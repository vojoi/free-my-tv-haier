param(
    [switch]$Public,
    [string]$JdkHome,
    [string]$SdkRoot
)
$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
Push-Location -LiteralPath $PSScriptRoot
try {
# Explicit paths, environment, then conventional locations; never download a toolchain.
$runtime = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../android-dev'))
if (!$JdkHome) { $JdkHome = $env:JAVA_HOME }
if (!$JdkHome) {
    $bundledJdks = @(Get-ChildItem -LiteralPath (Join-Path $runtime 'jdk') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') })
    if ($bundledJdks.Count) { $JdkHome = $bundledJdks[0].FullName }
}
if (!$JdkHome) {
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) { $JdkHome = Split-Path (Split-Path $javac.Source -Parent) -Parent }
}
if (!$JdkHome -or !(Test-Path -LiteralPath (Join-Path $JdkHome 'bin/javac.exe'))) {
    throw 'JDK 17+ not found. Set JAVA_HOME or pass -JdkHome to a JDK installation.'
}
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (!$SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (!$SdkRoot) {
    $sdkCandidates = @((Join-Path $runtime 'sdk'))
    if ($env:LOCALAPPDATA) { $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android/Sdk') }
    foreach ($candidate in $sdkCandidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'build-tools/34.0.0/aapt2.exe')) {
            $SdkRoot = $candidate
            break
        }
    }
}
if (!$SdkRoot) { throw 'Android SDK not found. Set ANDROID_HOME or pass -SdkRoot.' }
$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$SdkRoot = [IO.Path]::GetFullPath($SdkRoot)
$bt = Join-Path $SdkRoot 'build-tools/34.0.0'
$aj = Join-Path $SdkRoot 'platforms/android-34/android.jar'
foreach ($tool in @('aapt2.exe','core-lambda-stubs.jar','d8.bat','zipalign.exe','apksigner.bat')) {
    if (!(Test-Path -LiteralPath (Join-Path $bt $tool))) {
        throw "Missing Android build-tools 34.0.0 file: $tool. Install build-tools;34.0.0 with sdkmanager."
    }
}
if (!(Test-Path -LiteralPath $aj)) { throw 'Android API 34 missing. Install platforms;android-34 with sdkmanager.' }
# Child SDK tools use JAVA_HOME. Restore the caller's environment in finally.
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome/bin;$env:PATH"
$out = if ($Public) { 'out/windows-public' } else { 'out/windows' }
$apk = if ($Public) { 'FreeMyTV-Public.apk' } else { 'FreeMyTV.apk' }
New-Item -ItemType Directory -Force "$out/gen/io/freemytv", "$out/classes" | Out-Null
function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}
$properties = @{}
if (!(Test-Path -LiteralPath 'config.properties')) { Copy-Item -LiteralPath 'config.example.properties' -Destination 'config.properties' }
foreach ($line in Get-Content -LiteralPath 'config.properties' -Encoding UTF8) {
    if ($line -match '^\s*([^#=]+?)\s*=(.*)$') { $properties[$Matches[1].Trim()] = $Matches[2].Trim() }
}
if ($Public) { $properties['tmdb_key'] = '' }
$config = @('package io.freemytv;', 'final class Config {', 'private Config() {}')
foreach ($key in @('tmdb_key','tmdb_region','latitude','longitude','city','language','currency','temperature')) {
    $value = $properties[$key].Replace('\','\\').Replace('"','\"')
    $config += 'static final String ' + $key.ToUpperInvariant() + ' = "' + $value + '";'
}
foreach ($key in @('disable_assistant','show_crypto')) {
    if ($properties[$key] -notin @('true','false')) { throw "Invalid boolean $key" }
    $config += 'static final boolean ' + $key.ToUpperInvariant() + ' = ' + $properties[$key] + ';'
}
$config += '}'
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot "$out/gen/io/freemytv/Config.java"), $config, [Text.UTF8Encoding]::new($false))
Run "$bt/aapt2.exe" @('compile','--dir','app/res','-o',"$out/res.zip")
Run "$bt/aapt2.exe" @('link','-o',"$out/base.apk",'-I',$aj,'--manifest','app/AndroidManifest.xml','--java',"$out/gen","$out/res.zip",'--min-sdk-version','28','--target-sdk-version','34','-A','app/assets','-0','ttf')
# Use the author's Windows ZIP path fix, preserving STORED fonts.
$build = Get-Content -LiteralPath 'build.sh' -Raw
$fix = [regex]::Match($build, "cat > out/FixAssets.java <<'EOF'\r?\n([\s\S]*?)\r?\nEOF").Groups[1].Value
if (!$fix) { throw 'Author asset normalizer was not found.' }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot "$out/FixAssets.java"), $fix, [Text.UTF8Encoding]::new($false))
Run "$env:JAVA_HOME/bin/java.exe" @("$out/FixAssets.java","$out/base.apk")
$sources = @(Get-ChildItem 'app/src',"$out/gen" -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot "$out/javac.args"), $sources, [Text.UTF8Encoding]::new($false))
Run "$env:JAVA_HOME/bin/javac.exe" @('-nowarn','-Xlint:-options','-source','8','-target','8','-bootclasspath',"$aj;$bt/core-lambda-stubs.jar",'-classpath',$aj,'-encoding','UTF-8','-d',"$out/classes","@$out/javac.args")
$classes = @(Get-ChildItem "$out/classes" -Recurse -Filter '*.class' | ForEach-Object { $_.FullName.Replace('\','/') })
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot "$out/d8.args"), $classes, [Text.UTF8Encoding]::new($false))
Run "$bt/d8.bat" @('--release','--min-api','28','--lib',$aj,'--output',$out,"@$out/d8.args")
Run "$env:JAVA_HOME/bin/jar.exe" @('uf',"$out/base.apk",'-C',$out,'classes.dex')
Run "$bt/zipalign.exe" @('-f','-p','4',"$out/base.apk","$out/aligned.apk")
if (!(Test-Path -LiteralPath 'freemytv.keystore')) {
    Run "$env:JAVA_HOME/bin/keytool.exe" @('-genkeypair','-keystore','freemytv.keystore','-alias','freemytv','-keyalg','RSA','-keysize','2048','-validity','20000','-storepass','freemytv','-keypass','freemytv','-dname','CN=Free My TV')
}
Run "$bt/apksigner.bat" @('sign','--ks','freemytv.keystore','--ks-pass','pass:freemytv','--key-pass','pass:freemytv','--out',$apk,"$out/aligned.apk")
Run "$bt/apksigner.bat" @('verify','--verbose',$apk)
Write-Host "Built $apk"
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    Pop-Location
}
