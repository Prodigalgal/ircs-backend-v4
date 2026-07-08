FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    TRANSFORMERS_CACHE=/models \
    APP_ADULT_CLASSIFIER_LOCAL_FILES_ONLY=true

WORKDIR /app

COPY services/ircs-adult-classifier-service/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY services/ircs-adult-classifier-service/app /app/adult_classifier
RUN python -m adult_classifier.download_model

EXPOSE 8080

CMD ["uvicorn", "adult_classifier.main:app", "--host", "0.0.0.0", "--port", "8080"]
