param(
    [string]$Registry = "registry.mnnu.eu.org/ircs",
    [string]$Tag = "v4-dev",
    [string]$Platforms = "linux/arm64",
    [string]$ActiveProcessorCount = "1"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $root

$modules = @(
    "ircs-platform-api",
    "ircs-worker-runtime"
)

foreach ($module in $modules) {
    ./gradlew.bat --no-daemon ":platform:$module:jib" `
        "-PjibToImage=$Registry/$module`:$Tag" `
        "-PjibTargetPlatforms=$Platforms" `
        "-PircsRuntimeActiveProcessorCount=$ActiveProcessorCount"
}
