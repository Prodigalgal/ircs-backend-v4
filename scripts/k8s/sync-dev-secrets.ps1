param(
    [string]$SourceNamespace = "ircs-system",
    [string]$TargetNamespace = "ircs-dev",
    [string]$SourceSecret = "ircs-backend-secrets",
    [string]$TargetSecret = "ircs-dev-secrets",
    [string[]]$Keys = @(
        "DB_PASSWORD",
        "VALKEY_PASSWORD",
        "RABBITMQ_PASSWORD",
        "ELASTICSEARCH_PASSWORD",
        "ADMIN_PASSWORD",
        "KIBANA_PASSWORD"
    )
)

$ErrorActionPreference = "Stop"

$sourceSecretJson = kubectl get secret $SourceSecret -n $SourceNamespace -o json | ConvertFrom-Json
$dataLines = New-Object System.Collections.Generic.List[string]

foreach ($key in $Keys) {
    $encodedValue = $sourceSecretJson.data.$key
    if ($encodedValue) {
        $dataLines.Add("  ${key}: $encodedValue")
    } else {
        Write-Warning "$key not found in $SourceNamespace/$SourceSecret; skipping"
    }
}

if ($dataLines.Count -eq 0) {
    throw "No requested secret keys found in $SourceNamespace/$SourceSecret"
}

$manifest = @(
    "apiVersion: v1",
    "kind: Secret",
    "metadata:",
    "  name: $TargetSecret",
    "  namespace: $TargetNamespace",
    "  labels:",
    "    app.kubernetes.io/part-of: ircs",
    "    environment: dev",
    "type: Opaque",
    "data:"
) + $dataLines

$manifest -join "`n" | kubectl apply -f -

kubectl get secret $TargetSecret -n $TargetNamespace
