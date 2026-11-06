$ErrorActionPreference='Stop'
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$scriptFile=Join-Path $backend 'scripts\Invoke-IdentityDeployment.ps1'
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($scriptFile,[ref]$tokens,[ref]$errors)
if(@($errors).Count){throw 'Runner parse failure'}
$functions=$ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst]},$true)
foreach($f in $functions){. ([scriptblock]::Create($f.Extent.Text))}
$passed=0
function Check($ok,$message){if(!$ok){throw $message};$script:passed++}
function Reject([scriptblock]$call){$refused=$false;try{& $call}catch{$refused=$true};Check $refused 'Unsafe runner condition accepted'}
$taskDirectory=Join-Path $backend ('identity-service\target-identity-deployment-verify-20261106\runner-fixture-'+[guid]::NewGuid().ToString());New-Item -ItemType Directory -Path $taskDirectory | Out-Null
$root=$taskDirectory;$Bundle=Join-Path $root 'bundle.properties';Save-Properties @{example='C:\safe\path';stamp='2026-11-06T01:00:00Z'} $Bundle;$BundleSha256=(Get-FileHash $Bundle).Hash
Reject {Assert-PrivateControl $root};Set-PrivateControl $root;Assert-PrivateControl $root;$passed++
# Parent below is a shared fixture build directory, not a production control directory.
# The real ACL guard was exercised above; stub only for unrelated process-policy tests.
function Assert-PrivateControl([string]$directory){}
$roundtrip=Read-Properties $Bundle;Check ($roundtrip.example -ceq 'C:\safe\path') 'Properties path roundtrip';Check ($roundtrip.stamp -ceq '2026-11-06T01:00:00Z') 'Properties timestamp roundtrip';Reject {Save-Properties @{} $Bundle}
$oldHash='old-pin';$newHash='new-pin';$oldJar='C:\fixture\identity\old.jar';$newJar='C:\fixture\identity\new.jar';$pins=@{'oldJar.sha256'=$oldHash;'newJar.sha256'=$newHash}
$script:fixtureConnections=@();$script:fixtureProcesses=@();$script:fixtureCrm=@{CommandLine='java -Dkoc.ops.admission=READS_ONLY target-source-precision-verify'}
function Get-NetTCPConnection {param($State,$LocalPort,$ErrorAction) $script:fixtureConnections}
function Get-CimInstance {param($ClassName,$Filter) if($Filter -eq 'ProcessId=50528'){return $script:fixtureCrm};return $script:fixtureProcesses}
Assert-Pins $pins;$passed++
$wrong=$pins.Clone();$wrong['oldJar.sha256']='wrong';Reject {Assert-Pins $wrong}
$correctBundleHash=$BundleSha256;$BundleSha256='different';Reject {Assert-Pins $pins};$BundleSha256=$correctBundleHash
$script:fixtureCrm=@{CommandLine='java writable-crm'};Reject {Assert-Pins $pins};$script:fixtureCrm=@{CommandLine='java -Dkoc.ops.admission=READS_ONLY target-source-precision-verify'}
Assert-Free;$passed++;$script:fixtureConnections=@(@{OwningProcess=22});Reject {Assert-Free};$script:fixtureConnections=@();$script:fixtureProcesses=@(@{CommandLine=('java '+$newJar)});Reject {Assert-Free};$script:fixtureProcesses=@()
$fixtureNow=[DateTime]::UtcNow;$script:fixtureProcesses=@{Name='java.exe';CommandLine=('java '+$oldJar);CreationDate=$fixtureNow};$script:fixtureConnections=@(@{OwningProcess=33820})
Assert-Identity $pins 33820 $fixtureNow.ToString('o') $oldJar;$passed++;Reject {Assert-Identity $pins 33820 $fixtureNow.AddSeconds(-2).ToString('o') $oldJar};Reject {Assert-Identity $pins 33820 $fixtureNow.ToString('o') $newJar}
$data=@{};foreach($n in @('accounts','sessions','orphans','accountDigest','sessionDigest','usersSchema','sessionsSchema')){$data[$n]='same'};Same-Data $data $data;$passed++
foreach($n in $data.Keys.Clone()){$other=$data.Clone();$other[$n]='changed';Reject {Same-Data $data $other}}
# Exercise the actual Start-Gated orchestration with all process/network boundaries stubbed.
function Assert-Free {} function Assert-Pins($p) {} function Audit($p){return $data} function Launch-Environment {return @{}}
$script:starts=0
function Start-Process {param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError) $script:starts++;$p=[pscustomobject]@{Handle=0;StartTime=[DateTime]::UtcNow;Id=12345;HasExited=$true};$p | Add-Member -MemberType ScriptMethod -Name Refresh -Value {};return $p}
Save-Properties $data (Join-Path $root 'certificate.properties');$ExecutionApproval='offline-fixture';$java='not-executed';$helper='not-executed';$backend=$taskDirectory
Reject {Start-Gated @{'evidence.path'='not-executed'} $false};Check ($script:starts -eq 1) 'Fixture startup count differs'
Reject {Start-Gated @{'evidence.path'='not-executed'} $false};Check ($script:starts -eq 1) 'Spent startup attempt was reused'
Check (!$ast.Extent.Text.Contains('Stop-Process') -and !$ast.Extent.Text.Contains('taskkill')) 'Forced termination present'
Check ($ast.Extent.Text.Contains('Separate rollback execution approval required')) 'Rollback approval missing'
Write-Output ('IDENTITY_RUNNER_OFFLINE_PASS checks='+$passed+'; no live process/network/database calls')
