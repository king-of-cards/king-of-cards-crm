[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('discovery','identity','gateway','crm')][string]$Service,
    [string]$BuildDirectory,
    [switch]$EnableBigQueryReads,
    [ValidateSet('target','target-directional-verify','target-timestamp-safe-verify','target-preview-verify','target-admin-vendor-verify','target-original-csv-verify','target-confirmed-source-verify','target-source-precision-verify','target-final-integration-verify-20261105','target-final-integration-verify-20261106','target-final-parser-integration-verify-20261106','target-final-receipt-integration-verify-20261106','target-routing-verify-20261106')][string]$ArtifactSubdirectory='target',
    [switch]$InitializeDirectionalSchema,
    [switch]$DeferDirectionalReads,
    [switch]$EnableDirectionalWrites,
    [switch]$EnableVerifiedIdentityWrites,
    [string]$VerificationEvidence,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
if($DeferDirectionalReads -and ($InitializeDirectionalSchema -or $EnableDirectionalWrites)){throw 'Deferred directional reads cannot be combined with schema initialization or directional writes.'}
if(($InitializeDirectionalSchema -or $EnableDirectionalWrites -or $EnableVerifiedIdentityWrites) -and !$EnableBigQueryReads){throw 'Explicit real BigQuery reads are required; no fallback is allowed.'}
if($EnableVerifiedIdentityWrites -and (!(Test-Path -LiteralPath $VerificationEvidence -PathType Leaf))){throw 'Reviewed verification evidence is required; activation checks will not be bypassed.'}
$backend = Split-Path -Parent $PSScriptRoot
if([string]::IsNullOrWhiteSpace($BuildDirectory)) { $BuildDirectory=$backend }
$launchRoot = Join-Path $backend 'launch'
$settings = @{
    discovery = @{ Module='discovery-server'; Launch='DiscoveryServer-Development.launch'; Port=8761; Main='com.kingofcards.discovery.DiscoveryServerApplication' }
    identity = @{ Module='identity-service'; Launch='IdentityService-Development.launch'; Port=8081; Main='com.kingofcards.identity.IdentityServiceApplication' }
    gateway = @{ Module='api-gateway'; Launch='ApiGateway-Development.launch'; Port=8080; Main='com.kingofcards.gateway.ApiGatewayApplication' }
    crm = @{ Module='crm-service'; Launch='CrmService-Development.launch'; Port=8082; Main='com.kingofcards.crm.CrmServiceApplication' }
}[$Service]
if (-not $ValidateOnly -and @(Get-NetTCPConnection -State Listen -LocalPort $settings.Port -ErrorAction SilentlyContinue).Count -gt 0) {
    throw "Port $($settings.Port) is occupied. No duplicate process will be started."
}
[xml]$launch = Get-Content -Raw (Join-Path $launchRoot $settings.Launch)
$main = @($launch.launchConfiguration.stringAttribute | Where-Object key -eq 'org.eclipse.jdt.launching.MAIN_TYPE')[0].value
if ($main -ne $settings.Main) { throw 'Saved launch main class does not match the expected service.' }
$verifiedBuild=(Resolve-Path -LiteralPath $BuildDirectory -ErrorAction Stop).Path
if(-not [string]::Equals($verifiedBuild.TrimEnd('\'),$backend.TrimEnd('\'),[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Start only from the main backend project. Verification snapshots and runtime copies are not launch sources.'
}
$jar = Join-Path $verifiedBuild ($settings.Module+'\'+$ArtifactSubdirectory+'\'+$settings.Module+'-0.1.0-SNAPSHOT.jar')
if (-not (Test-Path -LiteralPath $jar)) { throw 'Verified service artifact is missing.' }
$buildInputs=@(Get-Item (Join-Path $verifiedBuild 'pom.xml'))+@(Get-Item (Join-Path $verifiedBuild ($settings.Module+'\pom.xml')))+@(Get-ChildItem (Join-Path $verifiedBuild ($settings.Module+'\src\main')) -Recurse -File)
if(@($buildInputs | Where-Object LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc).Count -gt 0) {
    throw 'Artifact predates its Maven/source configuration. Run a successful Maven verify before starting.'
}
# Refuse to silently deploy an older snapshot after a source/configuration change.
$modulePath=Join-Path $backend $settings.Module
foreach($file in (@(Get-Item (Join-Path $modulePath 'pom.xml'))+@(Get-ChildItem (Join-Path $modulePath 'src\main') -Recurse -File))) {
    $relative=$file.FullName.Substring($backend.Length+1)
    $verified=Join-Path $verifiedBuild $relative
    if (-not (Test-Path -LiteralPath $verified) -or (Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath $verified).Hash) {
        throw "Source differs from the verified build: $relative"
    }
}
$runtime = Join-Path (Join-Path $backend 'local-dev') ('logs\'+$Service+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$saved = @{}
try {
    foreach ($entry in $launch.launchConfiguration.mapAttribute.mapEntry) {
        if($entry.key -eq 'GOOGLE_APPLICATION_CREDENTIALS' -and $env:GOOGLE_APPLICATION_CREDENTIALS){continue}
        $saved[$entry.key] = [Environment]::GetEnvironmentVariable($entry.key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.key, $entry.value, 'Process')
    }
    if ($Service -eq 'identity' -and -not (Test-Path -LiteralPath $env:JWT_PRIVATE_KEY_LOCATION)) { throw 'Existing development private key is unavailable.' }
    if ($Service -eq 'gateway' -and -not (Test-Path -LiteralPath $env:JWT_PUBLIC_KEY_LOCATION)) { throw 'Existing development public key is unavailable.' }
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    if ($Service -in @('identity','gateway')) {
        [xml]$identityLaunch=Get-Content -Raw (Join-Path $launchRoot 'IdentityService-Development.launch')
        [xml]$gatewayLaunch=Get-Content -Raw (Join-Path $launchRoot 'ApiGateway-Development.launch')
        $privatePath=@($identityLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq 'JWT_PRIVATE_KEY_LOCATION')[0].value
        $publicPath=@($gatewayLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq 'JWT_PUBLIC_KEY_LOCATION')[0].value
        foreach($key in @('JWT_ISSUER','JWT_AUDIENCE')) {
            $identityValue=@($identityLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq $key)[0].value
            $gatewayValue=@($gatewayLaunch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq $key)[0].value
            if(-not $identityValue -or $identityValue -ne $gatewayValue){throw 'Saved Identity/Gateway issuer or audience does not match.'}
        }
        & $java (Join-Path $backend 'scripts\VerifyDevelopmentJwtKeys.java') $privatePath $publicPath
        if($LASTEXITCODE -ne 0){throw 'Existing development JWT pair failed validation; service not started.'}
    }
    if($EnableBigQueryReads -and $Service -in @('identity','crm') -and
        (-not $env:GOOGLE_APPLICATION_CREDENTIALS -or -not (Test-Path -LiteralPath $env:GOOGLE_APPLICATION_CREDENTIALS -PathType Leaf))) {
        throw 'Approved external GOOGLE_APPLICATION_CREDENTIALS file is required for metadata/read-only startup.'
    }
    $arguments=@('-jar', ('"'+$jar+'"'), '--spring.profiles.active=dev', ('--server.port='+$settings.Port))
    # Safety overrides are process-local and cannot be overridden by inherited environment JSON.
    $reads=if($EnableBigQueryReads){'true'}else{'false'}
    if ($Service -eq 'identity') {
        $write=if($EnableVerifiedIdentityWrites){'true'}else{'false'}
        $arguments+=@(('--identity.bigquery.enabled='+$reads),('--identity.bigquery.production-mutations-enabled='+$write))
        if($EnableVerifiedIdentityWrites){$arguments+=('--identity.bigquery.verification-evidence="'+(Resolve-Path -LiteralPath $VerificationEvidence).Path+'"')}
    }
    if ($Service -eq 'crm') {
        $auto=if($InitializeDirectionalSchema){'true'}else{'false'};$write=if($EnableDirectionalWrites){'true'}else{'false'}
        $directional=if($DeferDirectionalReads){'false'}else{$reads}
        $arguments+=@(('--crm.bigquery.enabled='+$reads),('--crm.directional.enabled='+$directional),('--crm.bigquery.writes-enabled='+$write),('--crm.directional.writes-enabled='+$write),('--crm.bigquery.schema-auto-create='+$auto),'--crm.bigquery.schema-init-enabled=false')
    }
    if($ValidateOnly) {
        Write-Output "Preflight PASS: $Service, main artifact $jar, BigQuery read mode $reads. No service started."
        return
    }
    New-Item -ItemType Directory -Path $runtime -ErrorAction Stop | Out-Null
    $running = Start-Process -FilePath $java -ArgumentList $arguments `
        -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log')
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
Write-Output "Started $Service PID $($running.Id), port $($settings.Port), logs: $runtime"
$deadline = (Get-Date).AddSeconds(60)
do {
    $running.Refresh()
    if ($running.HasExited) { throw "Service exited; inspect logs in $runtime" }
    $status = & curl.exe --silent --connect-timeout 1 --max-time 2 --noproxy '*' --output NUL --write-out '%{http_code}' "http://localhost:$($settings.Port)/actuator/health"
    $ready=$true
    if($Service -ne 'gateway') {
        $readiness=& curl.exe --silent --connect-timeout 1 --max-time 2 --noproxy '*' --output NUL --write-out '%{http_code}' "http://localhost:$($settings.Port)/actuator/health/readiness"
        $ready=$LASTEXITCODE -eq 0 -and "$readiness" -eq '200'
    }
    if ($LASTEXITCODE -eq 0 -and "$status" -eq '200' -and $ready) { Write-Output 'Health/readiness: HTTP 200. Only explicitly requested activation flags were passed.'; exit 0 }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
throw "Health did not become ready; inspect logs in $runtime"
