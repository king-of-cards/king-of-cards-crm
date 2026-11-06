[CmdletBinding()]
param(
    [switch]$ExecuteApprovedRestart,
    [int]$ExpectedIdentityPid = 7416,
    [int]$ExpectedCrmPid = 20276,
    [int]$ExpectedGatewayPid = 38472
)
$ErrorActionPreference = 'Stop'
$backend = Split-Path -Parent $PSScriptRoot
$targets = @(
    @{ Name='Gateway'; Port=8080; Pid=$ExpectedGatewayPid; Pattern='api-gateway(?:-0\.1\.0-SNAPSHOT)?\.jar' },
    @{ Name='CRM'; Port=8082; Pid=$ExpectedCrmPid; Pattern='crm-service-0\.1\.0-SNAPSHOT\.jar' },
    @{ Name='Identity'; Port=8081; Pid=$ExpectedIdentityPid; Pattern='identity-service-0\.1\.0-SNAPSHOT\.jar' }
)
function Assert-Owner($target) {
    $owners = @(Get-NetTCPConnection -LocalPort $target.Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -ne 1 -or $owners[0] -ne $target.Pid) { throw "Unexpected $($target.Name) port owner. No automatic PID substitution is allowed." }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($target.Pid)"
    if ($process.Name -notmatch '^java(w)?\.exe$' -or $process.CommandLine -notmatch $target.Pattern) {
        throw "Process identity is uncertain for $($target.Name)."
    }
}
foreach ($target in $targets) { Assert-Owner $target }
$targets | ForEach-Object { Write-Output "$($_.Name): confirmed PID $($_.Pid), port $($_.Port)" }
Write-Output 'Plan: stop only these three services; offline verify/package main artifacts; start Identity, CRM, Gateway.'
Write-Output 'Identity: preserve approved evidence-gated production authentication. CRM: reads on, lead writes off. New Admin/bulk mutation gates remain closed.'
Write-Output 'Eureka, Vite, credentials, account data and database schemas are untouched. No login or import probe is included.'
if (-not $ExecuteApprovedRestart) { Write-Output 'PLAN ONLY. Explicit user approval and -ExecuteApprovedRestart are required to execute.'; return }

[xml]$identityLaunch = Get-Content -Raw (Join-Path $backend 'launch\IdentityService-Development.launch')
[xml]$gatewayLaunch = Get-Content -Raw (Join-Path $backend 'launch\ApiGateway-Development.launch')
$privatePath = @($identityLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq 'JWT_PRIVATE_KEY_LOCATION')[0].value
$publicPath = @($gatewayLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq 'JWT_PUBLIC_KEY_LOCATION')[0].value
$adc = @($identityLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq 'GOOGLE_APPLICATION_CREDENTIALS')[0].value
$evidence = Join-Path $backend 'docs\identity-production-verification-evidence.json'
if (-not (Test-Path -LiteralPath $adc -PathType Leaf) -or (Get-FileHash -LiteralPath $evidence).Hash -ne '8BBD916AE7287419A72AB291DDCBF46C2780A9AD9D86E835C285652F1A93360B') { throw 'Approved external credentials/evidence are unavailable.' }
foreach ($key in 'JWT_ISSUER','JWT_AUDIENCE') {
    $left = @($identityLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq $key)[0].value
    $right = @($gatewayLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq $key)[0].value
    if (-not $left -or $left -ne $right) { throw 'JWT configuration mismatch.' }
}
$java = (Get-Command java.exe -ErrorAction Stop).Source
& $java (Join-Path $PSScriptRoot 'VerifyDevelopmentJwtKeys.java') $privatePath $publicPath
if ($LASTEXITCODE -ne 0) { throw 'Existing JWT pair failed verification.' }
foreach ($target in $targets) {
    Assert-Owner $target
    Stop-Process -Id $target.Pid -ErrorAction Stop
    Wait-Process -Id $target.Pid -Timeout 15 -ErrorAction SilentlyContinue
}
foreach ($target in $targets) {
    if (@(Get-NetTCPConnection -LocalPort $target.Port -State Listen -ErrorAction SilentlyContinue).Count) { throw 'A target port is still occupied. No replacement process started.' }
}
$logs = Join-Path $backend ('local-dev\logs\management-deploy-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $logs | Out-Null
Push-Location $backend
try {
    # Windows cannot safely repackage the in-use main JARs; build only after the approved stop.
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd -o -B -pl 'api-gateway,identity-service,crm-service' -am verify > (Join-Path $logs 'verify.log') 2> (Join-Path $logs 'verify.stderr.log')
    $verifyExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($verifyExit -ne 0) { throw "Offline verification failed; no old-artifact fallback. Inspect $logs" }
    $saved = @{}
    try {
        foreach ($entry in $identityLaunch.launchConfiguration.mapAttribute.mapEntry) {
            $saved[$entry.key] = [Environment]::GetEnvironmentVariable($entry.key,'Process')
            [Environment]::SetEnvironmentVariable($entry.key,$entry.value,'Process')
        }
        if (@(Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue).Count) { throw 'Port 8081 became occupied. No duplicate Identity instance started.' }
        $jar = Join-Path $backend 'identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'
        $arguments = @('-jar', ('"'+$jar+'"'), '--spring.profiles.active=dev', '--server.port=8081',
            '--identity.bigquery.enabled=true', '--identity.bigquery.production-mutations-enabled=true',
            ('--identity.bigquery.verification-evidence="'+$evidence+'"'))
        $identityProcess = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logs 'identity.stdout.log') -RedirectStandardError (Join-Path $logs 'identity.stderr.log')
    } finally { foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') } }
    $deadline = (Get-Date).AddSeconds(75)
    $ready = $false
    do {
        $identityProcess.Refresh()
        if ($identityProcess.HasExited) { throw "Identity activation failed. Inspect $logs; no safeguard will be bypassed." }
        $health = & curl.exe -s --connect-timeout 1 --max-time 2 -o NUL -w '%{http_code}' 'http://localhost:8081/actuator/health/readiness'
        if ($LASTEXITCODE -eq 0 -and "$health" -eq '200') { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (-not $ready) { throw "Identity readiness timed out. Inspect $logs" }
    & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Start-LocalService.ps1') -Service crm -EnableBigQueryReads
    if ($LASTEXITCODE -ne 0) { throw 'CRM did not become healthy. No automatic rollback/retry.' }
    & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'Start-LocalService.ps1') -Service gateway
    if ($LASTEXITCODE -ne 0) { throw 'Gateway did not become healthy. No automatic rollback/retry.' }
    Write-Output "Deployment completed. Identity PID $($identityProcess.Id). Logs: $logs"
    Write-Output 'No login, account mutation, lead insertion or import was executed. CRM writes and new Admin/bulk mutations remain disabled.'
} finally { Pop-Location; $ErrorActionPreference = 'Stop' }
