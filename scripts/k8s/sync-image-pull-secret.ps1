param(
    [string]$TargetNamespace = "ircs-prod",
    [string]$SecretName = "dockerhub-registry-secret",
    [string]$Registry = "https://index.docker.io/v1/",
    [string]$Username = "speedproxy"
)

$ErrorActionPreference = "Stop"

if (-not $env:REGISTRY_PASSWORD) {
    throw "REGISTRY_PASSWORD must be set before creating $TargetNamespace/$SecretName"
}

kubectl create namespace $TargetNamespace --dry-run=client -o yaml | kubectl apply -f -

kubectl -n $TargetNamespace create secret docker-registry $SecretName `
    --docker-server=$Registry `
    --docker-username=$Username `
    --docker-password=$env:REGISTRY_PASSWORD `
    --dry-run=client -o yaml | kubectl apply -f -

kubectl get secret $SecretName -n $TargetNamespace
