param(
    [Parameter(Mandatory = $true)]
    [string]$Service,

    [string]$Tag = "dev",
    [string]$Registry = "docker.io/speedproxy",
    [string]$Platform = "linux/arm64",
    [switch]$Push
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$serviceDir = Join-Path $root "services\$Service"
$dockerfile = Join-Path $serviceDir "Dockerfile"

if (-not (Test-Path $dockerfile)) {
    throw "Dockerfile not found for service $Service at $dockerfile"
}

docker version --format "{{.Server.Version}}" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker daemon is unavailable. Start Docker Desktop, then rerun this script."
}

Push-Location $root
try {
    .\gradlew.bat ":services:$Service:bootJar"
    if ($LASTEXITCODE -ne 0) {
        throw "bootJar failed for services:$Service"
    }

    $image = "$Registry/$Service`:$Tag"

    $buildArgs = @(
        "buildx",
        "build",
        "--platform",
        $Platform,
        "-f",
        $dockerfile,
        "-t",
        $image
    )

    if ($Push) {
        $buildArgs += "--push"
    } else {
        $buildArgs += "--load"
    }

    $buildArgs += "."
    docker @buildArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker buildx failed for $image on $Platform"
    }

    Write-Host $image
}
finally {
    Pop-Location
}
