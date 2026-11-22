from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from .db.models import Base
from .db.session import engine
from .routes import router

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("locust-service starting — creating DB tables")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    log.info("locust-service ready")
    yield
    log.info("locust-service shutting down")
    await engine.dispose()


app = FastAPI(title="Locust Service", lifespan=lifespan)
app.include_router(router)
