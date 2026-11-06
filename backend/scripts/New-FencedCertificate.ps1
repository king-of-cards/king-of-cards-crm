[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ArchivePath,[Parameter(Mandatory=$true)][string]$ArchiveManifestSha256,[switch]$IssueApprovedCertificate)
$ErrorActionPreference='Stop'
$backend='C:\kocbackend\king-of-cards-crm\backend'
$guard=Join-Path $backend 'crm-service\target-fenced-handover-verify\crm-fenced-guard.jar'
$guardHash='34FCCE45CA67531982D57042D8B93865EDCDBB6641B122B96D2F4043A9C19ECC'
$java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
if((Get-FileHash -LiteralPath $guard).Hash -cne $guardHash -or (Get-FileHash -LiteralPath $java).Hash -cne '82051FDAB26319D77D20CC0065045D05EC00B3E3D05F44935D7C06B96B621D55'){throw 'Guard/Java pin differs.'}
# Use preserved sources, never mutable workspace diagnostic code.
foreach($name in @('OwnJobsFenceProbe.java','CommitReceiptInvestigation.java')){if(-not (Test-Path -LiteralPath (Join-Path $ArchivePath $name))){throw 'Archived read-only probe missing.'}}
& $java -cp $guard (Join-Path $ArchivePath 'VerifyFencedStart.java') archive $ArchivePath $ArchiveManifestSha256
if($LASTEXITCODE -ne 0){throw 'UNKNOWN preservation archive invalid.'}
$launch=Join-Path $backend 'launch\CrmService-Development.launch'
if((Get-FileHash -LiteralPath $launch).Hash -cne '2E34F29FEA104AF241AEFBF5771C7C976D7FF3555DD8BB6F99BA99EC4ADD92AC'){throw 'Credential/configuration reference changed.'}
[xml]$xml=Get-Content -LiteralPath $launch -Raw
$credentials=($xml.launchConfiguration.mapAttribute.mapEntry|Where-Object key -eq 'GOOGLE_APPLICATION_CREDENTIALS').value
$stream=[IO.File]::OpenRead($credentials);$stream.Dispose()
$cpFile=Join-Path $backend 'crm-service\target\directional-local-classpath.txt'
if((Get-FileHash -LiteralPath $cpFile).Hash -ne (Get-FileHash -LiteralPath (Join-Path $ArchivePath 'runtime-classpath.txt')).Hash){throw 'Read-only runtime classpath differs.'}
$cp=(Get-Content -LiteralPath $cpFile -Raw).Trim()
$output=Join-Path $backend ('crm-service\target-fenced-handover-verify\probe-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'))
New-Item -ItemType Directory -Path $output -ErrorAction Stop|Out-Null
& (Join-Path (Split-Path $java) 'javac.exe') -proc:none -cp ($guard+';'+$cp) -d $output (Join-Path $ArchivePath 'OwnJobsFenceProbe.java') (Join-Path $ArchivePath 'CommitReceiptInvestigation.java')
if($LASTEXITCODE -ne 0){throw 'Read-only probe compilation failed.'}
$saved=[Environment]::GetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS','Process')
if($saved -and $saved -ne $credentials){throw 'Inherited credential differs.'}
$mode=if($IssueApprovedCertificate){'certify'}else{'prepare'}
$evidence=Join-Path $backend ('local-dev\crm-drain-control\fenced-'+$mode+'-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff')+'.properties')
try{
 [Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$credentials,'Process')
 & $java --add-modules jdk.attach -cp ($output+';'+$guard+';'+$cp) OwnJobsFenceProbe $mode $ArchivePath $ArchiveManifestSha256 $guardHash $evidence
 if($LASTEXITCODE -ne 0){throw 'Fenced evidence incomplete. Do not stop/start CRM.'}
}finally{[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$saved,'Process')}
Write-Output ('Evidence file: '+$evidence)
Write-Output ('Evidence SHA-256: '+(Get-FileHash -LiteralPath $evidence).Hash)
if(-not $IssueApprovedCertificate){Write-Output 'PREPARATION ONLY. This evidence cannot authorize stopping CRM.'}
