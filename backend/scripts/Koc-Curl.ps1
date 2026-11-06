# Dot-source this file. It defines a local cURL helper and executes no requests.
# Assign its return value: authentication responses contain secrets.
function Invoke-KocCurl {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET','POST','PATCH','OPTIONS')][string]$Method='GET',
        [hashtable]$Body,
        [string]$BearerToken,
        [string]$CookieJar,
        [switch]$Csrf
    )
    if (-not $Path.StartsWith('/api/') -or $Path.Contains("`r") -or $Path.Contains("`n")) {
        throw 'Use an existing /api/ path on the local Gateway.'
    }
    function Escape-KocCurl([string]$value) {
        return $value.Replace('\','\\').Replace('"','\"').Replace("`r",'\r').Replace("`n",'\n')
    }
    $config=@('url = "http://localhost:8080'+(Escape-KocCurl $Path)+'"',
        'request = "'+$Method+'"','header = "Origin: http://localhost:5173"')
    if ($PSBoundParameters.ContainsKey('Body')) {
        $json=ConvertTo-Json -InputObject $Body -Compress -Depth 8
        $config+='header = "Content-Type: application/json"'
        $config+='data-binary = "'+(Escape-KocCurl $json)+'"'
    }
    if ($BearerToken) { $config+='header = "Authorization: Bearer '+(Escape-KocCurl $BearerToken)+'"' }
    if ($CookieJar) {
        $full=[IO.Path]::GetFullPath($CookieJar)
        $repository=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
        if ($full.StartsWith($repository+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
            throw 'Session cookies must be stored outside the repository in a private local directory.'
        }
        $config+='cookie = "'+(Escape-KocCurl $full)+'"'
        $config+='cookie-jar = "'+(Escape-KocCurl $full)+'"'
    }
    if ($Csrf) {
        if (-not $CookieJar -or -not (Test-Path -LiteralPath $CookieJar)) { throw 'A private session cookie jar is required.' }
        $csrfValue=$null
        foreach($line in Get-Content -LiteralPath $CookieJar) {
            $fields=$line -split "`t"
            if ($fields.Count -eq 7 -and $fields[5] -eq 'XSRF-TOKEN') { $csrfValue=[uri]::UnescapeDataString($fields[6]) }
        }
        if (-not $csrfValue) { throw 'The session has no CSRF cookie.' }
        $config+='header = "X-XSRF-TOKEN: '+(Escape-KocCurl $csrfValue)+'"'
    }
    # Secret headers, JSON passwords and setup tokens travel through stdin, never OS arguments.
    $result=(($config -join "`n") | curl.exe --config - --silent --show-error --noproxy '*' `
        --max-time 20 --write-out '__KOC_STATUS__%{http_code}') -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Local cURL transport failed.' }
    $marker=$result.LastIndexOf('__KOC_STATUS__')
    if ($marker -lt 0) { throw 'Local cURL returned no HTTP status.' }
    $status=[int]$result.Substring($marker+'__KOC_STATUS__'.Length)
    $content=$result.Substring(0,$marker)
    $parsed=$null
    if (-not [string]::IsNullOrWhiteSpace($content)) { $parsed=ConvertFrom-Json -InputObject $content }
    return [pscustomobject]@{StatusCode=$status;Body=$parsed}
}
