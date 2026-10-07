# Fetch the libxposed Modern API artifacts (Apache-2.0) from Maven Central and
# extract their classes.jar next to the build output.
#
#   api.jar      compileOnly - the framework supplies io.github.libxposed.api at
#                runtime; bundling it would break the module classloader
#   service.jar  bundled into the APK - ships XposedProvider, which is how the
#                framework hands the settings UI a service binder
#
# Run once; build.ps1 fails with a clear message when these are missing.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dest = Join-Path $root 'build\libxposed'
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$version = '102.0.0'
$base = "https://repo1.maven.org/maven2/io/github/libxposed"

foreach ($name in @('api', 'service')) {
    $aar = Join-Path $dest "$name.aar"
    $jar = Join-Path $dest "$name.jar"
    $url = "$base/$name/$version/$name-$version.aar"

    Write-Host "[libxposed] $url"
    Invoke-WebRequest -Uri $url -OutFile $aar -UseBasicParsing

    python -c @"
import sys, zipfile
aar, out = sys.argv[1], sys.argv[2]
data = zipfile.ZipFile(aar).read('classes.jar')
open(out, 'wb').write(data)
print('[libxposed] %s -> %d bytes' % (out, len(data)))
"@ $aar $jar
    if ($LASTEXITCODE -ne 0) { throw "extracting classes.jar from $aar failed" }
}

Write-Host "[libxposed] ready in $dest"
