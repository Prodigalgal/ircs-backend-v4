from __future__ import annotations

from transformers import AutoModelForSequenceClassification, AutoTokenizer

from .settings import settings


def main() -> None:
    AutoTokenizer.from_pretrained(settings.model_name)
    AutoModelForSequenceClassification.from_pretrained(settings.model_name)


if __name__ == "__main__":
    main()
