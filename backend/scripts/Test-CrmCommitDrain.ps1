# Read-only runtime status, own-job metadata, original receipts and integrity checks.
# Never stops/starts CRM, retries an operation, or submits database DML/DDL.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][int]$CrmProcessId,
      [Parameter(Mandatory=$true)][ValidateSet('A7CB8433FF70F0D218819FB03D817FE8F9AC24E502050DB3B73D8E84A9281F97','405926DC620E64C72A56170526D365783A380CC33B963DE4581D6F2ABE8E8EB5')][string]$ApplicationSha256)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if($backend -ne 'C:\kocbackend\king-of-cards-crm\backend'){throw 'Unexpected existing workspace.'}
$java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
if((Get-FileHash -LiteralPath $java -Algorithm SHA256).Hash -cne '82051FDAB26319D77D20CC0065045D05EC00B3E3D05F44935D7C06B96B621D55'){throw 'Java pin differs.'}
$helper=Join-Path $backend 'crm-service\target-drain-control-verify\v2\crm-drain-control.jar'
$helperHash='C7576BB7E685E1F8C7265F46BDE398D5D747EB1B53B7D960785B9CD0DD0D82D9'
& $java --add-modules jdk.attach (Join-Path $PSScriptRoot 'crm-drain-control\CrmDrainControl.java') $CrmProcessId status $helper $helperHash $ApplicationSha256
if($LASTEXITCODE -ne 0){throw 'Actual process admission status unavailable.'}
$launch=Join-Path $backend 'launch\CrmService-Development.launch'
if((Get-FileHash -LiteralPath $launch -Algorithm SHA256).Hash -cne '2E34F29FEA104AF241AEFBF5771C7C976D7FF3555DD8BB6F99BA99EC4ADD92AC'){throw 'Configuration pin differs.'}
[xml]$xml=Get-Content -LiteralPath $launch -Raw
$credentials=($xml.launchConfiguration.mapAttribute.mapEntry|Where-Object key -eq 'GOOGLE_APPLICATION_CREDENTIALS').value
$stream=[IO.File]::OpenRead($credentials);$stream.Dispose()
$cp=(Get-Content -LiteralPath (Join-Path $backend 'crm-service\target\directional-local-classpath.txt') -Raw).Trim()
$saved=[Environment]::GetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS','Process')
if($saved -and $saved -ne $credentials){throw 'Inherited credential reference differs.'}
try{
    [Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$credentials,'Process')
    & $java -cp $cp (Join-Path $PSScriptRoot 'crm-drain-control\ReadOnlyCommitDrain.java') (Join-Path $backend 'local-dev\crm-drain-control')
    if($LASTEXITCODE -ne 0){throw 'Commit verification incomplete. Leave CRM running; no stop/start is permitted.'}
    $evidence=Join-Path $backend 'local-dev\crm-drain-control\drain-evidence.properties'
    if(-not (Select-String -LiteralPath $evidence -Pattern '^ready=true$' -Quiet)){throw 'Original outcomes remain unresolved. No stop/start is permitted.'}
}finally{[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$saved,'Process')}
