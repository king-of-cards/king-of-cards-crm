[CmdletBinding()]
param(
 [ValidateSet('inspect','pause-auth','drain-all')][string]$Action='inspect',
 [int]$ExpectedPid=33820,
 [Parameter(Mandatory)][string]$ExpectedHelperSha256,
 [string]$AdmissionApproval
)
$ErrorActionPreference='Stop'
if($Action -eq 'pause-auth' -and $AdmissionApproval -cne 'APPROVE_TEMPORARY_IDENTITY_AUTH_FENCE'){throw 'Separate live authentication-admission approval required.'}
if($Action -eq 'drain-all' -and $AdmissionApproval -cne 'APPROVE_IDENTITY_ALL_ADMISSION_DRAIN'){throw 'Separate all-request drain approval required.'}
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if($backend -cne 'C:\kocbackend\king-of-cards-crm\backend'){throw 'Unexpected workspace.'}
$artifact=Join-Path $backend 'identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'
$appHash='2B7E8A0655EC5C87DD761A756D70A6D233F9EF09E93BAB564B0B23A538F328DC'
$build=Join-Path $backend 'identity-service\target-identity-handover-verify-20261106'
$helper=Join-Path $build 'identity-admission-observer.jar'
if((Get-FileHash -LiteralPath $helper).Hash -cne $ExpectedHelperSha256 -or (Get-FileHash -LiteralPath $artifact).Hash -cne $appHash){throw 'Observer/application artifact pin differs.'}
$target=Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedPid"
if(!$target -or $target.Name -ne 'java.exe' -or !$target.CommandLine.Contains($artifact)){throw 'Expected existing Identity JVM identity differs.'}
if(@(Get-NetTCPConnection -State Listen -LocalPort 8081 | Where-Object OwningProcess -eq $ExpectedPid).Count -lt 1){throw 'Expected Identity JVM does not own 8081.'}
$crm=Get-CimInstance Win32_Process -Filter 'ProcessId=50528';if(!$crm -or !$crm.CommandLine.Contains('-Dkoc.ops.admission=READS_ONLY')){throw 'Expected READS_ONLY CRM differs.'}
[xml]$launch=Get-Content -LiteralPath (Join-Path $backend 'launch\IdentityService-Development.launch') -Raw
$values=@{};foreach($entry in $launch.launchConfiguration.mapAttribute.mapEntry){$values[$entry.key]=$entry.value}
$account=Get-Content -LiteralPath $values['GOOGLE_APPLICATION_CREDENTIALS'] -Raw | ConvertFrom-Json
function Text-Sha([string]$value){$hash=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($value)))).Replace('-','')}finally{$hash.Dispose()}}
$root=Join-Path $backend 'local-dev\identity-handover-control';if(!(Test-Path -LiteralPath $root)){New-Item -ItemType Directory -Path $root | Out-Null}
$nonce=[guid]::NewGuid().ToString();$request=Join-Path $root ('request-'+$nonce+'.properties')
$properties=@{
 pid=[string]$ExpectedPid; action=$Action; nonce=$nonce; expiresUtc=[DateTime]::UtcNow.AddSeconds(60).ToString('o');
 helperSha256=$ExpectedHelperSha256;applicationSha256=$appHash;approval=$AdmissionApproval;
 expectedPrincipalSha256=(Text-Sha $account.client_email);
 'expectedSha256.identity.jwt.issuer'=(Text-Sha $values['JWT_ISSUER']);
 'expectedSha256.identity.jwt.audience'=(Text-Sha $values['JWT_AUDIENCE'])
}
$lines=@($properties.Keys | Sort-Object | ForEach-Object { $_+'='+([string]$properties[$_]).Replace('\','\\').Replace(':','\:') })
$stream=[IO.File]::Open($request,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::ASCII.GetBytes(($lines -join "`n")+"`n");$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$requestHash=(Get-FileHash -LiteralPath $request).Hash
$java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
$stdout=Join-Path $root ('caller-'+$nonce+'.out.log');$stderr=Join-Path $root ('caller-'+$nonce+'.err.log')
$arguments=@('-cp',('"'+(Join-Path $build 'classes')+'"'),'IdentityObserveCli',[string]$ExpectedPid,('"'+$helper+'"'),('"'+$request+'|'+$requestHash+'"'))
$caller=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$callerHandle=$caller.Handle
if(!$caller.WaitForExit(15000)){Write-Output ('INSPECTION_INCOMPLETE: caller PID '+$caller.Id+' did not return. Do not stop Identity or retry attach. Caller preserved.');return}
$caller.WaitForExit()
$caller.Refresh()
if($caller.ExitCode -ne 0){throw ('Identity observer failed; inspect private diagnostics for nonce '+$nonce+'. No stop/start action exists.')}
$observation=Join-Path $root ('observation-'+$nonce+'.properties')
if(!(Test-Path -LiteralPath $observation)){throw 'No complete observation received; do not deploy.'}
Get-Content -LiteralPath $observation | Where-Object {$_ -notmatch '^#' -and $_ -ne ''}
Write-Output ('Observation file: '+$observation)
Write-Output 'Observation is not a closed-admission certificate or deployment approval. No stop/start action exists.'
