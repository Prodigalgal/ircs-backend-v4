#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-docker.io/speedproxy}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"
TARGET_OS="${TARGET_OS:-linux}"
TAG="${IMAGE_TAG:-sha-$(git rev-parse --short=12 HEAD)}"

if [[ -z "${REGISTRY_USERNAME:-}" || -z "${REGISTRY_PASSWORD:-}" ]]; then
  echo "REGISTRY_USERNAME and REGISTRY_PASSWORD are required." >&2
  exit 2
fi

modules=(
  "platform:ircs-migrator=ircs-migrator"
  "services:ircs-api-gateway=ircs-api-gateway"
  "services:ircs-aggregation-worker=ircs-aggregation-worker"
  "services:ircs-catalog-service=ircs-catalog-service"
  "services:ircs-config-service=ircs-config-service"
  "services:ircs-content-service=ircs-content-service"
  "services:ircs-credential-service=ircs-credential-service"
  "services:ircs-identity-service=ircs-identity-service"
  "services:ircs-ingestion-worker=ircs-ingestion-worker"
  "services:ircs-interaction-service=ircs-interaction-service"
  "services:ircs-magnet-service=ircs-magnet-service"
  "services:ircs-metadata-worker=ircs-metadata-worker"
  "services:ircs-normalization-worker=ircs-normalization-worker"
  "services:ircs-notification-worker=ircs-notification-worker"
  "services:ircs-ops-service=ircs-ops-service"
  "services:ircs-portal-service=ircs-portal-service"
  "services:ircs-scraper-service=ircs-scraper-service"
  "services:ircs-search-service=ircs-search-service"
  "services:ircs-storage-service=ircs-storage-service"
  "services:ircs-task-service=ircs-task-service"
)

echo "Building and pushing IRCS images to ${REGISTRY} with tag ${TAG} for ${TARGET_OS}/${TARGET_ARCH}"

mkdir -p build
: > build-image-manifest.txt
{
  echo "registry=${REGISTRY}"
  echo "tag=${TAG}"
  echo "target_os=${TARGET_OS}"
  echo "target_arch=${TARGET_ARCH}"
  echo "scope=all"
  echo "modules=${#modules[@]}"
} >> build-image-manifest.txt

for entry in "${modules[@]}"; do
  module="${entry%%=*}"
  image="${entry##*=}"
  target="${REGISTRY}/${image}:${TAG}"
  echo "${module}=${target}" >> build-image-manifest.txt
  echo "::group::${module} -> ${target}"
  ./gradlew --no-daemon ":${module}:jib" \
    "-PjibToImage=${target}" \
    "-PjibTargetArch=${TARGET_ARCH}" \
    "-PjibTargetOs=${TARGET_OS}" \
    "-Djib.to.auth.username=${REGISTRY_USERNAME}" \
    "-Djib.to.auth.password=${REGISTRY_PASSWORD}"
  echo "::endgroup::"
done

echo "Image manifest written to build-image-manifest.txt"
