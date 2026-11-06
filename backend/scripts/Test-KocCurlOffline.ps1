# No HTTP, credentials or database access: curl.exe is replaced in this script scope.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Koc-Curl.ps1')
$script:CapturedConfig=''
$script:CapturedArguments=@()
$script:MockResponse='{"ok":true}__KOC_STATUS__200'
function curl.exe {
    $script:CapturedConfig=@($input) -join "`n"
    $script:CapturedArguments=$args
    $global:LASTEXITCODE=0
    return $script:MockResponse
}
if ((Get-Command curl.exe).CommandType -ne 'Function') { throw 'Offline cURL stub was not installed.' }
$response=Invoke-KocCurl -Method POST -Path '/api/identity/auth/login' -BearerToken 'synthetic-secret-token' `
    -Body @{email='synthetic@example.test';password='synthetic"password\value'}
if ($response.StatusCode -ne 200 -or -not $response.Body.ok) { throw 'JSON/status parsing failed.' }
if (($script:CapturedArguments -join ' ') -match 'synthetic|password|Authorization') { throw 'A secret reached command-line arguments.' }
if ($script:CapturedConfig -notmatch 'Authorization: Bearer synthetic-secret-token') { throw 'Secret header stdin transport failed.' }
$dataLine=@($script:CapturedConfig -split "`n" | Where-Object { $_ -like 'data-binary = *' })[0]
$match=[regex]::Match($dataLine,'^data-binary = "(.*)"$')
if (-not $match.Success) { throw 'A quoted cURL JSON directive is required.' }
$roundTripJson=[regex]::Replace($match.Groups[1].Value,'\\([\\"])','$1')
$roundTrip=ConvertFrom-Json -InputObject $roundTripJson
if ($roundTrip.password -ne 'synthetic"password\value') { throw 'JSON secret did not round-trip through cURL config escaping.' }
Write-Output 'PASS: JSON parsing and secret headers/body through stdin only.'
$response=Invoke-KocCurl -Method POST -Path '/api/identity/auth/login' -Body @{}
if (-not $script:CapturedConfig.Contains('data-binary = "{}"')) { throw 'Empty JSON body was lost.' }
Write-Output 'PASS: empty JSON validation request remains an explicit body.'
$script:MockResponse='__KOC_STATUS__204'
$response=Invoke-KocCurl -Method POST -Path '/api/identity/auth/logout'
if ($response.StatusCode -ne 204 -or $null -ne $response.Body) { throw 'Empty success response parsing failed.' }
Write-Output 'PASS: empty 204 response parsing.'
