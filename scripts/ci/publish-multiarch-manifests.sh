#!/usr/bin/env bash
set -euo pipefail

ARTIFACT_DIR="${ARTIFACT_DIR:-build-image-manifests}"
IMAGE_ARCHES="${IMAGE_ARCHES:-amd64 arm64}"
TAG="${IMAGE_TAG:-sha-$(git rev-parse --short=12 HEAD)}"
FINAL_MANIFEST="${FINAL_MANIFEST:-build-image-manifest.txt}"

metadata_keys=(
  registry
  push_registry
  tag
  target_platforms
  target_os
  target_arch
  jib_base_image
  scope
  image_mode
  modules
)

is_metadata_key() {
  local key="$1"
  local metadata_key
  for metadata_key in "${metadata_keys[@]}"; do
    [[ "${key}" == "${metadata_key}" ]] && return 0
  done
  return 1
}

first_arch=""
for arch in ${IMAGE_ARCHES}; do
  manifest="${ARTIFACT_DIR}/build-image-manifest-${arch}/build-image-manifest.txt"
  if [[ ! -f "${manifest}" ]]; then
    echo "Missing image manifest for ${arch}: ${manifest}" >&2
    exit 2
  fi
  if [[ -z "${first_arch}" ]]; then
    first_arch="${arch}"
  fi
done

declare -A final_images=()
declare -A module_order=()
module_count=0

first_manifest="${ARTIFACT_DIR}/build-image-manifest-${first_arch}/build-image-manifest.txt"
while IFS='=' read -r module image; do
  module="${module%$'\r'}"
  image="${image%$'\r'}"
  [[ -z "${module}" || -z "${image}" ]] && continue
  is_metadata_key "${module}" && continue
  if [[ "${image}" != *"-${first_arch}" ]]; then
    echo "Expected ${first_arch} image tag to end with -${first_arch}: ${image}" >&2
    exit 3
  fi
  final_image="${image%-${first_arch}}"
  final_images["${module}"]="${final_image}"
  module_order["${module_count}"]="${module}"
  module_count=$((module_count + 1))
done < "${first_manifest}"

{
  echo "registry=${REGISTRY:-docker.io/speedproxy}"
  echo "push_registry=${PUSH_REGISTRY:-${REGISTRY:-docker.io/speedproxy}}"
  echo "tag=${TAG}"
  echo "target_platforms=$(printf 'linux/%s,' ${IMAGE_ARCHES} | sed 's/,$//')"
  echo "scope=multiarch"
  echo "image_mode=multiarch"
  echo "modules=${module_count}"
} > "${FINAL_MANIFEST}"

if [[ "${module_count}" -eq 0 ]]; then
  echo "No image modules were built. Nothing to publish."
  exit 0
fi

for ((i = 0; i < module_count; i++)); do
  module="${module_order[$i]}"
  final_image="${final_images[$module]}"
  refs=()
  for arch in ${IMAGE_ARCHES}; do
    arch_manifest="${ARTIFACT_DIR}/build-image-manifest-${arch}/build-image-manifest.txt"
    arch_ref="$(awk -F= -v key="${module}" '$1 == key { print $2 }' "${arch_manifest}" | tr -d '\r')"
    if [[ -z "${arch_ref}" ]]; then
      echo "Module ${module} missing from ${arch} manifest." >&2
      exit 4
    fi
    expected="${final_image}-${arch}"
    if [[ "${arch_ref}" != "${expected}" ]]; then
      echo "Unexpected ${arch} image for ${module}: expected ${expected}, got ${arch_ref}" >&2
      exit 5
    fi
    refs+=("${arch_ref}")
  done

  echo "Publishing multi-arch manifest: ${final_image}"
  docker buildx imagetools create --tag "${final_image}" "${refs[@]}"
  echo "${module}=${final_image}" >> "${FINAL_MANIFEST}"
done

echo "Multi-arch image manifest written to ${FINAL_MANIFEST}"
