from __future__ import annotations

import os
from dataclasses import dataclass


def _bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _int_env(name: str, default: int, minimum: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return max(minimum, parsed)


def _float_env(name: str, default: float, minimum: float, maximum: float) -> float:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        parsed = float(value)
    except ValueError:
        return default
    return min(max(parsed, minimum), maximum)


@dataclass(frozen=True)
class ClassifierSettings:
    model_name: str = os.getenv("APP_ADULT_CLASSIFIER_MODEL_NAME", "uget/sexual_content_dection")
    model_version: str = os.getenv("APP_ADULT_CLASSIFIER_MODEL_VERSION", "uget-sexual-content-dection")
    max_length: int = _int_env("APP_ADULT_CLASSIFIER_MAX_LENGTH", 128, 16)
    batch_size: int = _int_env("APP_ADULT_CLASSIFIER_BATCH_SIZE", 16, 1)
    torch_threads: int = _int_env("APP_ADULT_CLASSIFIER_TORCH_THREADS", 1, 1)
    adult_threshold: float = _float_env("APP_ADULT_CLASSIFIER_ADULT_THRESHOLD", 0.85, 0.0, 1.0)
    suspect_threshold: float = _float_env("APP_ADULT_CLASSIFIER_SUSPECT_THRESHOLD", 0.5, 0.0, 1.0)
    local_files_only: bool = _bool_env("APP_ADULT_CLASSIFIER_LOCAL_FILES_ONLY", True)
    require_token: bool = _bool_env("APP_ADULT_CLASSIFIER_REQUIRE_TOKEN", False)
    service_token: str = os.getenv("APP_ADULT_CLASSIFIER_SERVICE_TOKEN", "")
    required_scope: str = os.getenv("APP_ADULT_CLASSIFIER_REQUIRED_SCOPE", "adult-classifier:classify")


settings = ClassifierSettings()
