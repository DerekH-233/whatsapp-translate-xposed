# Publish a release to both places, in one command.
#
#   1. bump the version in build.ps1
#   2. build and sign the APK
#   3. push source + tag to the source repo
#   4. create the release in the LSPosed module repository, which is what the
#      manager's "Repository" tab indexes
#
# The module repository lives under a third-party org and only ever holds a
# README plus releases - no source. The tag there must be
# <versionCode>-<versionName>; the indexer ignores any other shape.
#
# Usage:
#   .\tools\release.ps1 -Version 1.2.0 -VersionCode 3
#   .\tools\release.ps1 -Version 1.2.0 -VersionCode 3 -Notes .\NOTES.md
#   .\tools\release.ps1 -Version 1.2.0 -VersionCode 3 -SkipMarket
#
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$Notes,
    [switch]$SkipMarket
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$SOURCE_REPO = 'DerekH-233/whatsapp-translate-xposed'
$MARKET_ORG = 'Xposed-Modules-Repo'
$PKG = 'io.github.derekh_233.watranslate'
$MARKET_REPO = "$MARKET_ORG/$PKG"

function Say($m) { Write-Host "[release] $m" }
function Fail($m) { Write-Host "[release] ERROR: $m" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- preflight
if (git status --porcelain) {
    Say 'working tree is dirty:'
    git status --short | ForEach-Object { Write-Host "   $_" }
    Fail 'commit or stash first, so the release matches a known revision'
}

if (-not (Test-Path 'build.ps1')) { Fail 'run this from the repository root' }

if (-not $SkipMarket) {
    $check = gh api "repos/$MARKET_REPO" --jq '.full_name' 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail @"
$MARKET_REPO does not exist yet, or you are not a collaborator on it.
Submit the module first at https://modules.lsposed.org/submission and accept
the invitation, then re-run. Use -SkipMarket to publish to the source repo only.
"@
    }
}

# ------------------------------------------------------------ bump version
Say "setting version $Version (code $VersionCode)"
$build = Get-Content build.ps1 -Raw
if ($build -notmatch '--version-code \d+' -or $build -notmatch '--version-name [0-9][0-9.]*') {
    Fail 'could not find --version-code/--version-name in build.ps1'
}
$new = [regex]::Replace($build, '--version-code \d+', "--version-code $VersionCode")
$new = [regex]::Replace($new, '--version-name [0-9][0-9.]*', "--version-name $Version")
if ($new -ne $build) {
    [System.IO.File]::WriteAllText((Join-Path $root 'build.ps1'), $new)
} else {
    Say 'build.ps1 already at this version'
}

# ---------------------------------------------------------------- build
Say 'building'
& (Join-Path $root 'build.ps1') -SkipInstall
if ($LASTEXITCODE -ne 0) { Fail 'build failed' }

$apk = Join-Path $root 'LSTrans.apk'
if (-not (Test-Path $apk)) { Fail "expected $apk" }
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $apk).Length
Say "apk $size bytes, sha256 $sha"

# ------------------------------------------------------- source repo: push
Say 'pushing source'
git add -A
if (git status --porcelain) {
    git commit -m "Release $Version" | Out-Null
} else {
    Say 'nothing to commit'
}
$srcTag = "v$Version"
git tag -f $srcTag | Out-Null
git push origin HEAD | Out-Null
git push -f origin $srcTag | Out-Null
Say "source pushed, tag $srcTag"

# The source repo needs a release as well, not just the tag: a bare tag leaves
# GitHub reporting the previous version as "Latest".
$srcNotes = Join-Path $env:TEMP "lst_src_$VersionCode.md"
if ($Notes -and (Test-Path $Notes)) {
    Copy-Item $Notes $srcNotes -Force
} else {
    @"
## WhatsApp 翻译助手 $Version

SHA-256: ``$sha``

APK attached below. The same build is published to the module repository for
the LSPosed manager:
https://github.com/$MARKET_REPO/releases/tag/$VersionCode-$Version
"@ | Set-Content -Path $srcNotes -Encoding utf8
}

gh release view $srcTag --repo $SOURCE_REPO --json tagName 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Say "source release $srcTag already exists; leaving it alone"
} else {
    gh release create $srcTag $apk `
        --repo $SOURCE_REPO `
        --title "WhatsApp 翻译助手 $Version" `
        --notes-file $srcNotes | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 'could not create the source release' }
    Say "source release $srcTag created"
}
Remove-Item $srcNotes -Force -ErrorAction SilentlyContinue

if ($SkipMarket) { Say 'done (market step skipped)'; exit 0 }

# ----------------------------------------------------- market repo: release
$marketTag = "$VersionCode-$Version"
Say "creating release $marketTag in $MARKET_REPO"

$existing = gh release view $marketTag --repo $MARKET_REPO --json tagName 2>&1
if ($LASTEXITCODE -eq 0) { Fail "tag $marketTag already exists in $MARKET_REPO" }

$notes = Join-Path $env:TEMP "lst_notes_$VersionCode.md"
@"
## WhatsApp 翻译助手 $Version

See the source repository for full notes:
https://github.com/$SOURCE_REPO/releases/tag/$srcTag

- APK size: $size bytes
- SHA-256: ``$sha``
"@ | Set-Content -Path $notes -Encoding utf8

gh release create $marketTag $apk `
    --repo $MARKET_REPO `
    --title "WhatsApp 翻译助手 $Version" `
    --notes-file $notes
if ($LASTEXITCODE -ne 0) { Fail 'could not create the market release' }

Remove-Item $notes -Force -ErrorAction SilentlyContinue

Say "published:"
Say "  source  https://github.com/$SOURCE_REPO/releases/tag/$srcTag"
Say "  market  https://github.com/$MARKET_REPO/releases/tag/$marketTag"
Say 'the indexer picks the market release up on its next pass'
