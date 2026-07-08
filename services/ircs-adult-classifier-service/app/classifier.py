from __future__ import annotations

from collections.abc import Iterable
from typing import Any

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

from .schemas import ModelTextItem, ModelTextResult
from .settings import ClassifierSettings


class AdultTextClassifier:
    def __init__(self, settings: ClassifierSettings) -> None:
        self._settings = settings
        torch.set_num_threads(settings.torch_threads)
        self._tokenizer = AutoTokenizer.from_pretrained(
            settings.model_name,
            local_files_only=settings.local_files_only,
        )
        self._model = AutoModelForSequenceClassification.from_pretrained(
            settings.model_name,
            local_files_only=settings.local_files_only,
        )
        self._model.eval()
        self._adult_index = self._resolve_adult_index()

    def classify(self, items: list[ModelTextItem]) -> list[ModelTextResult]:
        if not items:
            return []
        results: list[ModelTextResult] = []
        for chunk in self._chunks(items, self._settings.batch_size):
            texts = [item.text or "" for item in chunk]
            encoded = self._tokenizer(
                texts,
                padding=True,
                truncation=True,
                max_length=self._settings.max_length,
                return_tensors="pt",
            )
            with torch.inference_mode():
                logits = self._model(**encoded).logits
                probabilities = torch.sigmoid(logits).detach().cpu().tolist()
            for item, item_probabilities in zip(chunk, probabilities, strict=True):
                adult_score = self._adult_score(item_probabilities)
                label = self._label(adult_score)
                results.append(
                    ModelTextResult(
                        id=item.id,
                        adultScore=adult_score,
                        label=label,
                        raw={
                            "model": self._settings.model_name,
                            "version": self._settings.model_version,
                            "probabilities": self._probability_payload(item_probabilities),
                            "maxLength": self._settings.max_length,
                        },
                    )
                )
        return results

    def _adult_score(self, probabilities: list[float]) -> float:
        if not probabilities:
            return 0.0
        if self._adult_index < len(probabilities):
            return self._clamp(probabilities[self._adult_index])
        return self._clamp(max(probabilities))

    def _label(self, adult_score: float) -> str:
        if adult_score >= self._settings.adult_threshold:
            return "ADULT"
        if adult_score >= self._settings.suspect_threshold:
            return "SUSPECT"
        return "SAFE"

    def _resolve_adult_index(self) -> int:
        id2label: dict[int | str, str] = getattr(self._model.config, "id2label", {}) or {}
        normalized = {index: label.lower() for index, label in id2label.items()}
        for index, label in normalized.items():
            if any(token in label for token in ("sexual", "adult", "unsafe", "porn", "nsfw")):
                return int(index)
        return 1 if len(normalized) > 1 else 0

    def _probability_payload(self, probabilities: list[float]) -> dict[str, float]:
        id2label: dict[int | str, str] = getattr(self._model.config, "id2label", {}) or {}
        payload: dict[str, float] = {}
        for index, probability in enumerate(probabilities):
            label = id2label.get(index) or id2label.get(str(index)) or f"LABEL_{index}"
            payload[label] = self._clamp(probability)
        return payload

    @staticmethod
    def _chunks(items: list[ModelTextItem], size: int) -> Iterable[list[ModelTextItem]]:
        for index in range(0, len(items), size):
            yield items[index:index + size]

    @staticmethod
    def _clamp(value: Any) -> float:
        try:
            score = float(value)
        except (TypeError, ValueError):
            return 0.0
        return min(max(score, 0.0), 1.0)
