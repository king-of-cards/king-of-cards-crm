[CmdletBinding()]
param([switch]$ValidateOnly, [switch]$SelfTest)
$ErrorActionPreference='Stop'
$backend=Split-Path -Parent $PSScriptRoot
$module=Join-Path $backend 'api-gateway'
$output=Join-Path $module 'target-routing-verify-20261106'

function Assert-GatewayOutputSafety([string]$OutputPath,[string[]]$ActiveArtifacts,[bool]$AlreadyExists) {
    $candidate=[IO.Path]::GetFullPath($OutputPath).TrimEnd('\')
    if($AlreadyExists){throw 'Output already exists; preserve it and choose a separately reviewed new directory.'}
    foreach($artifact in $ActiveArtifacts){
        $active=[IO.Path]::GetFullPath($artifact)
        if($active.StartsWith($candidate+'\',[StringComparison]::OrdinalIgnoreCase)){
            throw 'Build output contains a running artifact. Never replace a JAR used by a live JVM.'
        }
    }
}
if($SelfTest){
    $count=0
    foreach($case in @(
        @{Path='C:\project\target';Active=@('C:\project\target\gateway.jar');Exists=$false;Reject=$true},
        @{Path='C:\project\TARGET';Active=@('C:\project\target\gateway.jar');Exists=$false;Reject=$true},
        @{Path='C:\project\x\..\target';Active=@('C:\project\target\gateway.jar');Exists=$false;Reject=$true},
        @{Path='C:\project\new';Active=@('C:\project\target\gateway.jar');Exists=$true;Reject=$true},
        @{Path='C:\project\new';Active=@('C:\project\target\gateway.jar');Exists=$false;Reject=$false},
        @{Path='C:\project\target';Active=@('C:\project\target-other\gateway.jar');Exists=$false;Reject=$false}
    )){
        $rejected=$false
        try{Assert-GatewayOutputSafety $case.Path $case.Active $case.Exists}catch{$rejected=$true}
        if($rejected -ne $case.Reject){throw 'Build safety regression failed'}
        $count++
    }
    Write-Output "BUILD_SAFETY_TESTS=$count PASS";return
}
# Do not print command lines: launch arguments may contain credential references.
$active=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object Name -match '^javaw?\.exe$' | ForEach-Object {
    $match=[regex]::Match($_.CommandLine,'(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))')
    if($match.Success){if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value}}
})
Assert-GatewayOutputSafety $output $active (Test-Path -LiteralPath $output)
if($ValidateOnly){Write-Output "BUILD_PREFLIGHT=PASS isolated output $output; no build or service action performed";return}
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
$log=Join-Path $output 'build.log'
Push-Location $backend
try{
    $savedPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
    try{& .\mvnw.cmd -o -pl api-gateway '-Dkoc.build.directory=target-routing-verify-20261106' verify *> $log;$buildExit=$LASTEXITCODE}
    finally{$ErrorActionPreference=$savedPreference}
    if($buildExit -ne 0){throw "Gateway verification failed. Preserve and inspect $log"}
}finally{Pop-Location}
$jar=Join-Path $output 'api-gateway-0.1.0-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $jar)){throw 'Executable JAR was not produced'}
Write-Output "BUILD=PASS $jar"
Get-FileHash -LiteralPath $jar -Algorithm SHA256 | Select-Object Path,Hash
