"""注疏库（`annotations.db`）—— **独立于 `poems.db` 的第二个 SQLite 文件**。

存的是**从网络获取的、真人编辑的注疏**（方式二），不是模型生成的内容。
设计见 `docs/06`。

## 两条设计线

**① 为什么不跟诗词放同一个库。** `etl/lib/pipeline.py:329` 的 `stage_load`
第一件事就是 `DB_PATH.unlink()` —— 每次重跑 ETL 都会把 `poems.db` 整个删掉重建。
注疏虽然可以从源数据重导（不像生成内容那样不可复现），但两个库的生命周期本来就不同：
`poems.db` 是 ETL 构建产物（只读），这张库是外部数据导入的沉淀（增量、可追溯来源）。
分开还让注疏可以单独备份与迁移，代价是没有外键 —— 这是有意的取舍。

**② 为什么每个字段都带来源与许可。** 这张库装的是**别人的编辑成果**，
不是我们算出来的。`source` / `source_url` / `license` 不是装饰性元数据，
它们回答「这条内容从哪来、能不能商用」，是将来合规审查时的唯一凭据。
**新增数据源时必须填，不要留空。**

## 与旧版（LLM 生成）的差别

旧 schema 有 `status`/`origin`/`provider`/`model`/`prompt_ver`/`attempts`/`last_error`
与一张 `annotation_queue` —— 那套是为「按需生成 + 入队」设计的。
需求纠正后方式二不再生成（方式一是流式问答，也不落这张库），故全部移除：
**这张表现在只有「有」与「没有」两种状态**（有行 = 有注疏），
不再需要表达「生成中 / 失败 / 待重试」。
"""

from __future__ import annotations

import json
import sqlite3
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Iterator

from .config import settings

_PRAGMAS = (
    "PRAGMA journal_mode = WAL",
    "PRAGMA synchronous = NORMAL",
    "PRAGMA foreign_keys = OFF",  # 跨库无外键可依赖，显式关掉以免误以为有约束
    "PRAGMA cache_size = -16384",
)

#: 表结构。`IF NOT EXISTS` —— 这张库不进 ETL 管线，
#: 由服务端启动或导入脚本自动创建，不需要任何人手工跑迁移。
DDL: tuple[str, ...] = (
    """
    CREATE TABLE IF NOT EXISTS poem_annotation (
      poem_id        INTEGER PRIMARY KEY,
      translation    TEXT,                -- 译文
      annotation     TEXT,                -- 注释
      appreciation   TEXT,                -- 赏析（含赏析/鉴赏/简析/评析等，多条已合并）
      background     TEXT,                -- 创作背景
      citation       TEXT,                -- 文献出处，多条以「；」分隔
      source         TEXT NOT NULL,       -- 数据源标识，如 'gushiwen'
      source_url     TEXT,                -- 可达的原站入口
      license        TEXT NOT NULL,       -- 许可标识，如 'CC0-1.0'
      -- 上游原始标题/作者。**不是冗余**：匹配是靠归一化后的名字做的，
      -- 出问题时只有存了原值才能回溯「到底配错了哪一首」。
      upstream_title  TEXT,
      upstream_author TEXT,
      -- 匹配证据。`match_kind` 是 'exact'（题名完全一致）或 'stem'（按词牌主干 +
      -- 正文相似度消歧）；`match_score` 是正文二元组 Dice 相似度。
      -- **存下来是为了可审计**：将来发现某条挂错了，能按 kind/score 批量筛出来复核，
      -- 不必重跑整个导入。
      match_kind     TEXT,
      match_score    REAL,
      imported_at    TEXT NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_ann_source ON poem_annotation(source)",
    "CREATE INDEX IF NOT EXISTS idx_ann_match_kind ON poem_annotation(match_kind)",
    # 只装译文没装赏析的比例不低，按「有什么」筛选时用得上
    "CREATE INDEX IF NOT EXISTS idx_ann_has_appreciation"
    " ON poem_annotation(poem_id) WHERE appreciation IS NOT NULL",
)

_READ_LOCAL = threading.local()
_WRITE_CONNECTION: sqlite3.Connection | None = None
_WRITE_LOCK = threading.Lock()


def _configure(connection: sqlite3.Connection) -> None:
    connection.row_factory = sqlite3.Row


def now_iso() -> str:
    """统一时间戳口径：UTC ISO 8601，与契约一致（`docs/02 §1.1`）。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ensure_schema() -> None:
    path = settings.annotations_db_path
    path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(path)
    try:
        for pragma in _PRAGMAS:
            connection.execute(pragma)
        for statement in DDL:
            connection.execute(statement)
        connection.commit()
    finally:
        connection.close()


def _reader() -> sqlite3.Connection:
    connection = getattr(_READ_LOCAL, "connection", None)
    if connection is None:
        connection = sqlite3.connect(settings.annotations_db_path, timeout=10.0)
        _configure(connection)
        _READ_LOCAL.connection = connection
    return connection


def _writer() -> sqlite3.Connection:
    global _WRITE_CONNECTION
    with _WRITE_LOCK:
        if _WRITE_CONNECTION is None:
            settings.annotations_db_path.parent.mkdir(parents=True, exist_ok=True)
            _WRITE_CONNECTION = sqlite3.connect(
                settings.annotations_db_path, timeout=30.0, check_same_thread=False
            )
            _configure(_WRITE_CONNECTION)
            for pragma in _PRAGMAS:
                _WRITE_CONNECTION.execute(pragma)
        return _WRITE_CONNECTION


@contextmanager
def read_cursor() -> Iterator[sqlite3.Cursor]:
    yield _reader().cursor()


@contextmanager
def write_cursor() -> Iterator[sqlite3.Cursor]:
    connection = _writer()
    cursor = connection.cursor()
    try:
        yield cursor
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        cursor.close()


# ---------------------------------------------------------------------------
# 读
# ---------------------------------------------------------------------------


def fetch(poem_id: int) -> sqlite3.Row | None:
    with read_cursor() as cursor:
        return cursor.execute(
            "SELECT * FROM poem_annotation WHERE poem_id = ?", (poem_id,)
        ).fetchone()


def fetch_all(poem_ids: list[int]) -> dict[int, sqlite3.Row]:
    if not poem_ids:
        return {}
    placeholders = ",".join("?" * len(poem_ids))
    with read_cursor() as cursor:
        rows = cursor.execute(
            f"SELECT * FROM poem_annotation WHERE poem_id IN ({placeholders})", poem_ids
        ).fetchall()
    return {row["poem_id"]: row for row in rows}


def stats() -> dict:
    with read_cursor() as cursor:
        def scalar(sql: str) -> int:
            return cursor.execute(sql).fetchone()[0]

        return {
            "total": scalar("SELECT COUNT(*) FROM poem_annotation"),
            "with_translation": scalar(
                "SELECT COUNT(*) FROM poem_annotation WHERE translation IS NOT NULL"),
            "with_annotation": scalar(
                "SELECT COUNT(*) FROM poem_annotation WHERE annotation IS NOT NULL"),
            "with_appreciation": scalar(
                "SELECT COUNT(*) FROM poem_annotation WHERE appreciation IS NOT NULL"),
            "with_background": scalar(
                "SELECT COUNT(*) FROM poem_annotation WHERE background IS NOT NULL"),
            "with_citation": scalar(
                "SELECT COUNT(*) FROM poem_annotation WHERE citation IS NOT NULL"),
            "by_source": {
                row["source"]: row["n"]
                for row in cursor.execute(
                    "SELECT source, COUNT(*) n FROM poem_annotation GROUP BY source")
            },
            "by_match_kind": {
                row["match_kind"]: row["n"]
                for row in cursor.execute(
                    "SELECT match_kind, COUNT(*) n FROM poem_annotation GROUP BY match_kind")
            },
            "latest": scalar("SELECT COALESCE(MAX(imported_at),'') FROM poem_annotation"),
        }


def health() -> dict:
    info: dict = {"db": "unknown", "error": None, "path": str(settings.annotations_db_path)}
    try:
        info.update(stats())
        info["db"] = "ok"
    except Exception as exc:  # noqa: BLE001 - 健康检查要吞异常并如实上报
        info["db"] = "error"
        info["error"] = f"{type(exc).__name__}: {exc}"
    return info


# ---------------------------------------------------------------------------
# 写（只由导入脚本调用）
# ---------------------------------------------------------------------------


def upsert_many(rows: list[dict]) -> int:
    """批量导入。**幂等 upsert** —— 重跑导入会覆盖而不是报主键冲突。

    每条必须带 `source` 与 `license`（NOT NULL）；缺了会在库里直接报错而不是
    悄悄写进一条无出处的记录 —— 这是有意的：来源不明的注疏不该进库。
    """
    if not rows:
        return 0
    stamp = now_iso()
    with write_cursor() as cursor:
        cursor.executemany(
            """
            INSERT INTO poem_annotation
              (poem_id, translation, annotation, appreciation, background, citation,
               source, source_url, license, upstream_title, upstream_author,
               match_kind, match_score, imported_at)
            VALUES (:poem_id, :translation, :annotation, :appreciation, :background, :citation,
                    :source, :source_url, :license, :upstream_title, :upstream_author,
                    :match_kind, :match_score, :imported_at)
            ON CONFLICT(poem_id) DO UPDATE SET
              translation     = excluded.translation,
              annotation      = excluded.annotation,
              appreciation    = excluded.appreciation,
              background      = excluded.background,
              citation        = excluded.citation,
              source          = excluded.source,
              source_url      = excluded.source_url,
              license         = excluded.license,
              upstream_title  = excluded.upstream_title,
              upstream_author = excluded.upstream_author,
              match_kind      = excluded.match_kind,
              match_score     = excluded.match_score,
              imported_at     = excluded.imported_at
            """,
            [{**row, "imported_at": stamp} for row in rows],
        )
    return len(rows)


# ---------------------------------------------------------------------------
# 供 `services/annotate.py` 渲染
# ---------------------------------------------------------------------------


def render(poem_id: int) -> dict | None:
    """读一条并转成响应所需的结构。没有则返回 `None`。"""
    row = fetch(poem_id)
    if row is None:
        return None
    sources = []
    if row["source_url"]:
        sources.append({"title": f"古诗文网（{row['license']}）", "url": row["source_url"]})
    return {
        "annotation": row["annotation"],
        "translation": row["translation"],
        "appreciation": row["appreciation"],
        "background": row["background"],
        "citation": row["citation"],
        "sources": sources,
        "source": row["source"],
        # 许可一并回传：客户端要把它显示在来源旁边（「古诗文网（CC0-1.0）」），
        # 这是别人编辑成果的出处声明，不是可选装饰。
        "license": row["license"],
    }


def export_json(poem_id: int) -> str:
    """调试用：把一条原样打印成 JSON。"""
    row = fetch(poem_id)
    return json.dumps(dict(row) if row else None, ensure_ascii=False, indent=2)
