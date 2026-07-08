param(
    [string]$BaseUrl = $env:IRCS_BASE_URL,
    [string]$AdminToken = $env:IRCS_ADMIN_TOKEN,
    [string]$DataSourceName = "光速资源",
    [string]$ResolverName = "金蝉解析",
    [switch]$ExecuteLive,
    [switch]$AllowExternalFetch
)

$ErrorActionPreference = "Stop"

function Write-Mark {
    param([string]$Message)
    Write-Host "MARK $Message"
}

function Assert-HttpUrl {
    param(
        [string]$Name,
        [string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is required when -ExecuteLive is set"
    }
    $uri = [System.Uri]$Value
    if ($uri.Scheme -notin @("http", "https")) {
        throw "$Name must be an http/https URL: $Value"
    }
}

Write-Mark "1/5 resolver/resource live smoke prep loaded"
Write-Host "BaseUrl=$BaseUrl"
Write-Host "DataSourceName=$DataSourceName"
Write-Host "ResolverName=$ResolverName"

if (-not $ExecuteLive) {
    Write-Mark "2/5 dry-run only; no HTTP calls will be sent"
    Write-Mark "3/5 planned check: catalog data source preset exists and can sync remote class[] under explicit live gate"
    Write-Mark "4/5 planned check: content active resolver preset exists and exposes default line"
    Write-Mark "5/5 planned check: external fetch remains blocked unless -AllowExternalFetch is supplied"
    exit 0
}

Assert-HttpUrl -Name "BaseUrl" -Value $BaseUrl
if ([string]::IsNullOrWhiteSpace($AdminToken)) {
    throw "IRCS_ADMIN_TOKEN/AdminToken is required when -ExecuteLive is set"
}
if (-not $AllowExternalFetch) {
    throw "-ExecuteLive requires -AllowExternalFetch so real resolver/resource access is explicit"
}

$headers = @{ Authorization = "Bearer $AdminToken" }

Write-Mark "2/5 live gate acknowledged"
$dataSources = Invoke-RestMethod -Method Get -Headers $headers -Uri "$BaseUrl/api/v1/data-sources?size=100"
$matchedSource = @($dataSources.content) | Where-Object { $_.name -eq $DataSourceName } | Select-Object -First 1
if ($null -eq $matchedSource) {
    throw "Data source preset not found: $DataSourceName"
}
Write-Mark "3/5 data source preset found: $DataSourceName"

$resolvers = Invoke-RestMethod -Method Get -Headers $headers -Uri "$BaseUrl/api/v1/resolvers?size=100"
$matchedResolver = @($resolvers.content) | Where-Object { $_.name -eq $ResolverName -and $_.active -eq $true } | Select-Object -First 1
if ($null -eq $matchedResolver) {
    throw "Active resolver preset not found: $ResolverName"
}
Write-Mark "4/5 active resolver preset found: $ResolverName"

Write-Mark "5/5 prep endpoint checks passed; real class[] fetch/player playback should be executed by the unified live test pass"
