[CmdletBinding()]
param([ValidateSet('Updated','Rollback')][string]$Role='Updated',[string]$ArchivePath,[string]$ArchiveManifestSha256,[switch]$ExecuteApprovedFencedStart,[string]$ApprovedAppHash,[int]$ExitedProcessId,[string]$CertificatePath,[string]$CertificateSha256)
$ErrorActionPreference='Stop'
$backend='C:\kocbackend\king-of-cards-crm\backend'
# Existing validated launcher prepares the exact env/arguments and returns without execution.
if($ArchivePath){if((Get-FileHash -LiteralPath (Join-Path $backend 'scripts\Start-CrmGatedService.ps1')).Hash -ne (Get-FileHash -LiteralPath (Join-Path $ArchivePath 'old-guarded-launcher.ps1')).Hash){throw 'Existing launcher changed.'}}
. (Join-Path $backend 'scripts\Start-CrmGatedService.ps1') -Artifact $Role
$guard=Join-Path $backend 'crm-service\target-fenced-handover-verify\crm-fenced-guard.jar'
$guardHash='34FCCE45CA67531982D57042D8B93865EDCDBB6641B122B96D2F4043A9C19ECC'
if((Get-FileHash -LiteralPath $guard).Hash -cne $guardHash){throw 'Fenced guard pin differs.'}
if($ArchivePath){& $java -cp $guard (Join-Path $ArchivePath 'VerifyFencedStart.java') archive $ArchivePath $ArchiveManifestSha256;if($LASTEXITCODE -ne 0){throw 'UNKNOWN archive invalid.'}}
if(-not $ExecuteApprovedFencedStart){Write-Output ('FENCED '+$Role+' validation PASS; no process started/stopped; historical UNKNOWN is retained.');return}
if(-not $ArchivePath -or -not $CertificatePath -or $ApprovedAppHash -cne $appHash -or $ExitedProcessId -lt 1){throw 'Separate exact-artifact approval, retained archive, certificate and exited PID required.'}
& $java -cp $guard (Join-Path $ArchivePath 'VerifyFencedStart.java') $Role $CertificatePath $CertificateSha256 $ArchivePath $ArchiveManifestSha256 $guardHash
if($LASTEXITCODE -ne 0){throw 'Fenced start certificate invalid.'}
if(Get-CimInstance Win32_Process -Filter "ProcessId=$ExitedProcessId"){throw 'Expected previous/failed process has not exited.'}
if($Role -eq 'Updated' -and $ExitedProcessId -ne 3160){throw 'Only reviewed previous PID 3160 may be replaced.'}
if($Role -eq 'Rollback'){$receipt=Get-Content -LiteralPath (Join-Path $backend 'local-dev\crm-drain-control\fenced-Updated-start.properties');$expected='pid='+$ExitedProcessId;if($receipt -notcontains $expected){throw 'Exited failed new PID does not match gated startup receipt.'}}
$processes=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'")
if(@($processes|Where-Object {-not $_.CommandLine}).Count -gt 0){throw 'Cannot establish Java process identity.'}
if(@($processes|Where-Object {$_.CommandLine -match 'king-of-cards-crm[\\/]backend[\\/]crm-service[\\/].*crm-service-0\.1\.0-SNAPSHOT\.jar'}).Count -gt 0){throw 'Another CRM process exists.'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq 8082).Count -gt 0){throw 'Port 8082 is occupied.'}
if($Role -eq 'Rollback'){
 $cpFile=Join-Path $backend 'crm-service\target\directional-local-classpath.txt'
 if((Get-FileHash -LiteralPath $cpFile).Hash -ne (Get-FileHash -LiteralPath (Join-Path $ArchivePath 'runtime-classpath.txt')).Hash){throw 'Read-only classpath changed.'}
 $cp=(Get-Content -LiteralPath $cpFile -Raw).Trim();$check=Join-Path $backend ('crm-service\target-fenced-handover-verify\rollback-check-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'));New-Item -ItemType Directory -Path $check|Out-Null
 & (Join-Path (Split-Path $java) 'javac.exe') -proc:none -cp ($guard+';'+$cp) -d $check (Join-Path $ArchivePath 'RollbackCompatibility.java') (Join-Path $ArchivePath 'CommitReceiptInvestigation.java');if($LASTEXITCODE -ne 0){throw 'Read-only compatibility check could not compile.'}
 $priorCredential=[Environment]::GetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS','Process')
 try{[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$crm['GOOGLE_APPLICATION_CREDENTIALS'],'Process');& $java -cp ($check+';'+$guard+';'+$cp) RollbackCompatibility $ArchivePath $ArchiveManifestSha256;if($LASTEXITCODE -ne 0){throw 'Rollback compatibility not confirmed; no startup.'}}
 finally{[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$priorCredential,'Process')}
}
$control=Join-Path $backend 'local-dev\crm-drain-control'
$attempt=Join-Path $control ('fenced-'+$Role+'-attempt.properties')
$stream=[IO.File]::Open($attempt,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read);try{$bytes=[Text.Encoding]::ASCII.GetBytes('appHash='+$appHash);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$logs=Join-Path $backend ('local-dev\logs\crm-fenced-'+$Role+'-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'));New-Item -ItemType Directory -Path $logs|Out-Null
$saved=@{}
try{foreach($key in $crm.Keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$crm[$key],'Process')};$running=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'stdout.log') -RedirectStandardError (Join-Path $logs 'stderr.log')}
finally{foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
$record="pid=$($running.Id)`nappHash=$appHash`nbootstrapHash=$helperHash`narchiveManifestSha256=$ArchiveManifestSha256`nreleaseWrites=false`n"
$stream=[IO.File]::Open((Join-Path $control ('fenced-'+$Role+'-start.properties')),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read);try{$bytes=[Text.Encoding]::ASCII.GetBytes($record);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
Write-Output ('ONE gated '+$Role+' startup requested. PID '+$running.Id+'; logs '+$logs+'; historical UNKNOWN retained; writes remain blocked.')
Write-Output 'On failure report. No retry/rollback/termination is automatic.'
