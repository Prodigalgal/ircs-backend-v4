param(
    [switch]$Native,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $root

$tasks = @(
    ":platform:ircs-platform-api:bootJar",
    ":platform:ircs-worker-runtime:bootJar"
)

if ($Native) {
    $tasks += @(
        ":platform:ircs-platform-api:nativeCompile",
        ":platform:ircs-worker-runtime:nativeCompile"
    )
}

if ($SkipTests) {
    ./gradlew.bat --no-daemon @tasks -x test
} else {
    ./gradlew.bat --no-daemon @tasks
}
