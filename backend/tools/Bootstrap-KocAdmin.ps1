$ErrorActionPreference = "Stop"
$backendRoot = Split-Path -Parent $PSScriptRoot
$keyPath = Join-Path $env:LOCALAPPDATA "KingOfCardsCRM\keys\jwt-private.pem"
$javaHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $env:JAVA_HOME
} else {
    "C:\Program Files\Java\jdk-21"
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe"))) {
    throw "Java 21 was not found. Set JAVA_HOME to the Java 21 JDK."
}
if (-not (Test-Path -LiteralPath $keyPath)) {
    throw "Development JWT private key is missing. Run Generate-KocJwtDevKey.ps1 first."
}

$names = @(
    "IDENTITY_DB_PASSWORD", "IDENTITY_DB_USERNAME", "POSTGRES_HOST", "POSTGRES_PORT",
    "JWT_ISSUER", "JWT_AUDIENCE", "JWT_PRIVATE_KEY_LOCATION", "IDENTITY_REFRESH_TOKEN_TTL",
    "IDENTITY_BOOTSTRAP_EMAIL", "IDENTITY_BOOTSTRAP_PASSWORD", "SPRING_PROFILES_ACTIVE", "JAVA_HOME", "MAVEN_USER_HOME", "MAVEN_OPTS"
)
$oldValues = @{}
foreach ($name in $names) { $oldValues[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }

$dbBstr = [IntPtr]::Zero
$adminBstr = [IntPtr]::Zero
$dbPassword = $null
$adminPassword = $null
try {
    $email = Read-Host "First administrator email"
    $dbSecure = Read-Host "Existing Identity DEV database password" -AsSecureString
    $adminSecure = Read-Host "New CRM_ADMIN password" -AsSecureString
    $dbBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($dbSecure)
    $adminBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($adminSecure)
    $dbPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($dbBstr)
    $adminPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($adminBstr)

    $env:JAVA_HOME = $javaHome
    $env:IDENTITY_DB_USERNAME = "koc_identity_dev_app"
    $env:IDENTITY_DB_PASSWORD = $dbPassword
    $env:POSTGRES_HOST = "localhost"
    $env:POSTGRES_PORT = "5432"
    $env:JWT_ISSUER = "http://localhost:8081"
    $env:JWT_AUDIENCE = "koc-crm-api"
    $env:JWT_PRIVATE_KEY_LOCATION = $keyPath
    $env:IDENTITY_REFRESH_TOKEN_TTL = "7d"
    $env:IDENTITY_BOOTSTRAP_EMAIL = $email
    $env:IDENTITY_BOOTSTRAP_PASSWORD = $adminPassword
    $env:SPRING_PROFILES_ACTIVE = "dev"
    $env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE ".m2"
    $repoPath = Join-Path $env:USERPROFILE ".m2\repository"
    $extraMavenOptions = "-Duser.home=$env:USERPROFILE -Dmaven.repo.local=$repoPath"
    $env:MAVEN_OPTS = (($oldValues["MAVEN_OPTS"] + " " + $extraMavenOptions).Trim())

    Push-Location $backendRoot
    try {
        $mavenArgs = @(
            "-pl", "identity-service",
            "spring-boot:run",
            "-Dspring-boot.run.main-class=com.kingofcards.identity.bootstrap.BootstrapAdminApplication"
        )
        & .\mvnw.cmd @mavenArgs
        if ($LASTEXITCODE -ne 0) { throw "Administrator bootstrap failed; review the non-secret error output." }
    }
    finally { Pop-Location }
}
finally {
    if ($dbBstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbBstr) }
    if ($adminBstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($adminBstr) }
    $dbPassword = $null
    $adminPassword = $null
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $oldValues[$name], "Process")
    }
}