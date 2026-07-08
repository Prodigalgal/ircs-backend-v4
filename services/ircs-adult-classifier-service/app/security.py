from __future__ import annotations

import hmac

from fastapi import Header, HTTPException

from .settings import settings


def assert_internal_access(
    x_ircs_service_id: str | None = Header(default=None, alias="X-IRCS-SERVICE-ID"),
    x_ircs_service_token: str | None = Header(default=None, alias="X-IRCS-SERVICE-TOKEN"),
    x_ircs_service_scopes: str | None = Header(default=None, alias="X-IRCS-SERVICE-SCOPES"),
) -> None:
    if not settings.require_token and not settings.service_token:
        return
    if not settings.service_token:
        raise HTTPException(status_code=503, detail="Adult classifier token is not configured")
    if not x_ircs_service_id or not x_ircs_service_token:
        raise HTTPException(status_code=401, detail="Invalid internal service identity")
    if not hmac.compare_digest(settings.service_token, x_ircs_service_token):
        raise HTTPException(status_code=401, detail="Invalid internal service identity")
    if settings.required_scope and not _has_scope(x_ircs_service_scopes, settings.required_scope):
        raise HTTPException(status_code=401, detail="Internal service scope is missing")


def _has_scope(scopes: str | None, required_scope: str) -> bool:
    if not required_scope:
        return True
    if not scopes:
        return False
    return any(scope == required_scope or scope == "*" for scope in scopes.replace(",", " ").split())
