[CmdletBinding()]
param(
    [ValidateSet('New','Rollback')][string]$Artifact='New',
    [switch]$Start,
    [switch]$SelfTest,
    [string]$ExecutionApproval
)
$ErrorActionPreference='Stop'
function Assert-GatewayLaunchAllowed([string]$Selected,[string]$Approval,[bool]$Occupied,[bool]$LiveGateway){
    $required=if($Selected -eq 'Rollback'){'APPROVE_ONE_GATEWAY_ROLLBACK'}else{'APPROVE_ONE_GATEWAY_START'}
    if($Approval -cne $required){throw 'Separate explicit approval required'}
    if($Occupied){throw 'Port 8080 occupied; leave the running JVM unchanged'}
    if($LiveGateway){throw 'A Gateway JVM is still alive; inspect it without automatic termination'}
}
if($SelfTest){
    $cases=@(
        @('New','',$false,$false,$true),
        @('New','APPROVE_ONE_GATEWAY_ROLLBACK',$false,$false,$true),
        @('Rollback','APPROVE_ONE_GATEWAY_START',$false,$false,$true),
        @('New','APPROVE_ONE_GATEWAY_START',$true,$false,$true),
        @('Rollback','APPROVE_ONE_GATEWAY_ROLLBACK',$false,$true,$true),
        @('New','APPROVE_ONE_GATEWAY_START',$false,$false,$false),
        @('Rollback','APPROVE_ONE_GATEWAY_ROLLBACK',$false,$false,$false)
    )
    foreach($case in $cases){
        $rejected=$false
        try{Assert-GatewayLaunchAllowed $case[0] $case[1] $case[2] $case[3]}catch{$rejected=$true}
        if($rejected -ne $case[4]){throw 'Gateway launch guard regression failed'}
    }
    Write-Output "LAUNCH_GUARD_TESTS=$($cases.Count) PASS; no files, processes or network changed";return
}
$backend=Split-Path -Parent $PSScriptRoot
$pins=@{
    New=@{Directory='target-routing-verify-20261106';Hash='5FE9E5ABF949EDA9D00685F8C81DE4D29D0C916A0ED444DA7FB6A446972AD123'}
    Rollback=@{Directory='target-directional-verify';Hash='BA2EDB788DDC2A28A7F84C724ACDB41B19C9D0A5425B50BCF0A3596EF8DEE5E5'}
}
$selected=$pins[$Artifact]
$jar=Join-Path $backend ('api-gateway\'+$selected.Directory+'\api-gateway-0.1.0-SNAPSHOT.jar')
$launchPath=Join-Path $backend 'launch\ApiGateway-Development.launch'
if((Get-FileHash -LiteralPath $jar).Hash -ne $selected.Hash){throw 'Gateway artifact hash mismatch'}
if((Get-FileHash -LiteralPath $launchPath).Hash -ne '64577DCE5561BCB5A83C142226695F9C4A910348A581350F3A40ADDFA3EF54D3'){
    throw 'Reviewed Gateway launch configuration changed'
}
[xml]$launch=Get-Content -Raw -LiteralPath $launchPath
if(@($launch.launchConfiguration.stringAttribute | Where-Object key -eq 'org.eclipse.jdt.launching.MAIN_TYPE')[0].value -ne 'com.kingofcards.gateway.ApiGatewayApplication'){
    throw 'Unexpected Gateway main class'
}
foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION','LOADER_PATH','LOADER_MAIN')){
    if([Environment]::GetEnvironmentVariable($name,'Process')){throw "Unreviewed inherited configuration: $name"}
}
$saved=@{}
try{
    foreach($entry in $launch.launchConfiguration.mapAttribute.mapEntry){
        $saved[$entry.key]=[Environment]::GetEnvironmentVariable($entry.key,'Process')
        [Environment]::SetEnvironmentVariable($entry.key,$entry.value,'Process')
    }
    if(!(Test-Path -LiteralPath $env:JWT_PUBLIC_KEY_LOCATION -PathType Leaf)){throw 'Reviewed JWT public key is unavailable'}
    if(!$env:JWT_ISSUER -or !$env:JWT_AUDIENCE){throw 'JWT issuer/audience missing'}
    # This launcher never stops any process. Approval for start is not approval for termination.
    if(!$Start){Write-Output "PINNED_PREFLIGHT=PASS artifact=$Artifact jar=$jar hash=$($selected.Hash); no service action";return}
    $occupied=@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).Count -gt 0
    $liveGateway=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'api-gateway.*\.jar|com\.kingofcards\.gateway\.ApiGatewayApplication'
    }).Count -gt 0
    Assert-GatewayLaunchAllowed $Artifact $ExecutionApproval $occupied $liveGateway
    $control=Join-Path $backend 'local-dev\gateway-routing-recovery-20261106'
    New-Item -ItemType Directory -Path $control -Force | Out-Null
    $attempt=Join-Path $control ($Artifact.ToLowerInvariant()+'-start-attempt.txt')
    if($Artifact -eq 'Rollback' -and !(Test-Path -LiteralPath (Join-Path $control 'new-start-attempt.txt'))){
        throw 'No recorded new-artifact attempt; rollback is not applicable'
    }
    # CREATE_NEW is deliberately never reset, including after failed startup.
    $marker=[IO.File]::Open($attempt,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$bytes=[Text.Encoding]::UTF8.GetBytes("utc=$([DateTime]::UtcNow.ToString('O'))`nhash=$($selected.Hash)`n");$marker.Write($bytes,0,$bytes.Length)}finally{$marker.Dispose()}
    $java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
    $gatewayArguments=@('-jar',('"'+$jar+'"'),'--spring.profiles.active=dev','--server.port=8080')
    $process=Start-Process -FilePath $java -ArgumentList $gatewayArguments -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $control ($Artifact.ToLowerInvariant()+'-stdout.log')) `
        -RedirectStandardError (Join-Path $control ($Artifact.ToLowerInvariant()+'-stderr.log'))
    Write-Output "Started Gateway PID=$($process.Id); jar=$jar; SHA256=$($selected.Hash). Verify read-only routing; never auto-retry or auto-rollback."
}finally{
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
