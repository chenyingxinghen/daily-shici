"""FastAPI 应用（`docs/03 §6` / `docs/02 §1.1`）。

- 路径前缀 `/api/v1`，**不做 v2 并行**（破坏性变更时直接升版本并强制客户端升级）。
- **单 worker**：SQLite 写锁是全局的，多 worker 只会互相争锁；
  而本项目读多写近乎零，单 worker 足够。要扩就换 PostgreSQL，不是加 worker。
- 定时任务用 APScheduler 跑在同一进程内，**不引 Celery** ——
  一个每日任务不值一套 broker。
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from apscheduler.schedulers.background import BackgroundScheduler
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError

from . import db
from .config import settings
from .errors import ApiError, api_error_handler, internal_handler, validation_handler
from .routers import annotations, ask, authors, daily, facets, packs, poems, search

logger = logging.getLogger("shici")

API_PREFIX = "/api/v1"

_scheduler: BackgroundScheduler | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _scheduler

    # 启动时清一次 facets 缓存：版权开关是启动期常量，开关变了缓存必须失效
    # （docs/03 §5.2：主动失效而不是等 TTL 过期）
    try:
        from .services import cache

        cache.invalidate()
    except Exception as exc:  # noqa: BLE001
        logger.warning("清理 facets 缓存失败：%s", exc)

    # 注疏库：建表即可（docs/06 §4）。
    # **这里没有后台 worker** —— 注疏是预先导入的真人内容，服务端只读；
    # 需求纠正后「按需生成」整条路径已移除（详见 docs/06 §1）。
    if settings.annotations_enabled:
        try:
            from . import annotation_db

            annotation_db.ensure_schema()
        except Exception as exc:  # noqa: BLE001 - 注疏是增值功能，坏了不该拖垮主服务
            logger.error("注疏库初始化失败，注疏功能不可用：%s", exc)

    if os.getenv("SHICI_DISABLE_SCHEDULER", "").lower() not in ("1", "true", "yes"):
        from .jobs.daily_pick import refill_daily_window

        _scheduler = BackgroundScheduler(timezone="Asia/Shanghai")
        # 00:05 而不是 00:00 —— 避开整点的资源竞争（docs/03 §5.1）
        _scheduler.add_job(refill_daily_window, "cron", hour=0, minute=5, id="daily_pick")
        _scheduler.start()
        logger.info("每日选诗定时任务已启动（00:05）")

    yield

    if _scheduler is not None:
        _scheduler.shutdown(wait=False)
        _scheduler = None


app = FastAPI(
    title="每日诗词 API",
    version="1.0",
    lifespan=lifespan,
    docs_url="/docs",
    openapi_url=f"{API_PREFIX}/openapi.json",
)

app.add_exception_handler(ApiError, api_error_handler)
app.add_exception_handler(RequestValidationError, validation_handler)
app.add_exception_handler(Exception, internal_handler)

for module in (daily, poems, search, facets, authors, packs, annotations, ask):
    app.include_router(module.router, prefix=API_PREFIX)

# 大文件包**不经 API 服务**（docs/03 §7.1）：生产环境由 nginx 直接服务。
# 这里只为本地联调挂一个只读的静态目录，线上应关掉或交给 nginx。
if os.getenv("SHICI_SERVE_PACKS", "1").lower() in ("1", "true", "yes"):
    from fastapi.staticfiles import StaticFiles

    if settings.packs_dir.exists():
        app.mount("/packs", StaticFiles(directory=str(settings.packs_dir)), name="packs")


@app.get("/healthz", tags=["ops"])
def healthz(request: Request):
    """健康检查。

    ⚠️ **必须包含 `daily_pick` 最新日期**（docs/03 §7.3）：定时任务是本服务唯一的
    自动写入路径，它静默失败时 API 仍会正常返回（只是每日一诗不更新），
    不主动检查就发现不了。

    同理，注疏库也是一条会静默失效的链路 —— 它空了的话 API 照常 200，
    只是所有诗都没有注疏。故这里把 `annotations` 一并暴露出来。
    """
    info = db.health()
    info["copyrighted_included"] = settings.include_copyrighted
    info["db_path"] = str(settings.db_path)
    if settings.annotations_enabled:
        try:
            from .services import annotate

            info["annotations"] = {**annotate.stats(), "enabled": True}
        except Exception as exc:  # noqa: BLE001
            info["annotations"] = {"db": "error", "enabled": True,
                                   "error": f"{type(exc).__name__}: {exc}"}
    else:
        info["annotations"] = {"enabled": False}
    return info
