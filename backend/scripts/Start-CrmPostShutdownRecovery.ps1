[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BundleManifestSha256,
    [switch]$ExecuteApprovedRecovery,
    [string]$Approval,
    [string]$ApprovedApplicationSha256
)
$ErrorActionPreference='Stop'
$backend='C:\kocbackend\king-of-cards-crm\backend'
$control=Join-Path $backend 'local-dev\crm-drain-control'
$archive=Join-Path $control 'fenced-handover-20261105'
$guard=Join-Path $backend 'crm-service\target-fenced-handover-verify\crm-fenced-guard.jar'
$bundle=$PSScriptRoot
# Run only the frozen bundle; workspace script is a review source, never executable authority.
if(-not $bundle.StartsWith($control+'\post-shutdown-recovery-', [StringComparison]::OrdinalIgnoreCase)){throw 'Run only the reviewed private recovery bundle.'}
$manifestPath=Join-Path $bundle 'manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $BundleManifestSha256){throw 'Recovery bundle manifest differs.'}
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
foreach($file in $manifest.files){
    if($file.name -notmatch '^[A-Za-z0-9_.-]+$'){throw 'Invalid recovery bundle filename.'}
    if((Get-FileHash -LiteralPath (Join-Path $bundle $file.name)).Hash -cne $file.sha256){throw 'Recovery bundle file changed.'}
}
foreach($required in @('Start-CrmPostShutdownRecovery.ps1','post-shutdown-guard.jar','PostShutdownPolicy.java','PostShutdownProbe.java')){
    if(@($manifest.files | Where-Object name -eq $required).Count -ne 1){throw 'Required reviewed bundle file missing.'}
}
if((Get-FileHash -LiteralPath $guard).Hash -cne '34FCCE45CA67531982D57042D8B93865EDCDBB6641B122B96D2F4043A9C19ECC'){throw 'Original guard changed.'}
if((Get-FileHash -LiteralPath (Join-Path $backend 'scripts\Start-CrmGatedService.ps1')).Hash -cne (Get-FileHash -LiteralPath (Join-Path $archive 'old-guarded-launcher.ps1')).Hash){throw 'Existing validated launcher changed.'}
# Validation ONLY: prepares saved credentials/arguments, checks runtime/configuration pins.
. (Join-Path $backend 'scripts\Start-CrmGatedService.ps1') -Artifact Updated
if($ExecuteApprovedRecovery -and ($Approval -cne 'APPROVE_ONE_POST_SHUTDOWN_READ_ONLY_START' -or $ApprovedApplicationSha256 -cne $appHash)){throw 'Separate exact-artifact recovery approval required.'}
$cpFile=Join-Path $backend 'crm-service\target\directional-local-classpath.txt'
if((Get-FileHash -LiteralPath $cpFile).Hash -cne (Get-FileHash -LiteralPath (Join-Path $archive 'runtime-classpath.txt')).Hash){throw 'Read-only dependency classpath changed.'}
$cp=(Join-Path $bundle 'post-shutdown-guard.jar')+';'+$guard+';'+(Get-Content -LiteralPath $cpFile -Raw).Trim()
Add-Type -AssemblyName System.Net.Http
function Read-HealthStatus([string]$url,[switch]$AllowNoBackendTimeout){
    $handler=[System.Net.Http.HttpClientHandler]::new();$handler.UseProxy=$false
    $client=[System.Net.Http.HttpClient]::new($handler)
    $client.DefaultRequestHeaders.Accept.ParseAdd('application/json')
    $client.Timeout=[TimeSpan]::FromSeconds(7)
    try{$response=$client.GetAsync($url,[System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult();try{return [int]$response.StatusCode}finally{$response.Dispose()}}
    catch{$cause=$_.Exception;$timedOut=$false;while($cause){if($cause -is [System.OperationCanceledException] -or $cause -is [System.TimeoutException]){$timedOut=$true};$cause=$cause.InnerException};if($AllowNoBackendTimeout -and $timedOut){return -1};throw ('Read-only health check failed: '+$url)}
    finally{$client.Dispose()}
}
function Confirm-Refused([System.Net.IPAddress]$address){
    $socket=[System.Net.Sockets.TcpClient]::new($address.AddressFamily)
    try{$task=$socket.ConnectAsync($address,8082);$task.GetAwaiter().GetResult();throw 'Direct CRM connection unexpectedly accepted.'}
    catch{ $cause=$_.Exception;while($cause.InnerException){$cause=$cause.InnerException};if($cause -isnot [System.Net.Sockets.SocketException] -or $cause.SocketErrorCode -ne [System.Net.Sockets.SocketError]::ConnectionRefused){throw 'Direct CRM closure cannot be established.'} }
    finally{$socket.Dispose()}
}
function Confirm-LocalState{
    if(Get-CimInstance Win32_Process -Filter 'ProcessId=3160'){throw 'Old PID exists; no recovery.'}
    $processes=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'")
    if(@($processes | Where-Object {-not $_.CommandLine}).Count -gt 0){throw 'Java process visibility incomplete.'}
    if(@($processes | Where-Object {$_.CommandLine -match 'crm-service-0\.1\.0-SNAPSHOT\.jar'}).Count -gt 0){throw 'A CRM application JVM is present.'}
    if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 8082).Count -gt 0){throw 'Port 8082 occupied.'}
    foreach($name in @('fenced-Updated-attempt.properties','fenced-Updated-start.properties','fenced-Rollback-attempt.properties','post-shutdown-recovery-attempt.properties')){if(Test-Path -LiteralPath (Join-Path $control $name)){throw 'A prior startup/recovery attempt exists; inspect without retry.'}}
    Confirm-Refused ([Net.IPAddress]::Loopback)
    Confirm-Refused ([Net.IPAddress]::IPv6Loopback)
    foreach($url in @('http://localhost:8080/actuator/health','http://localhost:8081/actuator/health','http://localhost:8761/actuator/health')){if((Read-HealthStatus $url) -ne 200){throw 'A dependency health check failed.'}}
    # A route timeout is NOT a fence. Independently require no CRM process,
    # refusal on both loopbacks and no CRM discovery instance, before and after.
    if((Read-HealthStatus 'http://localhost:8761/eureka/apps/CRM-SERVICE') -ne 404){throw 'CRM discovery instance remains; inspect before recovery.'}
    $route=Read-HealthStatus 'http://localhost:8080/api/crm/health' -AllowNoBackendTimeout
    if($route -notin @(-1,502,503,504)){throw 'Gateway CRM closure not established.'}
    if((Read-HealthStatus 'http://localhost:8761/eureka/apps/CRM-SERVICE') -ne 404){throw 'CRM discovery changed during closure probe.'}
    if($route -eq -1){Write-Output 'Gateway CRM health route timed out; closure relies on independent absent-JVM, refused-port and absent-discovery checks, not timeout alone.'}
    foreach($required in @('-Dloader.main=com.kingofcards.crm.ops.runtime.GatedCrmMain','-Dkoc.ops.admission=READS_ONLY',('-Dkoc.ops.application-sha256='+$appHash),('-Dkoc.ops.helper-sha256='+$helperHash))){if($arguments -cnotcontains $required){throw 'Mandatory startup fence missing.'}}
    foreach($required in @('--spring.profiles.active=dev','--server.port=8082','--crm.bigquery.enabled=true','--crm.directional.enabled=true','--crm.bigquery.writes-enabled=true','--crm.directional.writes-enabled=true','--crm.bigquery.schema-auto-create=false','--crm.bigquery.schema-init-enabled=false')){if($arguments -cnotcontains $required){throw 'Startup flags differ.'}}
}
& $java -cp $cp PostShutdownPolicy historical
if($LASTEXITCODE -ne 0){throw 'Preserved historical shutdown evidence differs.'}
Confirm-LocalState
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff')
$evidence=Join-Path $control ('post-shutdown-evidence-'+$stamp+'.properties')
$prior=[Environment]::GetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS','Process')
try{
    [Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$crm['GOOGLE_APPLICATION_CREDENTIALS'],'Process')
    & $java -cp $cp PostShutdownProbe $evidence
    if($LASTEXITCODE -ne 0){throw 'Fresh schema/data/job checks failed. No startup.'}
}finally{[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$prior,'Process')}
Confirm-LocalState
$local=Join-Path $control ('post-shutdown-local-'+$stamp+'.properties')
$lines=@('afterStartAdmission=READS_ONLY','releaseWrites=false',('timestamp='+[DateTime]::UtcNow.ToString('o')),('evidenceSha256='+(Get-FileHash -LiteralPath $evidence).Hash))
foreach($key in @('oldPidAbsent','noCrmJavaProcess','port8082Free','noPreviousStartupAttempt','gatewayCrmUnavailable','eurekaCrmAbsent','directIpv4Closed','directIpv6Closed','gatewayHealthy','identityHealthy','eurekaHealthy','bootstrapArgumentsVerified')){$lines+=($key+'=true')}
$stream=[IO.File]::Open($local,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::ASCII.GetBytes(($lines -join "`n")+"`n");$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
& $java -cp $cp PostShutdownPolicy check $evidence $local
if($LASTEXITCODE -ne 0){throw 'Fresh recovery evidence incomplete; no startup.'}
Write-Output ('Recovery evidence: '+$evidence)
Write-Output ('Recovery evidence SHA-256: '+(Get-FileHash -LiteralPath $evidence).Hash)
if(-not $ExecuteApprovedRecovery){Write-Output 'PREPARATION PASS ONLY. No service started/stopped. Separate approval required; execution repeats fresh checks.';return}
# Atomic shared marker also prevents the old blocked parent from launching later.
$marker=Join-Path $control 'fenced-Updated-attempt.properties'
$stream=[IO.File]::Open($marker,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::ASCII.GetBytes("appHash=$appHash`nmode=POST_SHUTDOWN_READ_ONLY_RECOVERY`n");$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$attempt=Join-Path $control 'post-shutdown-recovery-attempt.properties'
$stream=[IO.File]::Open($attempt,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::ASCII.GetBytes("originalCertificateSha256=FC7545BCE2203F9B0C480378B329A74BDD67058CB653CE841741E26AAC34E654`nbundleSha256=$BundleManifestSha256`nevidence=$evidence`nreleaseWrites=false`n");$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$logs=Join-Path $backend ('local-dev\logs\crm-post-shutdown-'+$stamp)
New-Item -ItemType Directory -Path $logs | Out-Null
$saved=@{}
if(Get-CimInstance Win32_Process -Filter 'ProcessId=3160'){throw 'Old PID reappeared; attempt consumed, report without retry.'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 8082).Count -gt 0){throw 'Port became occupied; attempt consumed, report without retry.'}
try{
    foreach($key in $crm.Keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$crm[$key],'Process')}
    $running=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'stdout.log') -RedirectStandardError (Join-Path $logs 'stderr.log')
}finally{foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
$receipt="pid=$($running.Id)`nappHash=$appHash`nbootstrapHash=$helperHash`nbundleSha256=$BundleManifestSha256`nreleaseWrites=false`n"
$stream=[IO.File]::Open((Join-Path $control 'post-shutdown-recovery-start.properties'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::ASCII.GetBytes($receipt);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
Write-Output ('ONE guarded recovery startup requested: PID '+$running.Id+'; logs '+$logs)
Write-Output 'No retry, rollback, termination or write reopening is automatic. Inspect health and fences; report any failure.'
