#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-registry.mnnu.eu.org/ircs}"
REGISTRY_HOST="${REGISTRY%%/*}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"
TARGET_OS="${TARGET_OS:-linux}"
TARGET_PLATFORMS="${TARGET_PLATFORMS:-${TARGET_OS}/${TARGET_ARCH}}"
BASE_IMAGE_SOURCE="${BASE_IMAGE_SOURCE:-mirror.gcr.io/library/eclipse-temurin:25-jre-alpine}"
BASE_IMAGE_FALLBACK="${BASE_IMAGE_FALLBACK:-public.ecr.aws/docker/library/eclipse-temurin:25-jre-alpine}"
BASE_IMAGE_TARGET="${BASE_IMAGE_TARGET:-${REGISTRY}/base/eclipse-temurin:25-jre-alpine}"

if [[ -z "${REGISTRY_USERNAME:-}" || -z "${REGISTRY_PASSWORD:-}" ]]; then
  echo "REGISTRY_USERNAME and REGISTRY_PASSWORD are required to sync the Jib base image." >&2
  exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker CLI is required to sync the Jib base image." >&2
  exit 2
fi

echo "${REGISTRY_PASSWORD}" | docker login "${REGISTRY_HOST}" -u "${REGISTRY_USERNAME}" --password-stdin >/dev/null
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
  --tag "${BASE_IMAGE_TARGET}" \
  --provenance=false \
  --push \
  "${tmp_context}"
echo "Synced Jib base image to ${BASE_IMAGE_TARGET}"
