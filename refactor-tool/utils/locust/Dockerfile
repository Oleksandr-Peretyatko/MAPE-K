FROM python:3.11-slim

WORKDIR /app

# Install dependencies first (layer cached unless requirements change)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy source — scenario files and the api package
COPY . .

EXPOSE 8100

CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8100", "--log-level", "info"]
