$ErrorActionPreference = "Stop"
$keyDirectory = Join-Path $env:LOCALAPPDATA "KingOfCardsCRM\keys"
$privatePath = Join-Path $keyDirectory "jwt-private.pem"
$publicPath = Join-Path $keyDirectory "jwt-public.pem"

if ((Test-Path -LiteralPath $privatePath) -or (Test-Path -LiteralPath $publicPath)) {
    throw "A development key already exists. Refusing to overwrite it."
}

New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
$directoryAcl = Get-Acl -LiteralPath $keyDirectory
$directoryAcl.SetAccessRuleProtection($true, $false)
$currentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$directoryRule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $currentIdentity, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")
$directoryAcl.SetAccessRule($directoryRule)
Set-Acl -LiteralPath $keyDirectory -AclObject $directoryAcl

$rsa = [System.Security.Cryptography.RSA]::Create(3072)
try {
    $newline = [Environment]::NewLine
    $privateBody = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey(), [Base64FormattingOptions]::InsertLineBreaks)
    $publicBody = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo(), [Base64FormattingOptions]::InsertLineBreaks)
    $privatePem = "-----BEGIN PRIVATE KEY-----" + $newline + $privateBody + $newline + "-----END PRIVATE KEY-----" + $newline
    $publicPem = "-----BEGIN PUBLIC KEY-----" + $newline + $publicBody + $newline + "-----END PUBLIC KEY-----" + $newline
    $privateStream = [IO.File]::Open($privatePath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $writer = [IO.StreamWriter]::new($privateStream, [Text.UTF8Encoding]::new($false))
        $writer.Write($privatePem)
        $writer.Dispose()
    }
    finally { $privateStream.Dispose() }
    $publicStream = [IO.File]::Open($publicPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $writer = [IO.StreamWriter]::new($publicStream, [Text.UTF8Encoding]::new($false))
        $writer.Write($publicPem)
        $writer.Dispose()
    }
    finally { $publicStream.Dispose() }

    $privateAcl = [System.Security.AccessControl.FileSecurity]::new()
    $privateAcl.SetAccessRuleProtection($true, $false)
    $privateAcl.SetAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
        $currentIdentity, "FullControl", "Allow"))
    Set-Acl -LiteralPath $privatePath -AclObject $privateAcl
    $publicAcl = [System.Security.AccessControl.FileSecurity]::new()
    $publicAcl.SetAccessRuleProtection($true, $false)
    $publicAcl.SetAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
        $currentIdentity, "FullControl", "Allow"))
    Set-Acl -LiteralPath $publicPath -AclObject $publicAcl
}
finally {
    $rsa.Dispose()
}
Write-Output "Development JWT keypair created under $keyDirectory; key contents were not displayed."