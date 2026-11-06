[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('identity','crm','gateway')][string]$Service,
    [string]$PrivateKeyLocation='C:\secure-credentials\king-of-cards-crm-dev-jwt\identity-dev-private.pem',
    [string]$PublicKeyLocation='C:\secure-credentials\king-of-cards-crm-dev-jwt\identity-dev-public.pem')
$ErrorActionPreference='Stop'
$backend=Split-Path -Parent $PSScriptRoot
$modules=@{identity='identity-service';crm='crm-service';gateway='api-gateway'}
$ports=@{identity=18081;crm=18082;gateway=18080}
$mains=@{identity='com.kingofcards.identity.IdentityServiceApplication';crm='com.kingofcards.crm.CrmServiceApplication';gateway='com.kingofcards.gateway.ApiGatewayApplication'}
if(Get-NetTCPConnection -State Listen -LocalPort $ports[$Service] -ErrorAction SilentlyContinue){throw "Port $($ports[$Service]) is occupied. No process was stopped."}
if($Service -eq 'identity' -and !(Test-Path -LiteralPath $PrivateKeyLocation -PathType Leaf)){throw 'Verified external development private key is missing. No key will be generated.'}
if($Service -eq 'gateway' -and !(Test-Path -LiteralPath $PublicKeyLocation -PathType Leaf)){throw 'Verified external development public key is missing. No key will be generated.'}
$java=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin\java.exe'}else{'java.exe'}
$version=(& $java --version | Out-String)
if($version -notmatch '(?m)^(?:openjdk|java)\s+21[.]|version "21[.]'){throw 'JAVA_HOME/PATH must select JDK 21.'}
$module=Join-Path $backend $modules[$Service]
$classpathFile=Join-Path $module 'target\directional-local-classpath.txt'
Push-Location $backend
try {
    # Offline compilation uses the MAIN source tree. Never an old local-dev runtime copy.
    & .\mvnw.cmd -o -B -pl $modules[$Service] -am '-DskipTests' compile
    if($LASTEXITCODE -ne 0){throw 'Main-source compilation failed.'}
    & .\mvnw.cmd -o -B -pl $modules[$Service] dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=runtime'
    if($LASTEXITCODE -ne 0){throw 'Offline dependency classpath could not be built. Do not start a stale artifact.'}
} finally {Pop-Location}
$classpath=(Join-Path $module 'target\classes')+';'+[IO.File]::ReadAllText($classpathFile).Trim()
$saved=@{}
$variables=@{SPRING_PROFILES_ACTIVE='lead-dashboard-local';JWT_PRIVATE_KEY_LOCATION=$PrivateKeyLocation;JWT_PUBLIC_KEY_LOCATION=$PublicKeyLocation}
if($Service -eq 'identity'){
    # Hidden, local-only input; never command-line arguments, logs or persisted configuration.
    foreach($kind in @('ADMIN','VENDOR')){
        $secure=Read-Host "Choose LOCAL ONLY $kind password (12+ characters, at most 72 UTF-8 bytes)" -AsSecureString
        $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try{$plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer);if($plain.Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($plain) -gt 72){throw 'Invalid local password length.'};$variables["KOC_LOCAL_${kind}_PASSWORD"]=$plain}
        finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$plain=$null;$secure.Dispose()}
    }
}
try {
    foreach($entry in $variables.GetEnumerator()){$saved[$entry.Key]=[Environment]::GetEnvironmentVariable($entry.Key,'Process');[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
    Write-Host "Starting MAIN-source $Service on loopback port $($ports[$Service]); no Eureka or Google Cloud access. Ctrl+C stops only this foreground instance."
    & $java '-cp' $classpath $mains[$Service] '--spring.profiles.active=lead-dashboard-local'
} finally {
    foreach($entry in $saved.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
    $variables.Clear()
}
