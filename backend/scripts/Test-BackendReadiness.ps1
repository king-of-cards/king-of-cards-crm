[CmdletBinding()]
param(
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$IdentityUrl = 'http://localhost:8081',
    [string]$CrmUrl = 'http://localhost:8082',
    [string]$EurekaUrl = 'http://localhost:8761'
)

# Local checks only. POST probes have missing/invalid input and cannot reach persistence.
# Never sends real credentials, cookies or JWTs; never logs bodies or token headers.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
foreach ($endpoint in @($GatewayUrl, $IdentityUrl, $CrmUrl, $EurekaUrl)) {
    $parsed = [uri]$endpoint
    if (-not $parsed.IsLoopback -or $parsed.Scheme -ne 'http') {
        throw 'This readiness script only permits local HTTP services.'
    }
}
$checks = @(
    @{ Name = 'Eureka health'; Url = "$EurekaUrl/actuator/health"; Expected = 200 },
    @{ Name = 'Gateway health'; Url = "$GatewayUrl/actuator/health"; Expected = 200 },
    @{ Name = 'Identity actuator'; Url = "$IdentityUrl/actuator/health"; Expected = 200 },
    @{ Name = 'CRM actuator'; Url = "$CrmUrl/actuator/health"; Expected = 200 },
    @{ Name = 'Identity via Gateway'; Url = "$GatewayUrl/api/identity/health"; Expected = 200 },
    @{ Name = 'CRM via Gateway'; Url = "$GatewayUrl/api/crm/health"; Expected = 200 },
    @{ Name = 'Identity direct missing JWT'; Url = "$IdentityUrl/auth/me"; Expected = 401 },
    @{ Name = 'CRM direct missing JWT'; Url = "$CrmUrl/leads"; Expected = 401 },
    @{ Name = 'Identity missing JWT'; Url = "$GatewayUrl/api/identity/auth/me"; Expected = 401 },
    @{ Name = 'Identity invalid JWT'; Url = "$GatewayUrl/api/identity/auth/me"; Expected = 401;
        Args = @('-H', 'Authorization: Bearer invalid-test-token') },
    @{ Name = 'CRM missing JWT'; Url = "$GatewayUrl/api/crm/leads"; Expected = 401 },
    @{ Name = 'CRM invalid JWT'; Url = "$GatewayUrl/api/crm/leads"; Expected = 401;
        Args = @('-H', 'Authorization: Bearer invalid-test-token') },
    @{ Name = 'Lead detail missing JWT'; Url = "$GatewayUrl/api/crm/leads/00000000-0000-4000-8000-000000000001"; Expected = 401 },
    @{ Name = 'Vendor directory missing JWT'; Url = "$GatewayUrl/api/identity/admin/vendors"; Expected = 401 },
    @{ Name = 'Vendor invitation missing JWT'; Url = "$GatewayUrl/api/identity/admin/vendors/invitations"; Expected = 401;
        Args = @('-X', 'POST', '-H', 'Content-Type: application/json', '--data-binary', '{}') },
    @{ Name = 'Vendor invitation invalid JWT'; Url = "$GatewayUrl/api/identity/admin/vendors/invitations"; Expected = 401;
        Args = @('-X', 'POST', '-H', 'Authorization: Bearer invalid-test-token', '-H', 'Content-Type: application/json', '--data-binary', '{}') },
    @{ Name = 'Inactive vendor setup denied'; Url = "$GatewayUrl/api/identity/auth/vendor-password-setup"; Expected = 401;
        Args = @('-X', 'POST', '-H', 'Content-Type: application/json', '--data-binary', '{}') },
    @{ Name = 'Credentialed setup preflight'; Url = "$GatewayUrl/api/identity/auth/vendor-password-setup"; Expected = 200; Cors = $true;
        Args = @('-X', 'OPTIONS', '-H', 'Origin: http://localhost:5173', '-H', 'Access-Control-Request-Method: POST',
            '-H', 'Access-Control-Request-Headers: content-type') },
    @{ Name = 'Credentialed invitation preflight'; Url = "$GatewayUrl/api/identity/admin/vendors/invitations"; Expected = 200; Cors = $true;
        Args = @('-X', 'OPTIONS', '-H', 'Origin: http://localhost:5173', '-H', 'Access-Control-Request-Method: POST',
            '-H', 'Access-Control-Request-Headers: authorization,content-type') },
    @{ Name = 'Login invalid body'; Url = "$GatewayUrl/api/identity/auth/login"; Expected = 400;
        Args = @('-X', 'POST', '-H', 'Content-Type: application/json', '--data-binary', '{}') },
    @{ Name = 'Refresh missing cookie'; Url = "$GatewayUrl/api/identity/auth/refresh"; Expected = 401;
        Args = @('-X', 'POST') },
    @{ Name = 'Refresh CSRF rejection'; Url = "$GatewayUrl/api/identity/auth/refresh"; Expected = 403;
        Args = @('-X', 'POST', '-H', 'Cookie: KOC_REFRESH=synthetic-invalid-no-csrf') },
    @{ Name = 'Logout CSRF rejection'; Url = "$GatewayUrl/api/identity/auth/logout"; Expected = 403;
        Args = @('-X', 'POST', '-H', 'Cookie: KOC_REFRESH=synthetic-invalid-no-csrf') },
    @{ Name = 'Logout without session is no-op'; Url = "$GatewayUrl/api/identity/auth/logout"; Expected = 204;
        Args = @('-X', 'POST') },
    @{ Name = 'Credentialed CRM preflight'; Url = "$GatewayUrl/api/crm/leads"; Expected = 200; Cors = $true;
        Args = @('-X', 'OPTIONS', '-H', 'Origin: http://localhost:5173', '-H', 'Access-Control-Request-Method: GET',
            '-H', 'Access-Control-Request-Headers: authorization,content-type') },
    @{ Name = 'Unapproved CORS origin'; Url = "$GatewayUrl/api/crm/leads"; Expected = 403;
        Args = @('-X', 'OPTIONS', '-H', 'Origin: https://unapproved.example', '-H', 'Access-Control-Request-Method: GET') }
)
$failed = 0
foreach ($check in $checks) {
    $curlArgs = @('--silent', '--show-error', '--connect-timeout', '3', '--max-time', '8', '--noproxy', '*',
        '--output', 'NUL', '--dump-header', '-', '--write-out', '__HTTP_STATUS__%{http_code}')
    if ($check.Args) { $curlArgs += $check.Args }
    $output = (& curl.exe @curlArgs $check.Url) -join "`n"
    $curlExit = $LASTEXITCODE
    $code = if ($output -match '__HTTP_STATUS__(\d{3})') { $Matches[1] } else { '000' }
    $passed = $curlExit -eq 0 -and "$code" -eq "$($check.Expected)"
    if ($check.Cors) {
        $passed = $passed -and $output -match '(?im)^Access-Control-Allow-Origin:\s*http://localhost:5173\s*$' `
            -and $output -match '(?im)^Access-Control-Allow-Credentials:\s*true\s*$'
    }
    if (-not $passed) { $failed++ }
    [pscustomobject]@{ Check = $check.Name; Expected = $check.Expected; Actual = "$code"; Passed = $passed }
}
if ($failed -gt 0) {
    Write-Warning "$failed runtime checks failed. Connection failure or HTTP 503 can mean the service is not running or registered; this script does not start it."
    exit 1
}
exit 0
