[CmdletBinding()]
param([switch]$ArtifactsOnly)
$ErrorActionPreference='Stop'
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if($backend -cne 'C:\kocbackend\king-of-cards-crm\backend'){throw 'Unexpected workspace.'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$artifacts=@(
    @('crm-service','target-final-receipt-integration-verify-20261106','FCEFD7489FC1526D38BF77F1EF2C5C003669DEF1CF71E0B77200E2DA74557F47','com.kingofcards.crm.CrmServiceApplication'),
    @('crm-service','target-final-parser-integration-verify-20261106','5644AACA12CF275C9BB92E63C2BA247BF3537A29165F8A42B6057B69A8AA7585','com.kingofcards.crm.CrmServiceApplication'),
    @('crm-service','target-final-integration-verify-20261105','EA052C426E4F1B3712802C9B0D380A3B880C01F07F811259B49BD6855E2532DA','com.kingofcards.crm.CrmServiceApplication'),
    @('identity-service','target-final-integration-verify-20261106','8CC080E57C602C4D24CFABFACC35B7788B6D37D8B60AE2FA5038CB73777910B5','com.kingofcards.identity.IdentityServiceApplication')
)
foreach($artifact in $artifacts){
    $output=Join-Path $backend ($artifact[0]+'\'+$artifact[1]);$jar=Join-Path $output ($artifact[0]+'-0.1.0-SNAPSHOT.jar')
    if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -cne $artifact[2]){throw 'New artifact pin differs.'}
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try{
        $reader=[IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open());try{$manifest=$reader.ReadToEnd()}finally{$reader.Dispose()}
        if($manifest -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' -or $manifest -notmatch [regex]::Escape('Start-Class: '+$artifact[3]) -or -not $zip.GetEntry('org/springframework/boot/loader/launch/PropertiesLauncher.class')){throw 'Executable Boot manifest/launchers missing.'}
        $count=0
        foreach($entry in $zip.Entries){
            if($entry.FullName -notlike 'BOOT-INF/classes/*.class'){continue}
            $relative=$entry.FullName.Substring('BOOT-INF/classes/'.Length);$compiled=Join-Path $output ('classes\'+$relative)
            if(-not (Test-Path -LiteralPath $compiled -PathType Leaf)){throw 'Packaged class has no verified compiled counterpart.'}
            $digest=[Security.Cryptography.SHA256]::Create();$stream=$entry.Open()
            try{$actual=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','')}finally{$stream.Dispose();$digest.Dispose()}
            if($actual -cne (Get-FileHash -LiteralPath $compiled -Algorithm SHA256).Hash){throw 'Packaged class differs from compiled class.'};$count++
        }
        if($count -lt 30){throw 'Unexpectedly incomplete executable artifact.'}
        if($artifact[0] -eq 'identity-service' -and -not $zip.GetEntry('BOOT-INF/classes/com/kingofcards/identity/controller/VendorSelfProfileController.class')){throw 'Vendor self-profile endpoint missing.'}
        if($artifact[0] -eq 'crm-service'){
            foreach($name in @('DirectionalService','BigQueryDirectionalRepository','DirectionalController','SheetFiles','SourceDateTime','DirectionalModels$ImportSelection','DirectionalModels$DashboardReport')){if(-not $zip.GetEntry('BOOT-INF/classes/com/kingofcards/crm/directional/'+$name+'.class')){throw 'Required CRM class missing.'}}
        }
        Write-Output ('ARTIFACT_PASS module='+$artifact[0]+' compiledClassesMatched='+$count+' sha256='+$artifact[2])
    }finally{$zip.Dispose()}
}
if($ArtifactsOnly){Write-Output 'ARTIFACTS_ONLY_PASS; no process, network, database or startup action performed.';return}
$crmProcess=Get-CimInstance Win32_Process -Filter 'ProcessId=50528'
$activeJar=Join-Path $backend 'crm-service\target-source-precision-verify\crm-service-0.1.0-SNAPSHOT.jar'
$activeHash='405926DC620E64C72A56170526D365783A380CC33B963DE4581D6F2ABE8E8EB5'
if(-not $crmProcess -or $crmProcess.Name -ne 'java.exe' -or -not $crmProcess.CommandLine.Contains($activeJar) -or -not $crmProcess.CommandLine.Contains('-Dkoc.ops.admission=READS_ONLY')){throw 'Expected READS_ONLY CRM identity cannot be verified.'}
if((Get-FileHash -LiteralPath $activeJar).Hash -cne $activeHash){throw 'Active JAR changed.'}
foreach($flag in @('--spring.profiles.active=dev','--crm.bigquery.enabled=true','--crm.directional.enabled=true','--crm.bigquery.writes-enabled=true','--crm.directional.writes-enabled=true','--crm.bigquery.schema-auto-create=false','--crm.bigquery.schema-init-enabled=false')){if(-not $crmProcess.CommandLine.Contains($flag)){throw 'Current development/read/schema flag cannot be verified.'}}
if(@(Get-NetTCPConnection -State Listen -LocalPort 8082 | Where-Object OwningProcess -eq 50528).Count -lt 1){throw 'CRM does not own port 8082.'}
Write-Output ('CURRENT_CRM_PASS pid=50528 port=8082 mode=READS_ONLY sha256='+$activeHash)
foreach($url in @('http://localhost:8082/actuator/health','http://localhost:8082/actuator/health/readiness','http://localhost:8080/api/crm/health','http://localhost:8080/actuator/health','http://localhost:8081/actuator/health','http://localhost:8761/actuator/health','http://localhost:5173/')){
    $status=& curl.exe --silent --noproxy '*' --connect-timeout 3 --max-time 10 --output NUL --write-out '%{http_code}' $url
    if($LASTEXITCODE -ne 0 -or "$status" -ne '200'){throw ('Read-only health failed: '+$url)}
    Write-Output ('HEALTH_PASS '+$url+' HTTP=200')
}
$discovery=& curl.exe --silent --fail --noproxy '*' --max-time 10 --header 'Accept: application/json' 'http://localhost:8761/eureka/apps/CRM-SERVICE'
if($LASTEXITCODE -ne 0){throw 'CRM discovery lookup failed.'}
$instances=@(($discovery | ConvertFrom-Json).application.instance)
if(@($instances | Where-Object {$_.status -eq 'UP' -and $_.port.'$' -eq 8082}).Count -ne 1){throw 'Expected one UP CRM registration on 8082.'}
Write-Output 'EUREKA_PASS CRM-SERVICE=UP port=8082'
# Only the existing verifier's dataset/table GET and SELECT implementation runs.
# Its broad historical-operation search entry point is never invoked.
$control=Join-Path $backend 'local-dev\crm-drain-control'
$archive=Join-Path $control 'fenced-handover-20261105'
$bundle=Join-Path $control 'post-shutdown-recovery-20261105-113219'
$cp=(Join-Path $bundle 'post-shutdown-guard.jar')+';'+(Join-Path $backend 'crm-service\target-fenced-handover-verify\crm-fenced-guard.jar')+';'+(Get-Content -LiteralPath (Join-Path $backend 'crm-service\target\directional-local-classpath.txt') -Raw).Trim()
[xml]$launch=Get-Content -LiteralPath (Join-Path $backend 'launch\CrmService-Development.launch') -Raw
$credentialRef=@($launch.launchConfiguration.mapAttribute.mapEntry | Where-Object key -eq 'GOOGLE_APPLICATION_CREDENTIALS')[0].value
$credentialStream=[IO.File]::OpenRead($credentialRef);$credentialStream.Dispose()
$prior=[Environment]::GetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS','Process')
try{
    [Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$credentialRef,'Process')
    & 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe' -cp $cp RollbackCompatibility $archive '9BBE58931F5D9769DAEAF6DB7F856E92B5A3BE96AF781C3A7C55748392354E87'
    if($LASTEXITCODE -ne 0){throw 'Read-only schema/data compatibility check failed.'}
}finally{[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$prior,'Process')}
Write-Output 'READ_ONLY_VERIFICATION_PASS; no startup, admission change, historical replay, DML or DDL performed.'
