FROM mcr.microsoft.com/playwright/python:v1.44.0-jammy

WORKDIR /app

RUN groupadd -r trauth && useradd -r -g trauth trauth

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

RUN chown -R trauth:trauth /app
USER trauth

EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
