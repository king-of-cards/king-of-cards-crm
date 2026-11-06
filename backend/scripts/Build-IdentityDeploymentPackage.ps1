[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$backend=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$build=Join-Path $backend 'identity-service\target-identity-deployment-verify-20261106\v2'
if(!(Test-Path -LiteralPath $build)){New-Item -ItemType Directory -Path $build | Out-Null}
$jar=Join-Path $build 'identity-maintenance.jar'
if(Test-Path -LiteralPath $jar){throw 'Preserve sealed helper artifact. Build a separately reviewed version instead of overwriting it.'}
$java='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin'
$deps=(Get-Content -LiteralPath (Join-Path $backend 'crm-service\target\directional-local-classpath.txt') -Raw).Trim()
$classes=Join-Path $build 'classes';if(!(Test-Path -LiteralPath $classes)){New-Item -ItemType Directory -Path $classes | Out-Null}
$sourceRoot=Join-Path $backend 'scripts\identity-deployment'
$sources=@(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' | ForEach-Object FullName)+@(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'tests') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $java 'javac.exe') -proc:none --release 21 -cp $deps -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Maintenance compile failed'}
$offline=Join-Path $build 'offline';if(!(Test-Path -LiteralPath $offline)){New-Item -ItemType Directory -Path $offline | Out-Null}
foreach($test in @('MaintenanceOfflineTest','StartupRegistrationTest','WindowsDetachTest')){
 [string[]]$testArguments=@($offline)
 if($test -eq 'WindowsDetachTest'){$testArguments+=@($classes)}
 $priorErrorPreference=$ErrorActionPreference
 try{$ErrorActionPreference='Continue'; & (Join-Path $java 'java.exe') -cp ($classes+';'+$deps) $test @testArguments *> (Join-Path $build ($test+'-final.log'));$testExit=$LASTEXITCODE}finally{$ErrorActionPreference=$priorErrorPreference}
 if($testExit -ne 0){Get-Content -LiteralPath (Join-Path $build ($test+'-final.log')) -Tail 16;throw ('Offline test failed: '+$test)}
 Get-Content -LiteralPath (Join-Path $build ($test+'-final.log')) | Where-Object {$_ -match '_PASS'}
}
& (Join-Path $sourceRoot 'tests\Test-RunnerOffline.ps1') *> (Join-Path $build 'runner-final.log')
if(!$?){throw 'Offline runner tests failed'}
Get-Content -LiteralPath (Join-Path $build 'runner-final.log') -Tail 1
$resources=Join-Path $build 'resources\META-INF';New-Item -ItemType Directory -Path $resources -Force | Out-Null
# Boot 4 no longer installs DelegatingApplicationContextInitializer. Register the
# initializer through the actual Spring factories SPI, not an inert system property.
[IO.File]::WriteAllText((Join-Path $resources 'spring.factories'),"org.springframework.context.ApplicationContextInitializer=com.kingofcards.identity.maintenance.IdentityMaintenanceInitializer`n",[Text.Encoding]::ASCII)
$manifest=Join-Path $build 'maintenance-manifest.mf'
[IO.File]::WriteAllText($manifest,"Manifest-Version: 1.0`nAgent-Class: com.kingofcards.identity.maintenance.attach.MaintenanceAgent`nCan-Redefine-Classes: false`nCan-Retransform-Classes: false`n`n",[Text.Encoding]::ASCII)
& (Join-Path $java 'jar.exe') --create --file $jar --manifest $manifest -C $classes com -C (Join-Path $build 'resources') META-INF
if($LASTEXITCODE -ne 0){throw 'Helper packaging failed'}
$sourceManifest=@();foreach($source in @($sources)+@($PSCommandPath,(Join-Path $backend 'scripts\Invoke-IdentityDeployment.ps1'))){$sourceManifest+=((Get-FileHash -LiteralPath $source).Hash+' '+$source)}
$sourceManifest | Set-Content -LiteralPath (Join-Path $build 'sealed-source-sha256.txt') -Encoding ASCII
Write-Output ('SEALED_HELPER='+$jar);Write-Output ('HELPER_SHA256='+(Get-FileHash -LiteralPath $jar).Hash)
Write-Output 'Application JARs unchanged. No admission installation, service startup or shutdown performed.'
