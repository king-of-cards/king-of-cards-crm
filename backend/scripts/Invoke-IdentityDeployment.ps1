[CmdletBinding()]
param(
 [ValidateSet('Prepare','Validate','Deploy','Status','Cancel','Verify','Release','Rollback')][string]$Mode='Validate',
 [string]$Bundle,
 [string]$BundleSha256,
 [string]$HelperSha256,
 [string]$ExecutionApproval,
 [string]$VendorBearerFile,
 [string]$VerificationFile
)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if($backend -cne 'C:\kocbackend\king-of-cards-crm\backend'){throw 'Unexpected workspace'}
$java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
$helper=Join-Path $backend 'identity-service\target-identity-deployment-verify-20261106\v2\identity-maintenance.jar'
$oldHash='2B7E8A0655EC5C87DD761A756D70A6D233F9EF09E93BAB564B0B23A538F328DC'
$newHash='8CC080E57C602C4D24CFABFACC35B7788B6D37D8B60AE2FA5038CB73777910B5'
$oldJar=Join-Path $backend 'identity-service\target\identity-service-0.1.0-SNAPSHOT.jar'
$newJar=Join-Path $backend 'identity-service\target-final-integration-verify-20261106\identity-service-0.1.0-SNAPSHOT.jar'
$deps=(Get-Content -LiteralPath (Join-Path $backend 'crm-service\target\directional-local-classpath.txt') -Raw).Trim()
$cp=$helper+';'+$deps
function Hash-Text([string]$value){$h=[Security.Cryptography.SHA256]::Create();try{([BitConverter]::ToString($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($value)))).Replace('-','')}finally{$h.Dispose()}}
function Save-Properties([hashtable]$values,[string]$path){
 $lines=@($values.Keys | Sort-Object | ForEach-Object {$_+'='+([string]$values[$_]).Replace('\','\\').Replace(':','\:').Replace("`n",'\n')})
 $staged=Join-Path (Split-Path -Parent $path) ('.writing-'+[guid]::NewGuid().ToString())
 $stream=[IO.File]::Open($staged,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try{$bytes=[Text.Encoding]::ASCII.GetBytes(($lines -join "`n")+"`n");$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
 # Windows same-directory MoveFile publishes the closed file without replacement.
 [IO.File]::Move($staged,$path)
}
function Read-Properties([string]$path){
 $p=@{};foreach($line in Get-Content -LiteralPath $path){if($line -match '^([^#!][^=]*)=(.*)$'){$p[$matches[1]]=$matches[2].Replace('\:',':').Replace('\=','=').Replace('\\','\')}};return $p
}
function Launch-Environment {
 [xml]$xml=Get-Content -LiteralPath (Join-Path $backend 'launch\IdentityService-Development.launch') -Raw
 $result=@{};foreach($entry in $xml.launchConfiguration.mapAttribute.mapEntry){$result[$entry.key]=$entry.value};return $result
}
function Set-PrivateControl([string]$directory){
 $identity=[Security.Principal.WindowsIdentity]::GetCurrent();$acl=[Security.AccessControl.DirectorySecurity]::new()
 $acl.SetOwner($identity.User);$acl.SetAccessRuleProtection($true,$false)
 foreach($sid in @($identity.User.Value,'S-1-5-18','S-1-5-32-544')){
  $rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),[Security.AccessControl.FileSystemRights]::FullControl,([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
  $acl.AddAccessRule($rule)
 }
 Set-Acl -LiteralPath $directory -AclObject $acl
}
function Assert-PrivateControl([string]$directory){
 $acl=Get-Acl -LiteralPath $directory;$owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
 if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $owner -or !$acl.AreAccessRulesProtected){throw 'Control ACL owner/inheritance differs'}
 $allowed=@($owner,'S-1-5-18','S-1-5-32-544')
 foreach($rule in $acl.Access){$sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value;$write=[int]$rule.FileSystemRights -band [int]([Security.AccessControl.FileSystemRights]::Write -bor [Security.AccessControl.FileSystemRights]::Delete -bor [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor [Security.AccessControl.FileSystemRights]::ChangePermissions -bor [Security.AccessControl.FileSystemRights]::TakeOwnership);if($rule.AccessControlType -eq 'Allow' -and $write -ne 0 -and $allowed -notcontains $sid){throw 'Other local user can modify control files'}}
}
function Assert-Pins($p){
 if($p['newJar.sha256'] -cne $newHash -or $p['oldJar.sha256'] -cne $oldHash){throw 'Unapproved Identity artifacts'}
 if((Get-FileHash -LiteralPath $Bundle).Hash -cne $BundleSha256){throw 'Bundle hash differs'}
 Assert-PrivateControl (Split-Path -Parent $Bundle);Assert-PrivateControl (Split-Path -Parent (Split-Path -Parent $Bundle))
 foreach($key in @($p.Keys | Where-Object {$_ -like '*.path'})){$name=$key.Substring(0,$key.Length-5);if((Get-FileHash -LiteralPath $p[$key]).Hash -cne $p[$name+'.sha256']){throw ('Pinned file changed: '+$name)}}
 $crm=Get-CimInstance Win32_Process -Filter 'ProcessId=50528'
 if(!$crm -or !$crm.CommandLine.Contains('-Dkoc.ops.admission=READS_ONLY') -or !$crm.CommandLine.Contains('target-source-precision-verify')){throw 'CRM READS_ONLY pin differs'}
 foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','SPRING_PROFILES_INCLUDE','LOADER_PATH','LOADER_MAIN')){if([Environment]::GetEnvironmentVariable($name,'Process')){throw ('Unexpected inherited startup override: '+$name)}}
}
function Assert-Identity($p,[int]$id,[string]$started,[string]$jar){
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$id"
 if(!$process -or $process.Name -ne 'java.exe' -or !$process.CommandLine.Contains($jar)){throw 'Identity process identity differs'}
 # Compare instants rather than formatting precision between CIM/.NET/Java.
 if([Math]::Abs(($process.CreationDate.ToUniversalTime()-[DateTime]::Parse($started).ToUniversalTime()).TotalSeconds) -gt 0.01){throw 'Identity PID was reused'}
 if(@(Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $id).Count -lt 1){throw 'Identity does not own 8081'}
}
function Assert-Free {
 if(@(Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue).Count){throw '8081 is occupied'}
 $live=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and ($_.CommandLine.Contains($oldJar) -or $_.CommandLine.Contains($newJar))})
 if($live.Count){throw 'Identity JVM is still alive; never terminate or replace an unhealthy JVM automatically'}
}
function Run-Java([string]$main,[string[]]$arguments){
 $log=Join-Path $root ('checker-'+[guid]::NewGuid().ToString()+'.log');$prior=$ErrorActionPreference
 try{$ErrorActionPreference='Continue'; & $java -cp $cp $main @arguments *> $log;$code=$LASTEXITCODE}finally{$ErrorActionPreference=$prior}
 Get-Content -LiteralPath $log | Where-Object {$_ -match '^IDENTITY_'} | ForEach-Object {Write-Host $_}
 if($code -ne 0){throw ('Safe Identity checker failed: '+$main+'; private diagnostic '+$log)}
}
function Audit($p){
 $file=Join-Path $root ('audit-'+[guid]::NewGuid().ToString()+'.properties')
 Run-Java 'com.kingofcards.identity.maintenance.AuditCli' @($Bundle,$BundleSha256,$file)
 $audit=Read-Properties $file
 if($audit['activeAuthJobs'] -ne '0' -or $audit['ownJobPagesComplete'] -ne 'true' -or $audit['orphans'] -ne '0'){throw 'Pending/uncertain Identity activity or integrity failure'}
 return $audit
}
function Same-Data($a,$b){foreach($n in @('accounts','sessions','orphans','accountDigest','sessionDigest','usersSchema','sessionsSchema')){if(!$a.ContainsKey($n) -or $a[$n] -cne $b[$n]){throw ('Identity data/schema differs: '+$n)}}}
function Command([int]$id,[string]$started,[string]$action,[hashtable]$extra=@{},[switch]$NoWait){
 $nonce=[guid]::NewGuid().ToString();$request=@{nonce=$nonce;pid=[string]$id;processStartedUtc=$started;action=$action;bundleSha256=$BundleSha256;issuedUtc=[DateTime]::UtcNow.ToString('o');expiresUtc=[DateTime]::UtcNow.AddSeconds(120).ToString('o')}
 foreach($key in $extra.Keys){$request[$key]=$extra[$key]}
 Save-Properties $request (Join-Path $root ($nonce+'.command.properties'))
 if($NoWait){return $nonce}
 $response=Join-Path $root ($nonce+'.response.properties');$deadline=[DateTime]::UtcNow.AddSeconds(120)
 while(!(Test-Path -LiteralPath $response)){
  if([DateTime]::UtcNow -gt $deadline){throw 'Control did not finish; do not repeat it or terminate the JVM'}
  if(!(Get-Process -Id $id -ErrorAction SilentlyContinue)){throw 'Identity exited unexpectedly; no automatic retry or rollback'}
  Start-Sleep -Milliseconds 250
 }
 $result=Read-Properties $response;if($result['ok'] -ne 'true'){throw ('Identity control refused '+$action+'; private evidence '+$response)};return $result
}
function Verify-Running($p,$running,[bool]$rollback){
 Assert-Pins $p;$id=[int]$running['pid'];$jar=if($rollback){$oldJar}else{$newJar};Assert-Identity $p $id $running['processStartedUtc'] $jar
 $status=Command $id $running['processStartedUtc'] 'inspect'
 if($status['gateMode'] -ne 'MUTATIONS_CLOSED'){throw 'New Identity admission is not closed'}
 $statusFile=Join-Path $root ('gate-status-'+[guid]::NewGuid().ToString()+'.properties');Save-Properties $status $statusFile
 Run-Java 'com.kingofcards.identity.maintenance.GateProbeCli' @($Bundle,$BundleSha256,$statusFile)
 $audit=Audit $p;Same-Data (Read-Properties (Join-Path $root 'certificate.properties')) $audit
 $http=Join-Path $root ('http-'+[guid]::NewGuid().ToString()+'.properties');$token=if($VendorBearerFile -and !$rollback){(Resolve-Path -LiteralPath $VendorBearerFile).Path}else{'NONE'}
 Run-Java 'com.kingofcards.identity.maintenance.VerifyHttpCli' @($Bundle,$BundleSha256,$http,$token)
 $checks=Read-Properties $http
 $checks['admissionProbes']='PASS'
 if($rollback){$checks['vendorProfile']='EXPECTED_ABSENT_OLD'}
 $checks['applicationSha256']=if($rollback){$oldHash}else{$newHash};$checks['processStartedUtc']=$running['processStartedUtc'];$checks['pid']=[string]$id
 if(!$rollback -and $checks['vendorProfile'] -ne 'PASS'){Write-Output 'Identity is healthy with admission CLOSED. Vendor-profile live authorization awaits a valid existing Vendor JWT file. Use Verify later; do not release admission.';return}
 $proof=Join-Path $root ('verified-'+$id+'-'+[guid]::NewGuid().ToString()+'.properties');Save-Properties $checks $proof
 Write-Output ('Identity verification saved; Vendor profile='+$checks['vendorProfile']+'. Admission remains CLOSED. PID='+$id+' VerificationFile='+$proof)
}
function Start-Gated($p,[bool]$rollback){
 Assert-Free;Assert-Pins $p
 $before=Audit $p;Same-Data (Read-Properties (Join-Path $root 'certificate.properties')) $before
 $kind=if($rollback){'rollback'}else{'start'};$marker=Join-Path $root ($kind+'-attempt.properties')
 Save-Properties @{issuedUtc=[DateTime]::UtcNow.ToString('o');bundleSha256=$BundleSha256;approval=$ExecutionApproval;admission='MUTATIONS_CLOSED'} $marker
 $jar=if($rollback){$oldJar}else{$newJar};$envMap=Launch-Environment;$saved=@{}
 $runtime=Join-Path $root ($kind+'-logs');New-Item -ItemType Directory -Path $runtime -ErrorAction Stop | Out-Null
 try{
  foreach($key in $envMap.Keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$envMap[$key],'Process')}
  $arguments=@(('"-Dloader.path='+$helper+'"'),('"-Dkoc.identity.bundle='+$Bundle+'"'),('-Dkoc.identity.bundle.sha256='+$BundleSha256),'-Dkoc.identity.gated=true','-cp',('"'+$jar+'"'),'org.springframework.boot.loader.launch.PropertiesLauncher','--spring.profiles.active=dev','--server.port=8081','--identity.bigquery.enabled=true','--identity.bigquery.production-mutations-enabled=true',('--identity.bigquery.verification-evidence="'+$p['evidence.path']+'"'),'--identity.bigquery.project-id=king-of-cards-509610','--identity.bigquery.dataset=shared_rds_data','--identity.bigquery.location=asia-south1','--server.shutdown=graceful','--spring.lifecycle.timeout-per-shutdown-phase=30s')
  $child=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'stdout.log') -RedirectStandardError (Join-Path $runtime 'stderr.log')
  $handle=$child.Handle;$started=$child.StartTime.ToUniversalTime().ToString('o')
  $running=@{pid=[string]$child.Id;processStartedUtc=$started;applicationSha256=($(if($rollback){$oldHash}else{$newHash}));jar=$jar;bundleSha256=$BundleSha256}
  Save-Properties $running (Join-Path $root ($kind+'-process.properties'))
 }finally{foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
 $deadline=[DateTime]::UtcNow.AddSeconds(120);$startup=Join-Path $root ('startup-'+$child.Id+'.properties')
 while(!(Test-Path -LiteralPath $startup)){
  $child.Refresh();if($child.HasExited){throw ('Identity exited. '+$kind+' attempt is spent; no automatic retry or rollback. Inspect '+$runtime)}
  if([DateTime]::UtcNow -gt $deadline){throw ('Identity is alive but not verified. Leave it running; inspect '+$runtime)}
  Start-Sleep -Milliseconds 500
 }
 # Use Java's exact process timestamp in subsequent nonce-bound commands.
 $startupState=Read-Properties $startup;$running['processStartedUtc']=$startupState['processStartedUtc']
 Save-Properties $running (Join-Path $root ($kind+'-ready-process.properties'))
 Verify-Running $p $running $rollback
}

if($Mode -eq 'Prepare'){
 if(!$HelperSha256 -or (Get-FileHash -LiteralPath $helper).Hash -cne $HelperSha256){throw 'Explicit verified helper hash required'}
 [xml]$gateway=Get-Content -LiteralPath (Join-Path $backend 'launch\ApiGateway-Development.launch') -Raw;$gatewayMap=@{};foreach($e in $gateway.launchConfiguration.mapAttribute.mapEntry){$gatewayMap[$e.key]=$e.value}
 $values=Launch-Environment;$credential=Get-Content -LiteralPath $values['GOOGLE_APPLICATION_CREDENTIALS'] -Raw | ConvertFrom-Json
 $pins=@{expectedPid='33820';helperSha256=$HelperSha256;principalSha256=(Hash-Text $credential.client_email);'controlOwner.name'=[Security.Principal.WindowsIdentity]::GetCurrent().Name;'controlSystem.name'=([Security.Principal.SecurityIdentifier]::new('S-1-5-18').Translate([Security.Principal.NTAccount]).Value);'controlAdministrators.name'=([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544').Translate([Security.Principal.NTAccount]).Value)}
 $files=@{newJar=$newJar;oldJar=$oldJar;helper=$helper;identityLaunch=(Join-Path $backend 'launch\IdentityService-Development.launch');gatewayLaunch=(Join-Path $backend 'launch\ApiGateway-Development.launch');privateKey=$values['JWT_PRIVATE_KEY_LOCATION'];publicKey=$gatewayMap['JWT_PUBLIC_KEY_LOCATION'];credentials=$values['GOOGLE_APPLICATION_CREDENTIALS'];evidence=(Join-Path $backend 'docs\identity-production-verification-evidence.json');archive=(Join-Path $backend 'local-dev\crm-drain-control\fenced-handover-20261105\manifest.properties');runner=$PSCommandPath}
 foreach($name in $files.Keys){$path=(Resolve-Path -LiteralPath $files[$name]).Path;$pins[$name+'.path']=$path;$pins[$name+'.sha256']=(Get-FileHash -LiteralPath $path).Hash}
 foreach($key in @('JWT_ISSUER','JWT_AUDIENCE')){if($values[$key] -cne $gatewayMap[$key]){throw 'Identity/Gateway claims differ'}}
 $expected=@{'identity.jwt.issuer'=$values['JWT_ISSUER'];'identity.jwt.audience'=$values['JWT_AUDIENCE'];'identity.jwt.private-key-location'=$values['JWT_PRIVATE_KEY_LOCATION'];'identity.jwt.access-token-ttl'=$values['IDENTITY_ACCESS_TOKEN_TTL'];'identity.refresh.token-ttl'=$values['IDENTITY_REFRESH_TOKEN_TTL'];'identity.cookie.secure'=$values['IDENTITY_COOKIE_SECURE'];'identity.cookie.same-site'=$values['IDENTITY_COOKIE_SAME_SITE'];'identity.cookie.allowed-origin'=$values['CORS_ALLOWED_ORIGIN'];'eureka.client.service-url.defaultZone'=$values['EUREKA_DEFAULT_ZONE']}
 foreach($key in $expected.Keys){if(!$expected[$key]){throw ('Saved setting unavailable: '+$key)};$pins['expected.'+$key]=Hash-Text $expected[$key]}
 $process=Get-CimInstance Win32_Process -Filter 'ProcessId=33820';if(!$process){throw 'Expected Identity process missing'};$pins['expectedStartedUtc']=$process.CreationDate.ToUniversalTime().ToString('o')
 $base=Join-Path $backend 'local-dev\identity-deployment';if(!(Test-Path -LiteralPath $base)){New-Item -ItemType Directory -Path $base | Out-Null}
 Set-PrivateControl $base
 $root=Join-Path $base ('bundle-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString());New-Item -ItemType Directory -Path $root | Out-Null
 Set-PrivateControl $root
 $Bundle=Join-Path $root 'bundle.properties';Save-Properties $pins $Bundle;$BundleSha256=(Get-FileHash -LiteralPath $Bundle).Hash
 Assert-Pins $pins;Assert-Identity $pins 33820 $pins['expectedStartedUtc'] $oldJar
 & $java (Join-Path $backend 'scripts\VerifyDevelopmentJwtKeys.java') $pins['privateKey.path'] $pins['publicKey.path'];if($LASTEXITCODE -ne 0){throw 'RSA pair invalid'}
 $audit=Audit $pins;Write-Output ('PREPARED Bundle='+$Bundle);Write-Output ('BUNDLE_SHA256='+$BundleSha256);Write-Output 'No admission control activated and no service started/stopped.';return
}
if(!$Bundle -or !$BundleSha256){throw 'An existing hash-pinned bundle is required'}
$Bundle=(Resolve-Path -LiteralPath $Bundle).Path;$root=Split-Path -Parent $Bundle
if(!$root.StartsWith((Join-Path $backend 'local-dev\identity-deployment\'),[StringComparison]::OrdinalIgnoreCase)){throw 'Bundle outside approved control directory'}
$pins=Read-Properties $Bundle;Assert-Pins $pins
if($Mode -eq 'Validate'){Assert-Identity $pins ([int]$pins['expectedPid']) $pins['expectedStartedUtc'] $oldJar;$null=Audit $pins;Write-Output 'VALIDATE PASS; no admission, stop or startup action';return}
if($Mode -eq 'Deploy'){
 if($ExecutionApproval -cne 'APPROVE_ONE_IDENTITY_DEPLOYMENT'){throw 'Separate deployment execution approval required'}
 Assert-Identity $pins ([int]$pins['expectedPid']) $pins['expectedStartedUtc'] $oldJar;$null=Audit $pins
 if(!$VendorBearerFile){throw 'An existing authorized Vendor JWT file is required BEFORE admission closure. No login/refresh will be created by this runner.'}
 $tokenFile=(Resolve-Path -LiteralPath $VendorBearerFile).Path
 Run-Java 'com.kingofcards.identity.maintenance.VerifyHttpCli' @($Bundle,$BundleSha256,(Join-Path $root ('prestart-vendor-'+[guid]::NewGuid().ToString()+'.properties')),$tokenFile,'PRESTART_TOKEN_CHECK')
 Save-Properties @{issuedUtc=[DateTime]::UtcNow.ToString('o');bundleSha256=$BundleSha256} (Join-Path $root 'deployment-attempt.properties')
 $nonce=[guid]::NewGuid().ToString();$install=Join-Path $root 'install.properties'
 # Java ProcessHandle timestamp is canonical; obtain the exact timestamp via existing
 # read-only observation when installation validates within the same 10 ms tolerance.
 Save-Properties @{nonce=$nonce;approval=$ExecutionApproval;pid=$pins['expectedPid'];processStartedUtc=$pins['expectedStartedUtc'];expiresUtc=[DateTime]::UtcNow.AddSeconds(30).ToString('o');helperSha256=$pins['helper.sha256'];bundle=$Bundle;bundleSha256=$BundleSha256} $install
 $caller=Start-Process -FilePath $java -ArgumentList @('-cp',('"'+$helper+'"'),'com.kingofcards.identity.maintenance.AttachCli',$pins['expectedPid'],('"'+$helper+'"'),('"'+$install+'"')) -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'attach.out.log') -RedirectStandardError (Join-Path $root 'attach.err.log')
 $callerHandle=$caller.Handle
 if(!$caller.WaitForExit(15000)){throw ('Attach did not return; preserve helper PID '+$caller.Id+'. Do not stop Identity, retry attach or force-kill anything')};$caller.WaitForExit();$caller.Refresh();if($caller.ExitCode -ne 0){throw 'Admission installation failed; no shutdown requested'}
 $state=Read-Properties (Join-Path $root 'installed.properties');$id=[int]$state['pid'];$started=$state['processStartedUtc']
 $drain=Command $id $started 'prepare-stop';$quietUntil=[DateTime]::Parse($drain['quietUntilUtc']).ToUniversalTime()
 Write-Output 'Identity mutations are closed; reads remain available during the 315-second request-deadline settling window. Cancellation is available through the independent file channel.'
 while([DateTime]::UtcNow -lt $quietUntil){
  Start-Sleep -Seconds 5
  $status=Command $id $started 'inspect'
  if($status['gateMode'] -ne 'MUTATIONS_CLOSED' -or $status['gateMutations'] -ne '0' -or $status['mutationThreads'] -ne '0'){throw 'Admission/activity changed during settling; no shutdown permitted'}
 }
 $certificate=Command $id $started 'certify';$certPath=Join-Path $root 'certificate.properties'
 Assert-Pins $pins;Assert-Identity $pins $id $started $oldJar
 $stopNonce=Command $id $started 'stop' @{approval=$ExecutionApproval;certificateSha256=(Get-FileHash -LiteralPath $certPath).Hash} -NoWait
 $deadline=[DateTime]::UtcNow.AddSeconds(120)
 while(Get-Process -Id $id -ErrorAction SilentlyContinue){
  if(Test-Path -LiteralPath (Join-Path $root ($stopNonce+'.response.properties'))){throw 'Shutdown was refused; inspect response, preserve JVM, do not repeat'}
  if([DateTime]::UtcNow -gt $deadline){throw 'Identity has not exited; do not force-kill or start replacement'};Start-Sleep -Milliseconds 250
 }
 if(!(Test-Path -LiteralPath (Join-Path $root 'stop-consumed.properties'))){throw 'No approved shutdown marker; no startup permitted'}
 Assert-Free;Start-Gated $pins $false;return
}
if($Mode -eq 'Rollback'){
 if($ExecutionApproval -cne 'APPROVE_ONE_IDENTITY_ROLLBACK'){throw 'Separate rollback execution approval required'}
 if(!(Test-Path -LiteralPath (Join-Path $root 'start-attempt.properties')) -or !(Test-Path -LiteralPath (Join-Path $root 'stop-consumed.properties'))){throw 'Approved replacement/shutdown record missing'}
 if(Test-Path -LiteralPath (Join-Path $root 'rollback-attempt.properties')){throw 'Rollback attempt is already spent'}
 Assert-Free;Start-Gated $pins $true;return
}
if($Mode -eq 'Cancel'){
 if($ExecutionApproval -cne 'APPROVE_IDENTITY_ADMISSION_RELEASE'){throw 'Explicit cancellation/admission-release approval required'}
 $running=Read-Properties (Join-Path $root 'installed.properties');Assert-Identity $pins ([int]$running['pid']) $running['processStartedUtc'] $oldJar
 $null=Command ([int]$running['pid']) $running['processStartedUtc'] 'cancel' @{approval=$ExecutionApproval};Write-Output 'Original Identity admission restored; no service restarted; CRM untouched';return
}
$record=if(Test-Path -LiteralPath (Join-Path $root 'rollback-ready-process.properties')){'rollback-ready-process.properties'}elseif(Test-Path -LiteralPath (Join-Path $root 'start-ready-process.properties')){'start-ready-process.properties'}else{'installed.properties'}
$running=Read-Properties (Join-Path $root $record);$isRollback=$record -like 'rollback-*'
if($Mode -eq 'Status'){$status=Command ([int]$running['pid']) $running['processStartedUtc'] 'inspect';Write-Output ($status|ConvertTo-Json -Compress);return}
if($Mode -eq 'Verify'){Verify-Running $pins $running $isRollback;return}
if($Mode -eq 'Release'){
 if($ExecutionApproval -cne 'APPROVE_IDENTITY_ADMISSION_RELEASE'){throw 'Explicit release approval required'}
 if(!$VerificationFile){throw 'An explicit fresh verification file is required'};$proof=(Resolve-Path -LiteralPath $VerificationFile).Path
 $null=Command ([int]$running['pid']) $running['processStartedUtc'] 'release' @{approval=$ExecutionApproval;verificationPath=$proof;verificationSha256=(Get-FileHash -LiteralPath $proof).Hash};Write-Output 'Verified Identity admission restored; CRM remains READS_ONLY';return
}
