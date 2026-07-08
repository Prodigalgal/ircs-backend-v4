param(
    [string]$Registry = "docker.io/speedproxy",
    [string]$Tag = "v4-native-dev",
    [string]$Platform = "linux/arm64"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $root

$images = @(
    @{ Module = "ircs-platform-api"; Dockerfile = "platform/ircs-platform-api/Dockerfile.native" },
    @{ Module = "ircs-worker-runtime"; Dockerfile = "platform/ircs-worker-runtime/Dockerfile.native" }
)

foreach ($image in $images) {
    docker buildx build `
        --platform $Platform `
        -f $image.Dockerfile `
        -t "$Registry/$($image.Module):$Tag" `
        --push `
        .
}
