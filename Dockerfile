FROM python:3.12-slim

WORKDIR /app
COPY server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server/ ./server/
WORKDIR /app/server
ENV PORT=43123
CMD ["python", "app.py"]
