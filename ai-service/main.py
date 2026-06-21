"""
TaskForge AI Service — FastAPI entry point.

Phase 0: Skeleton with health check.
Phase 6: Routes for /embed and /search will be added here.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(
    title="TaskForge AI Service",
    description="Semantic search, embeddings, and AI-powered features for TaskForge",
    version="0.1.0",
)

# Allow calls from the Spring Boot backend container
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],   # Tighten in production to backend service origin
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health", tags=["observability"])
async def health_check() -> dict:
    """Liveness probe — returns 200 when the service is running."""
    return {"status": "ok", "service": "taskforge-ai-service"}


@app.get("/", include_in_schema=False)
async def root() -> dict:
    return {"message": "TaskForge AI Service — see /docs for API reference"}
