#!/usr/bin/env bash
set -euo pipefail

manifest="${1:-build-image-manifest.txt}"
prod_dirs="${PROD_K8S_DIRS:-${PROD_K8S_DIR:-deploy/k8s/prod}}"

if [[ ! -f "${manifest}" ]]; then
  echo "Image manifest not found: ${manifest}" >&2
  exit 2
fi

for prod_dir in ${prod_dirs}; do
  if [[ ! -d "${prod_dir}" ]]; then
    echo "Production manifest directory not found: ${prod_dir}" >&2
    exit 2
  fi
done

updated=0
while IFS='=' read -r module image; do
  [[ -z "${module}" || -z "${image}" ]] && continue
  [[ "${module}" == registry || "${module}" == push_registry || "${module}" == tag || "${module}" == target_platforms || "${module}" == target_os || "${module}" == target_arch || "${module}" == jib_base_image || "${module}" == scope || "${module}" == image_mode || "${module}" == modules ]] && continue

  image_name="${image##*/}"
  image_name="${image_name%%:*}"
  current_ref_pattern="image: ([^[:space:]]*/)?${image_name}(@sha256:[a-f0-9]+|:[A-Za-z0-9._-]+)"
  replacement="image: ${image}"

  for prod_dir in ${prod_dirs}; do
    while IFS= read -r -d '' file; do
      if grep -Eq "${current_ref_pattern}" "${file}"; then
        sed -i -E "s#${current_ref_pattern}#${replacement}#g" "${file}"
        echo "Updated ${file}: ${image_name} -> ${image}"
        updated=1
      fi
    done < <(find "${prod_dir}" -type f \( -name '*.yaml' -o -name '*.yml' \) -print0)
  done
done < "${manifest}"

if [[ "${updated}" == "0" ]]; then
  echo "No production image references matched ${manifest}."
fi
