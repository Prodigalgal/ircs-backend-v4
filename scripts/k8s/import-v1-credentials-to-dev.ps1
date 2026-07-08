param(
    [string]$V1SecretsPath = "D:\WorkSpace\Project\ircs\ircs-project-v1\ircs-config\01-secrets.yaml",
    [string]$Namespace = "ircs-dev",
    [string]$PostgresPod = "postgres-0",
    [string]$Database = "ircs",
    [string]$DatabaseUser = "postgres",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"

$DefaultOpenAiBaseUrl = "https://ai.mnnu.eu.org/v1"

function Get-CredentialsJsonBlock {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "V1 secrets file not found: $Path"
    }

    $raw = Get-Content -Raw -LiteralPath $Path
    $match = [regex]::Match($raw, "credentials\.json:\s*\|\s*(?<json>[\s\S]*?)(\r?\n---|\z)")
    if (-not $match.Success) {
        throw "credentials.json block was not found in $Path"
    }

    $lines = $match.Groups["json"].Value -split "`r?`n"
    $json = ($lines | ForEach-Object { $_ -replace "^    ", "" }) -join "`n"
    return $json.Trim()
}

function ConvertTo-Hashtable {
    param($Object)

    $result = [ordered]@{}
    if ($null -eq $Object) {
        return $result
    }
    foreach ($property in $Object.PSObject.Properties) {
        $result[$property.Name] = $property.Value
    }
    return $result
}

function Normalize-OpenAiBaseUrl {
    param([string]$BaseUrl)

    $value = if ([string]::IsNullOrWhiteSpace($BaseUrl)) { $DefaultOpenAiBaseUrl } else { $BaseUrl.Trim() }
    while ($value.EndsWith("/")) {
        $value = $value.Substring(0, $value.Length - 1)
    }
    $suffix = "/chat/completions"
    if ($value.EndsWith($suffix, [System.StringComparison]::OrdinalIgnoreCase)) {
        $value = $value.Substring(0, $value.Length - $suffix.Length)
    }
    return $value
}

function Get-RequiredText {
    param(
        [hashtable]$Payload,
        [string]$Key,
        [string]$Provider
    )

    if (-not $Payload.Contains($Key) -or [string]::IsNullOrWhiteSpace([string]$Payload[$Key])) {
        throw "$Provider payload is missing required key '$Key'"
    }
    return ([string]$Payload[$Key]).Trim()
}

function Get-FingerprintSource {
    param(
        [string]$Provider,
        [hashtable]$Payload
    )

    switch ($Provider) {
        "OPENAI" {
            $baseUrl = Normalize-OpenAiBaseUrl ([string]$Payload["base_url"])
            $Payload["base_url"] = $baseUrl
            return $baseUrl + "`n" + (Get-RequiredText $Payload "api_key" $Provider)
        }
        "TMDB" {
            return Get-RequiredText $Payload "api_key" $Provider
        }
        "MAIL" {
            return (Get-RequiredText $Payload "username" $Provider) + "|" + (Get-RequiredText $Payload "password" $Provider)
        }
        "R2" {
            return (Get-RequiredText $Payload "account_id" $Provider) + "|" +
                (Get-RequiredText $Payload "access_key" $Provider) + "|" +
                (Get-RequiredText $Payload "secret_key" $Provider)
        }
        default {
            throw "Unsupported provider: $Provider"
        }
    }
}

function Get-Sha256Hex {
    param([string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $sha.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function ConvertTo-ImportCredential {
    param($Item)

    $provider = ([string]$Item.provider).Trim().ToUpperInvariant()
    if ($provider -eq "GEMINI") {
        $provider = "OPENAI"
    }

    $payload = ConvertTo-Hashtable $Item.payload
    $fingerprintSource = Get-FingerprintSource $provider $payload
    $payloadJson = $payload | ConvertTo-Json -Compress -Depth 8

    [pscustomobject]@{
        Id = [guid]::NewGuid().ToString()
        Provider = $provider
        SourceProvider = ([string]$Item.provider).Trim().ToUpperInvariant()
        Name = [string]$Item.name
        PayloadJson = $payloadJson
        PayloadKeys = @($payload.Keys | Sort-Object)
        Fingerprint = Get-Sha256Hex $fingerprintSource
        Enabled = $true
        Priority = if ($null -eq $Item.priority) { 0 } else { [int]$Item.priority }
        RateLimit = if ($null -eq $Item.rateLimit) { $null } else { [int]$Item.rateLimit }
        RateLimitUnit = if ([string]::IsNullOrWhiteSpace([string]$Item.rateLimitUnit)) { $null } else { ([string]$Item.rateLimitUnit).Trim().ToUpperInvariant() }
        DayLimit = if ($null -eq $Item.dayLimit) { 0 } else { [long]$Item.dayLimit }
        MonthLimit = if ($null -eq $Item.monthLimit) { 0 } else { [long]$Item.monthLimit }
        ClassALimit = if ($null -eq $Item.classALimit) { 0 } else { [long]$Item.classALimit }
        ClassBLimit = if ($null -eq $Item.classBLimit) { 0 } else { [long]$Item.classBLimit }
        Remark = "Imported from V1 ircs-config for V3 dev live parity"
    }
}

function Sql-Literal {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) {
        return "null"
    }
    return "'" + ($Value -replace "'", "''") + "'"
}

function Sql-NumberOrNull {
    param($Value)

    if ($null -eq $Value) {
        return "null"
    }
    return [string]$Value
}

function Invoke-PostgresText {
    param([string]$Sql)

    $output = $Sql | kubectl -n $Namespace exec -i $PostgresPod -- psql -U $DatabaseUser -d $Database -v ON_ERROR_STOP=1 -q -tA
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed"
    }
    return ($output | Out-String).Trim()
}

function Get-ExistingFingerprints {
    $text = Invoke-PostgresText "select fingerprint from sys_credentials where fingerprint is not null;"
    $set = New-Object "System.Collections.Generic.HashSet[string]"
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        foreach ($line in ($text -split "`r?`n")) {
            $trimmed = $line.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
                [void]$set.Add($trimmed)
            }
        }
    }
    return ,$set
}

function Get-CredentialSummaryText {
    Invoke-PostgresText @"
select provider || ' total=' || count(*) || ' enabled=' || count(*) filter (where enabled)
  from sys_credentials
 group by provider
 order by provider;
"@
}

function New-InsertSql {
    param($Credentials)

    $statements = New-Object System.Collections.Generic.List[string]
    $statements.Add("begin;")
    foreach ($credential in $Credentials) {
        $enabled = if ($credential.Enabled) { "true" } else { "false" }
        $statements.Add(@"
insert into sys_credentials (
    id, created_at, updated_at, version, provider, name, payload, fingerprint,
    enabled, priority, rate_limit, rate_limit_unit, day_limit, month_limit,
    class_a_limit, class_b_limit, remark
)
select
    '$(($credential.Id))'::uuid,
    now(),
    now(),
    0,
    $(Sql-Literal $credential.Provider),
    $(Sql-Literal $credential.Name),
    $(Sql-Literal $credential.PayloadJson)::jsonb,
    $(Sql-Literal $credential.Fingerprint),
    $enabled,
    $(Sql-NumberOrNull $credential.Priority),
    $(Sql-NumberOrNull $credential.RateLimit),
    $(Sql-Literal $credential.RateLimitUnit),
    $(Sql-NumberOrNull $credential.DayLimit),
    $(Sql-NumberOrNull $credential.MonthLimit),
    $(Sql-NumberOrNull $credential.ClassALimit),
    $(Sql-NumberOrNull $credential.ClassBLimit),
    $(Sql-Literal $credential.Remark)
where not exists (
    select 1 from sys_credentials where fingerprint = $(Sql-Literal $credential.Fingerprint)
);
"@)
    }
    $statements.Add("commit;")
    return ($statements -join "`n")
}

$json = Get-CredentialsJsonBlock -Path $V1SecretsPath
$sourceItems = @($json | ConvertFrom-Json)
if ($sourceItems.Count -eq 1 -and $sourceItems[0] -is [array]) {
    $sourceItems = @($sourceItems[0])
}

$credentials = @(
    foreach ($sourceItem in $sourceItems) {
        ConvertTo-ImportCredential $sourceItem
    }
)

if ($credentials.Count -eq 0) {
    throw "No credentials found in V1 source"
}

Write-Host "MARK 1/4 V1 credential source structure is parsed without printing secret values"
$credentials |
    Group-Object Provider |
    Sort-Object Name |
    ForEach-Object {
        $keySets = $_.Group | ForEach-Object { ($_.PayloadKeys | Sort-Object) -join "," } | Sort-Object -Unique
        [pscustomobject]@{
            provider = $_.Name
            count = $_.Count
            payloadKeySets = ($keySets -join " | ")
        }
    } |
    Format-Table -AutoSize

$existingFingerprints = Get-ExistingFingerprints
$currentTotal = Invoke-PostgresText "select count(*) from sys_credentials;"
Write-Host "MARK 2/4 V3 sys_credentials state is confirmed before import"
Write-Host "current sys_credentials total: $currentTotal"

$planned = @($credentials | Where-Object { -not $existingFingerprints.Contains($_.Fingerprint) })
Write-Host "MARK 3/4 import script dry-run reports provider counts and planned inserts"
$planned |
    Group-Object Provider |
    Sort-Object Name |
    ForEach-Object {
        [pscustomobject]@{
            provider = $_.Name
            plannedInserts = $_.Count
        }
    } |
    Format-Table -AutoSize

if (-not $Apply) {
    Write-Host "Dry-run only. Rerun with -Apply to import credentials."
    return
}

if ($planned.Count -gt 0) {
    $sql = New-InsertSql -Credentials $planned
    Invoke-PostgresText $sql | Out-Null
}

Write-Host "MARK 4/4 apply mode imports credentials idempotently and leaves no plaintext in docs"
Write-Host "post-import provider summary:"
$summary = Get-CredentialSummaryText
if ([string]::IsNullOrWhiteSpace($summary)) {
    Write-Host "(empty)"
} else {
    Write-Host $summary
}
