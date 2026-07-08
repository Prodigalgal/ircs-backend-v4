param(
    [string]$V1Root = "D:\WorkSpace\Project\ircs\ircs-project-v1\ircs-backend",
    [switch]$SkipGradle
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$fixtureDir = Join-Path $repoRoot "build\tmp\02816d-tika-smoke-$stamp"
$gradle = Join-Path $repoRoot "gradlew.bat"

function Mark {
    param([string]$Name, [int]$Index, [int]$Total)
    Write-Output ("MARK {0}/{1} {2}" -f $Index, $Total, $Name)
}

function Convert-HexToBytes {
    param([string]$Hex)
    $clean = $Hex -replace "\s", ""
    $bytes = New-Object byte[] ($clean.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bytes[$i] = [Convert]::ToByte($clean.Substring($i * 2, 2), 16)
    }
    return $bytes
}

function Assert-Contains {
    param([string]$Path, [string]$Pattern, [string]$Reason)
    if (-not (Select-String -Path $Path -Pattern $Pattern -Quiet)) {
        throw $Reason
    }
}

function Assert-NotContains {
    param([string]$Path, [string]$Pattern, [string]$Reason)
    if (Select-String -Path $Path -Pattern $Pattern -Quiet) {
        throw $Reason
    }
}

try {
    if (-not (Test-Path $V1Root)) {
        throw "V1 root not found: $V1Root"
    }

    $v1Build = Join-Path $V1Root "build.gradle"
    $v1Storage = Join-Path $V1Root "src\main\java\com\prodigalgal\ircs\modules\storage\service\impl\ImageSecurityValidatorImpl.java"
    $v1Scraper = Join-Path $V1Root "src\main\java\com\prodigalgal\ircs\modules\scraper\service\impl\ListScraperServiceImpl.java"
    Assert-Contains $v1Build "tika-core:3.2.2" "V1 tika-core dependency missing"
    Assert-Contains $v1Build "tika-parsers-standard-package:3.2.2" "V1 parser standard dependency missing"
    Assert-Contains $v1Storage "new Tika\(\)" "V1 storage Tika.detect source missing"
    Assert-Contains $v1Scraper "UniversalEncodingDetector" "V1 scraper charset detector missing"
    $genericParsers = Get-ChildItem -Path (Join-Path $V1Root "src\main\java") -Recurse -Filter *.java |
        Select-String -Pattern "AutoDetectParser|BodyContentHandler|PDFParser"
    if ($genericParsers) {
        throw "V1 generic text extraction parser usage found; D3 boundary must be revisited"
    }
    Mark "V1 Tika usage points documented and no generic text extraction parser usage found" 1 6

    $storageBuild = Join-Path $repoRoot "services\ircs-storage-service\build.gradle"
    $scraperBuild = Join-Path $repoRoot "services\ircs-scraper-service\build.gradle"
    Assert-Contains $storageBuild "tika-core:3.2.2" "storage-service tika-core dependency missing"
    Assert-NotContains $storageBuild "tika-parsers-standard-package" "storage-service must not include parser standard package"
    Assert-Contains $scraperBuild "tika-core:3.2.2" "scraper-service tika-core dependency missing"
    Assert-Contains $scraperBuild "tika-parsers-standard-package:3.2.2" "scraper-service parser standard package dependency missing"
    Assert-NotContains $scraperBuild "tika-parser-text-module" "scraper-service must use parser standard package instead of narrow text module"
    Mark "V3 Tika dependencies are service-scoped: storage core-only, scraper standard parser package" 2 6

    New-Item -ItemType Directory -Path $fixtureDir -Force | Out-Null
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "valid.png"),
        (Convert-HexToBytes "89504E470D0A1A0A0000000D4948445200000001000000010802000000907753DE0000000049454E44AE426082"))
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "spoof-html.png"),
        [Text.Encoding]::UTF8.GetBytes("<html><body>not an image</body></html>"))
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "renamed.pdf.png"),
        [Text.Encoding]::ASCII.GetBytes("%PDF-1.7`n"))
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "gb18030-list.json"),
        [Text.Encoding]::GetEncoding("GB18030").GetBytes('{"items":[{"vod_id":"v-gbk","updated":"2026-06-09","name":"中文标题"}]}'))
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "big5-detail.json"),
        [Text.Encoding]::GetEncoding("Big5").GetBytes('{"list":[{"vod_id":"d-big5","vod_name":"繁體詳情"}]}'))
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "shift-jis-list.json"),
        [Text.Encoding]::GetEncoding("Shift_JIS").GetBytes('{"items":[{"vod_id":"v-sjis","updated":"2026-06-10","name":"日本映画"}]}'))
    $bomPagination = [byte[]](0xEF, 0xBB, 0xBF) + [Text.Encoding]::UTF8.GetBytes('{"total":"88","pagecount":9,"items":[{"vod_id":"v-page","updated":"2026-06-10"}]}')
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "utf8-bom-pagination.json"),
        [byte[]]$bomPagination)
    [IO.File]::WriteAllBytes(
        (Join-Path $fixtureDir "empty.bin"),
        (New-Object byte[] 0))

    $summary = Get-ChildItem -LiteralPath $fixtureDir | ForEach-Object {
        $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName
        [ordered]@{
            name = $_.Name
            bytes = $_.Length
            sha256 = $hash.Hash.ToLowerInvariant()
        }
    }
    $summary | ConvertTo-Json -Depth 4
    Mark "small local fixtures created for image spoof, pdf rename, GB18030/Big5/Shift_JIS/BOM JSON and empty file" 3 6

    if (-not $SkipGradle) {
        Push-Location $repoRoot
        try {
            & $gradle --no-daemon `
                :services:ircs-storage-service:test `
                :services:ircs-scraper-service:test `
                --tests "*ImageSecurityValidatorTest" `
                --tests "*FileNormalizationServiceTest" `
                --tests "*ListScraperClientTest" `
                --tests "*ScraperCharsetDetectorTest" `
                --rerun-tasks
            if ($LASTEXITCODE -ne 0) {
                throw "Gradle targeted Tika/file parsing tests failed with exit code $LASTEXITCODE"
            }
        } finally {
            Pop-Location
        }
        Mark "focused Gradle tests cover MIME mismatch, bad files, size/name guards and charset parsing" 4 6
    } else {
        Mark "focused Gradle tests skipped by -SkipGradle; fixture prep and dependency guards only" 4 6
    }

    Mark "smoke completed without live network, uploads, business DB writes or public HTTPRoute" 5 6
} finally {
    if (Test-Path $fixtureDir) {
        $resolvedFixtureDir = (Resolve-Path $fixtureDir).Path
        if (-not $resolvedFixtureDir.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean fixture dir outside repo: $resolvedFixtureDir"
        }
        Remove-Item -LiteralPath $resolvedFixtureDir -Recurse -Force
    }
    if (Test-Path $fixtureDir) {
        throw "Fixture cleanup failed: $fixtureDir"
    }
    Mark "fixture directory cleaned: $fixtureDir" 6 6
}
