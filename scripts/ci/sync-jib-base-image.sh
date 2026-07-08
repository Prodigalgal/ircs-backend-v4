#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-docker.io/speedproxy}"
PUSH_REGISTRY="${PUSH_REGISTRY:-${REGISTRY}}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"
TARGET_OS="${TARGET_OS:-linux}"
TARGET_PLATFORMS="${TARGET_PLATFORMS:-${TARGET_OS}/${TARGET_ARCH}}"
BASE_IMAGE_SOURCE="${BASE_IMAGE_SOURCE:-eclipse-temurin:25-jre-alpine}"
BASE_IMAGE_FALLBACK="${BASE_IMAGE_FALLBACK:-public.ecr.aws/docker/library/eclipse-temurin:25-jre-alpine}"
BASE_IMAGE_TARGET="${BASE_IMAGE_TARGET:-${PUSH_REGISTRY}/base/eclipse-temurin:25-jre-alpine}"
BASE_IMAGE_TARGET_SCHEME="${BASE_IMAGE_TARGET_SCHEME:-https}"

registry_image_exists() {
  local image_ref="$1"
  local scheme="$2"
  local host="${image_ref%%/*}"
  local image_path="${image_ref#*/}"
  local tag="${image_path##*:}"
  local repo="${image_path%:*}"
  if [[ "${repo}" == "${image_path}" ]]; then
    tag="latest"
    repo="${image_path}"
  fi

  curl -fsS \
    -u "${REGISTRY_USERNAME}:${REGISTRY_PASSWORD}" \
    -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json" \
    "${scheme}://${host}/v2/${repo}/manifests/${tag}" >/dev/null
}

if [[ -z "${REGISTRY_USERNAME:-}" || -z "${REGISTRY_PASSWORD:-}" ]]; then
  echo "REGISTRY_USERNAME and REGISTRY_PASSWORD are required to sync the Jib base image." >&2
  exit 2
fi

if command -v curl >/dev/null 2>&1 && registry_image_exists "${BASE_IMAGE_TARGET}" "${BASE_IMAGE_TARGET_SCHEME}"; then
  echo "Jib base image already exists at ${BASE_IMAGE_TARGET}; skipping sync."
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker CLI is required to sync the Jib base image." >&2
  exit 2
fi

BASE_IMAGE_TARGET_HOST="${BASE_IMAGE_TARGET%%/*}"
echo "${REGISTRY_PASSWORD}" | docker login "${BASE_IMAGE_TARGET_HOST}" -u "${REGISTRY_USERNAME}" --password-stdin >/dev/null
docker buildx inspect ircs-builder >/dev/null 2>&1 || docker buildx create --name ircs-builder --use >/dev/null
docker buildx use ircs-builder
docker buildx inspect --bootstrap >/dev/null

selected_source=""
for source in "${BASE_IMAGE_SOURCE}" "${BASE_IMAGE_FALLBACK}"; do
  [[ -z "${source}" ]] && continue
  echo "Checking Jib base image ${source} for ${TARGET_PLATFORMS}"
  if docker buildx imagetools inspect "${source}" >/dev/null 2>&1; then
    selected_source="${source}"
    break
  fi
done

if [[ -z "${selected_source}" ]]; then
  echo "Unable to pull any configured Jib base image source." >&2
  exit 3
fi

echo "Syncing Jib base image ${selected_source} as ${BASE_IMAGE_TARGET} for ${TARGET_PLATFORMS}"
tmp_context="$(mktemp -d)"
trap 'rm -rf "${tmp_context}"' EXIT
cat > "${tmp_context}/Dockerfile" <<'DOCKERFILE'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
DOCKERFILE
docker buildx build \
  --platform "${TARGET_PLATFORMS}" \
  --build-arg "BASE_IMAGE=${selected_source}" \
  --provenance=false \
  --output "type=image,name=${BASE_IMAGE_TARGET},push=true,registry.insecure=$([[ "${BASE_IMAGE_TARGET_SCHEME}" == "http" ]] && echo true || echo false)" \
  "${tmp_context}"
echo "Synced Jib base image to ${BASE_IMAGE_TARGET}"
