[CmdletBinding()]
param([switch]$ValidateOnly,[switch]$CrmOnly,[switch]$ReadOnlySchemaCheck,[switch]$ReadOnlyJobCheck,[switch]$DeferDirectionalReads,[switch]$InitializeSchema,[switch]$EnableLeadWrites,
    [switch]$EnableVerifiedIdentityWrites,[string]$VerificationEvidence,
    [ValidateSet('target','target-directional-verify')][string]$ArtifactSubdirectory='target-directional-verify')
$ErrorActionPreference='Stop'
$backend=Split-Path -Parent $PSScriptRoot
$repo=Split-Path -Parent $backend
# Read only this setting. Never source the migration .env or read the JSON contents.
$resolver=@'
import os,shlex,subprocess
try:
 base='/mnt/c/Users/kingo/migration_code'
 values=[]
 for line in open(base+'/.env',encoding='utf-8-sig'):
  line=line.strip()
  if line.startswith('export '): line=line[7:]
  if '=' in line and line.split('=',1)[0].strip()=='GOOGLE_APPLICATION_CREDENTIALS': values.append(line.split('=',1)[1].strip())
 if len(values)!=1: raise ValueError()
 parts=shlex.split(values[0],comments=True)
 if len(parts)!=1: raise ValueError()
 path=os.path.normpath(os.path.join(base,os.path.expanduser(parts[0])))
 if not os.path.isfile(path): raise ValueError()
 print(subprocess.check_output(['wslpath','-w',path],universal_newlines=True).strip())
except Exception:
 raise SystemExit(2)
'@
$credential=($resolver | & wsl.exe --exec python3 - 2>$null | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or !$credential -or !(Test-Path -LiteralPath $credential -PathType Leaf)){throw 'Existing WSL credential path cannot be accessed by Windows. Run Windows under the authorized WSL user/distro or run the backend within that WSL distro using the original credential path; do not copy the key into this repository.'}
Write-Host 'Existing WSL credential is Windows-accessible. Credential contents were not read.'
$saved=[Environment]::GetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS','Process')
try {
    $env:GOOGLE_APPLICATION_CREDENTIALS=$credential
    if($ReadOnlySchemaCheck -or $ReadOnlyJobCheck){
        $module=Join-Path $backend 'crm-service'
        $cpFile=Join-Path $module 'target\directional-local-classpath.txt'
        if(!(Test-Path -LiteralPath $cpFile)){throw 'Cached runtime classpath is missing; run the documented dependency:build-classpath command offline.'}
        $cp=(Join-Path $module ($ArtifactSubdirectory+'\classes'))+';'+[IO.File]::ReadAllText($cpFile).Trim()
        if($ReadOnlyJobCheck){& java.exe '-cp' $cp (Join-Path $PSScriptRoot 'VerifyDirectionalBigQuerySchema.java') 'jobs'}
        else{& java.exe '-cp' $cp (Join-Path $PSScriptRoot 'VerifyDirectionalBigQuerySchema.java')}
        if($LASTEXITCODE -ne 0){throw 'Read-only BigQuery metadata verification failed; no DDL or DML was submitted.'}
        return
    }
    if($EnableVerifiedIdentityWrites -and (!$VerificationEvidence -or !(Test-Path -LiteralPath $VerificationEvidence -PathType Leaf))){throw 'An explicitly reviewed evidence file is required for Identity writes.'}
    $services=if($CrmOnly){@('crm')}else{@('discovery','identity','crm','gateway')}
    foreach($service in $services){
        $port=@{discovery=8761;identity=8081;crm=8082;gateway=8080}[$service]
        $occupied=Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        if($occupied -and !$ValidateOnly){
            if(($service -eq 'crm' -and ($InitializeSchema -or $EnableLeadWrites)) -or ($service -eq 'identity' -and $EnableVerifiedIdentityWrites)){throw "Port $port is occupied. The requested flags were NOT applied. Review and stop only the confirmed outdated service before rerunning. No process was terminated."}
            Write-Warning "Port $port is occupied; existing service retained, not reconfigured or claimed current."
            continue
        }
        $arguments=@('-NoProfile','-File',(Join-Path $PSScriptRoot 'Start-LocalService.ps1'),'-Service',$service,'-ArtifactSubdirectory',$ArtifactSubdirectory)
        if($service -in @('identity','crm')){$arguments+='-EnableBigQueryReads'}
        if($ValidateOnly){$arguments+='-ValidateOnly'}
        if($service -eq 'crm' -and $InitializeSchema){$arguments+='-InitializeDirectionalSchema'}
        if($service -eq 'crm' -and $DeferDirectionalReads){$arguments+='-DeferDirectionalReads'}
        if($service -eq 'crm' -and $EnableLeadWrites){$arguments+='-EnableDirectionalWrites'}
        if($service -eq 'identity' -and $EnableVerifiedIdentityWrites){$arguments+=@('-EnableVerifiedIdentityWrites','-VerificationEvidence',$VerificationEvidence)}
        & powershell.exe @arguments
        if($LASTEXITCODE -ne 0){throw "Preflight/start failed for $service. Previously running services were preserved; inspect the error before retrying."}
    }
    if(!$ValidateOnly -and !$CrmOnly){
        if(!(Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)){
            $oldBase=$env:VITE_API_BASE_URL;$oldLocal=$env:VITE_KOC_LEADS_LOCAL
            try{$env:VITE_API_BASE_URL='http://localhost:8080';$env:VITE_KOC_LEADS_LOCAL='false';Start-Process -FilePath $env:ComSpec -ArgumentList '/c npm.cmd run dev -- --host localhost --port 5173' -WorkingDirectory (Join-Path $repo 'frontend') -WindowStyle Hidden | Out-Null}
            finally{$env:VITE_API_BASE_URL=$oldBase;$env:VITE_KOC_LEADS_LOCAL=$oldLocal}
        }else{Write-Warning 'Frontend port 5173 is occupied; existing Vite configuration was not changed.'}
        Write-Host 'Open http://localhost:5173/leads. Default: schema DDL and all writes disabled. A healthy process alone does not prove real login or persistence.'
    }
} finally {[Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS',$saved,'Process')}
