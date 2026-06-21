# ─── Stage 1: Build / dependency install ─────────────────────────────────────
FROM python:3.11-slim AS builder
WORKDIR /app

RUN pip install --no-cache-dir build

COPY pyproject.toml .
RUN pip install --no-cache-dir --prefix=/install .

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM python:3.11-slim AS runtime
WORKDIR /app

# Non-root user
RUN addgroup --system taskforge && adduser --system --ingroup taskforge taskforge
USER taskforge

COPY --from=builder /install /usr/local
COPY . .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
