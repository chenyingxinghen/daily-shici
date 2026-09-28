"""SQLite 连接管理（`docs/03 §6` 的「连接处理」）。

三条约定，都是为了让 SQLite + FastAPI 这个组合不出问题：

1. **不用 aiosqlite。** 全网只读 + 页缓存命中，同步阻塞在微秒级；
   引入 async 驱动只增加复杂度。
2. **读连接用 `threading.local()` 每线程一个。** FastAPI 的**同步**路由跑在线程池里，
   故每线程复用即可 —— 常见的坑是每请求新建连接，那是几百微秒的纯浪费。
3. **写连接只有一个**（`check_same_thread=False`），仅供每日选诗任务使用。
   SQLite 的写锁是全局的，多写连接只会互相争锁。
"""

from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from typing import Iterator

from .config import settings

_local = threading.local()
_write_connection: sqlite3.Connection | None = None
_write_lock = threading.Lock()


def _configure(connection: sqlite3.Connection) -> None:
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute(f"PRAGMA cache_size = -{settings.db_cache_kb}")
    # 只读连接无需 WAL 设置（库文件已是 WAL），query_only 防止误写
    return None


def reader() -> sqlite3.Connection:
    connection = getattr(_local, "connection", None)
    if connection is None:
        connection = sqlite3.connect(settings.db_path, timeout=10.0)
        _configure(connection)
        _local.connection = connection
    return connection


def writer() -> sqlite3.Connection:
    global _write_connection
    with _write_lock:
        if _write_connection is None:
            _write_connection = sqlite3.connect(
                settings.db_path, timeout=30.0, check_same_thread=False
            )
            _configure(_write_connection)
            _write_connection.execute("PRAGMA journal_mode = WAL")
            _write_connection.execute("PRAGMA synchronous = NORMAL")
        return _write_connection


@contextmanager
def read_cursor() -> Iterator[sqlite3.Cursor]:
    yield reader().cursor()


@contextmanager
def write_cursor() -> Iterator[sqlite3.Cursor]:
    connection = writer()
    cursor = connection.cursor()
    try:
        yield cursor
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        cursor.close()


def health() -> dict:
    """`/healthz` 用。**必须检查 `daily_pick` 最新日期**（docs/03 §7.3）：

    定时任务是本服务唯一的自动写入路径，它静默失败时 API 仍会正常返回
    （只是每日一诗不更新），不主动检查就发现不了。
    """
    info: dict = {"db": "unknown", "poem_count": 0, "daily_latest": None, "error": None}
    try:
        with read_cursor() as cursor:
            info["poem_count"] = cursor.execute("SELECT COUNT(*) FROM poem").fetchone()[0]
            row = cursor.execute("SELECT MAX(date) FROM daily_pick").fetchone()
            info["daily_latest"] = row[0] if row else None
            info["db"] = "ok"
    except Exception as exc:  # noqa: BLE001 - 健康检查要吞掉异常并如实上报
        info["db"] = "error"
        info["error"] = f"{type(exc).__name__}: {exc}"
    return info
