from __future__ import annotations

from contextlib import asynccontextmanager
from uuid import UUID

from fastapi import Depends, FastAPI, Request

from .classifier import AdultTextClassifier
from .schemas import ModelBatchResponse, ModelTextItem
from .security import assert_internal_access
from .settings import settings

classifier: AdultTextClassifier | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global classifier
    classifier = AdultTextClassifier(settings)
    yield


app = FastAPI(title="IRCS Adult Classifier Service", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, object]:
    return {"status": "UP", "model": settings.model_name, "ready": classifier is not None}


@app.get("/actuator/health/liveness")
def liveness() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/actuator/health/readiness")
def readiness() -> dict[str, object]:
    return health()


@app.post(
    "/internal/v1/adult-classifier:classify",
    response_model=ModelBatchResponse,
    dependencies=[Depends(assert_internal_access)],
)
async def classify(request: Request) -> ModelBatchResponse:
    if classifier is None:
        return ModelBatchResponse(items=[])
    try:
        payload = await request.json()
    except ValueError:
        payload = {}
    return ModelBatchResponse(items=classifier.classify(_parse_items(payload)))


def _parse_items(payload: object) -> list[ModelTextItem]:
    if not isinstance(payload, dict):
        return []
    raw_items = payload.get("items")
    if not isinstance(raw_items, list):
        return []
    items: list[ModelTextItem] = []
    for raw_item in raw_items:
        if not isinstance(raw_item, dict):
            continue
        raw_id = raw_item.get("id")
        if raw_id is None:
            continue
        try:
            item_id = UUID(str(raw_id))
        except ValueError:
            continue
        raw_text = raw_item.get("text")
        text = raw_text if isinstance(raw_text, str) else "" if raw_text is None else str(raw_text)
        items.append(ModelTextItem(id=item_id, text=text))
    return items
