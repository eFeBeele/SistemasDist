FROM python:3.12-slim
WORKDIR /app
COPY python/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY python/ .
ENV PYTHONUNBUFFERED=1
CMD ["python", "bot.py"]
