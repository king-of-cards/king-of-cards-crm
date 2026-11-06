# Preparation defaults to validation only. No process is ever stopped by this script.
[CmdletBinding()]
param(
    [ValidateSet('Updated','Rollback')][string]$Artifact='Updated',
    [switch]$ExecuteApprovedStart,
    [string]$ApprovedArtifactHash,
    [int]$ExitedCrmPid
)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if($backend -ne 'C:\kocbackend\king-of-cards-crm\backend'){throw 'Unexpected backend workspace.'}
$pins=@{
    Updated=@('target-source-precision-verify','405926DC620E64C72A56170526D365783A380CC33B963DE4581D6F2ABE8E8EB5')
    Rollback=@('target-timestamp-safe-verify','A7CB8433FF70F0D218819FB03D817FE8F9AC24E502050DB3B73D8E84A9281F97')
}
$jar=Join-Path $backend ('crm-service\'+$pins[$Artifact][0]+'\crm-service-0.1.0-SNAPSHOT.jar')
$appHash=$pins[$Artifact][1]
$helper=Join-Path $backend 'crm-service\target-drain-control-verify\v2\crm-drain-control.jar'
$helperHash='C7576BB7E685E1F8C7265F46BDE398D5D747EB1B53B7D960785B9CD0DD0D82D9'
$java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
foreach($pin in @(@($jar,$appHash),@($helper,$helperHash),@($java,'82051FDAB26319D77D20CC0065045D05EC00B3E3D05F44935D7C06B96B621D55'))){
    if((Get-FileHash -LiteralPath $pin[0] -Algorithm SHA256).Hash -cne $pin[1]){throw 'Artifact/runtime pin differs; no startup allowed.'}
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try{
    foreach($entry in @('org/springframework/boot/loader/launch/PropertiesLauncher.class','BOOT-INF/classes/com/kingofcards/crm/CrmServiceApplication.class')){
        if(-not $zip.GetEntry($entry)){throw 'Expected existing Boot application/launcher missing.'}
    }
    $reader=[IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
    try{$manifest=$reader.ReadToEnd()}finally{$reader.Dispose()}
    if($manifest -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' -or $manifest -notmatch '(?m)^Start-Class: com\.kingofcards\.crm\.CrmServiceApplication\r?$'){throw 'Unexpected executable manifest.'}
}finally{$zip.Dispose()}
$configPins=@{
    'CrmService-Development.launch'='2E34F29FEA104AF241AEFBF5771C7C976D7FF3555DD8BB6F99BA99EC4ADD92AC'
    'IdentityService-Development.launch'='DA4D3B3443A493F47EA4AF643939ECA72E1AAD00C5F7E166D06375FA6FFD508F'
    'ApiGateway-Development.launch'='64577DCE5561BCB5A83C142226695F9C4A910348A581350F3A40ADDFA3EF54D3'
}
$configs=@{}
foreach($name in $configPins.Keys){
    $path=Join-Path $backend ('launch\'+$name)
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $configPins[$name]){throw 'Saved configuration pin differs.'}
    [xml]$xml=Get-Content -LiteralPath $path -Raw
    $values=@{};foreach($entry in $xml.launchConfiguration.mapAttribute.mapEntry){$values[$entry.key]=$entry.value}
    $configs[$name]=$values
}
$crm=$configs['CrmService-Development.launch']
foreach($key in @('JWT_ISSUER','JWT_AUDIENCE')){
    if(-not $crm[$key] -or $crm[$key] -ne $configs['IdentityService-Development.launch'][$key] -or $crm[$key] -ne $configs['ApiGateway-Development.launch'][$key]){throw 'JWT configuration mismatch.'}
}
foreach($pair in @(@('BIGQUERY_PROJECT_ID','king-of-cards-509610'),@('BIGQUERY_DATASET','shared_rds_data'),@('BIGQUERY_LOCATION','asia-south1'),@('JWT_JWKS_URI','http://localhost:8081/auth/jwks'),@('EUREKA_DEFAULT_ZONE','http://localhost:8761/eureka/'))){
    if($crm[$pair[0]] -ne $pair[1]){throw 'Project/security/discovery configuration mismatch.'}
}
if(-not $crm['GOOGLE_APPLICATION_CREDENTIALS']){throw 'Credential reference missing.'}
$credentialStream=[IO.File]::OpenRead($crm['GOOGLE_APPLICATION_CREDENTIALS']);$credentialStream.Dispose()
if($env:GOOGLE_APPLICATION_CREDENTIALS -and $env:GOOGLE_APPLICATION_CREDENTIALS -ne $crm['GOOGLE_APPLICATION_CREDENTIALS']){throw 'Inherited credentials differ.'}
foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','SPRING_PROFILES_INCLUDE','LOADER_PATH','LOADER_MAIN','CLASSPATH')){
    if([Environment]::GetEnvironmentVariable($name,'Process')){throw "Unreviewed inherited setting: $name (value withheld)."}
}
$helperUri=([Uri]$helper).AbsoluteUri
$arguments=@(('-Dloader.path='+$helperUri),'-Dloader.main=com.kingofcards.crm.ops.runtime.GatedCrmMain',
    ('-Dkoc.ops.helper-sha256='+$helperHash),('-Dkoc.ops.application-sha256='+$appHash),'-Dkoc.ops.admission=READS_ONLY',
    '-cp',('"'+$jar+'"'),'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--spring.profiles.active=dev','--server.port=8082','--crm.bigquery.enabled=true','--crm.directional.enabled=true',
    '--crm.bigquery.writes-enabled=true','--crm.directional.writes-enabled=true',
    '--crm.bigquery.schema-auto-create=false','--crm.bigquery.schema-init-enabled=false')
if(-not $ExecuteApprovedStart){
    Write-Output ('VALIDATION PASS: '+$Artifact+' '+$jar)
    Write-Output ('Application SHA-256: '+$appHash)
    Write-Output ('Admission helper SHA-256: '+$helperHash)
    Write-Output 'Saved credentials accessible; JWT/discovery pins match; dev/reads/writes enabled; schema creation/init disabled.'
    Write-Output 'Pinned mandatory bootstrap keeps writes blocked before the connector starts. No process or environment changed.'
    return
}
if($ApprovedArtifactHash -cne $appHash -or $ExitedCrmPid -lt 1){throw 'Separate exact-artifact start approval and verified exited CRM PID required.'}
if(Get-CimInstance Win32_Process -Filter "ProcessId=$ExitedCrmPid"){throw 'Previous/failed CRM process has not exited.'}
$processes=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'")
if(@($processes|Where-Object {-not $_.CommandLine}).Count -gt 0){throw 'Cannot establish Java process identity.'}
if(@($processes|Where-Object {$_.CommandLine -match 'king-of-cards-crm[\\/]backend[\\/]crm-service[\\/].*crm-service-0\.1\.0-SNAPSHOT\.jar'}).Count -gt 0){throw 'Another CRM process is present.'}
if(@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq 8082).Count -gt 0){throw 'Port 8082 is occupied.'}
$control=Join-Path $backend 'local-dev\crm-drain-control'
$proof=@{};Get-Content -LiteralPath (Join-Path $control 'drain-evidence.properties')|ForEach-Object {if($_ -match '^([^#!][^=]*)=(.*)$'){$proof[$matches[1]]=$matches[2]}}
if($proof.ready -ne 'true' -or $proof.activeCrmWriteJobs -ne '0' -or $proof.unresolvedKnownOperations -ne '0' -or $proof.agentSha256 -cne $helperHash){throw 'Verified drain/commit evidence required.'}
if($Artifact -eq 'Updated'){
    if($proof.pid -ne [string]$ExitedCrmPid -or $proof.applicationSha256 -cne $pins.Rollback[1] -or [DateTimeOffset]::Parse($proof.timestamp.Replace('\:',':')) -lt [DateTimeOffset]::UtcNow.AddMinutes(-2)){throw 'Fresh original-process drain evidence required.'}
}else{
    # A failed new start must have used this same mandatory gate. No ungated rollback path.
    $start=Get-Content -LiteralPath (Join-Path $control 'gated-Updated-start.json') -Raw|ConvertFrom-Json
    if($start.pid -ne $ExitedCrmPid -or $start.appHash -cne $pins.Updated[1] -or $start.helperHash -cne $helperHash){throw 'Failed gated new-process identity cannot be verified.'}
}
$marker=Join-Path $control ('gated-'+$Artifact+'-attempt.json')
$stream=[IO.File]::Open($marker,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::UTF8.GetBytes((@{timestamp=[DateTime]::UtcNow.ToString('o');appHash=$appHash;helperHash=$helperHash;exitedPid=$ExitedCrmPid}|ConvertTo-Json -Compress));$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
$logs=Join-Path $backend ('local-dev\logs\crm-gated-'+$Artifact+'-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff'))
New-Item -ItemType Directory -Path $logs -ErrorAction Stop|Out-Null
$saved=@{}
try{
    foreach($key in $crm.Keys){$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$crm[$key],'Process')}
    $running=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'stdout.log') -RedirectStandardError (Join-Path $logs 'stderr.log')
}finally{foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}}
$receipt=@{pid=$running.Id;appHash=$appHash;helperHash=$helperHash;logs=$logs;timestamp=[DateTime]::UtcNow.ToString('o')}|ConvertTo-Json
$receiptPath=Join-Path $control ('gated-'+$Artifact+'-start.json')
$stream=[IO.File]::Open($receiptPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
try{$bytes=[Text.Encoding]::UTF8.GetBytes($receipt);$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
Write-Output ('ONE gated startup requested: PID '+$running.Id+'; logs '+$logs)
Write-Output 'Verify health/readiness, Eureka, Gateway, metadata and write barrier. On failure report; do not retry or stop automatically.'
