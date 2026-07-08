param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectPath,

    [Parameter(Mandatory = $true)]
    [string]$ImageName,

    [string]$Tag = "dev",
    [string]$Registry = "registry.mnnu.eu.org/ircs",
    [string]$Architecture = "arm64",
    [string]$Os = "linux",
    [string]$DockerConfigSecretNamespace = "ircs-prod",
    [string]$DockerConfigSecret = "registry-secret"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$normalizedProjectPath = $ProjectPath.Trim()

if (-not $normalizedProjectPath.StartsWith(":")) {
    $normalizedProjectPath = ":$normalizedProjectPath"
}

$encodedDockerConfig = kubectl get secret $DockerConfigSecret `
    -n $DockerConfigSecretNamespace `
    -o jsonpath="{.data.\.dockerconfigjson}"

if (-not $encodedDockerConfig) {
    throw ".dockerconfigjson not found in $DockerConfigSecretNamespace/$DockerConfigSecret"
}

$tmpDockerConfig = Join-Path ([System.IO.Path]::GetTempPath()) ("ircs-jib-docker-config-" + [System.Guid]::NewGuid())
New-Item -ItemType Directory -Path $tmpDockerConfig | Out-Null

$previousDockerConfig = $env:DOCKER_CONFIG

try {
    $configJson = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($encodedDockerConfig))
    [System.IO.File]::WriteAllText(
        (Join-Path $tmpDockerConfig "config.json"),
        $configJson,
        [System.Text.UTF8Encoding]::new($false)
    )
    $env:DOCKER_CONFIG = $tmpDockerConfig

    $image = "$Registry/$ImageName`:$Tag"

    Push-Location $root
    try {
        $taskPath = "${normalizedProjectPath}:jib"
        .\gradlew.bat $taskPath `
            "-PjibToImage=$image" `
            "-PjibTargetArch=$Architecture" `
            "-PjibTargetOs=$Os"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle Jib task failed for $taskPath"
        }
    }
    finally {
        Pop-Location
    }

    Write-Host "Pushed $image for $Os/$Architecture"
}
finally {
    $env:DOCKER_CONFIG = $previousDockerConfig
    Remove-Item -LiteralPath $tmpDockerConfig -Recurse -Force -ErrorAction SilentlyContinue
}
