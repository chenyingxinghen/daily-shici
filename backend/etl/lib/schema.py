"""`poems.db` 的 DDL —— 逐条对应 `docs/03 §2.2`。

单独成模块的理由：库表结构是服务端与 ETL 的**共同契约**，两边各自手写 SQL 迟早漂移。
这里导出 DDL 常量，`load.py` 建库与 `verify.py` 校验都用它。
"""

from __future__ import annotations

#: 建库前必须设的 PRAGMA（docs/03 §2.2）。WAL 让读并发不受限；
#: synchronous=NORMAL 在 WAL 下是常见的安全/性能平衡点。
PRAGMAS = (
    "PRAGMA journal_mode = WAL",
    "PRAGMA foreign_keys = ON",
    "PRAGMA synchronous = NORMAL",
    "PRAGMA cache_size = -65536",  # 64 MB 页缓存（负值 = KB）
)

#: 表、索引、FTS 的建表语句，按依赖顺序排列。
DDL: tuple[str, ...] = (
    # ---- 朝代：双粒度（细分 dynasty + 大期 period），order 递增时 period 单调 ----
    """
    CREATE TABLE dynasty (
      dynasty_id      INTEGER PRIMARY KEY,
      name            TEXT NOT NULL UNIQUE,
      period          TEXT NOT NULL,
      period_order    INTEGER NOT NULL,
      dynasty_order   INTEGER NOT NULL,
      is_public_domain INTEGER NOT NULL
    )
    """,
    """
    CREATE INDEX idx_dynasty_period ON dynasty(period_order, dynasty_order)
    """,
    # ---- 作者：按归一化名字去重 ----
    """
    CREATE TABLE author (
      author_id   INTEGER PRIMARY KEY,
      name        TEXT NOT NULL,
      dynasty_id  INTEGER NOT NULL REFERENCES dynasty(dynasty_id),
      poem_count  INTEGER NOT NULL DEFAULT 0
    )
    """,
    "CREATE INDEX idx_author_name ON author(name)",
    # ---- 诗词主体 ----
    """
    CREATE TABLE poem (
      poem_id     INTEGER PRIMARY KEY,
      source_id   TEXT NOT NULL UNIQUE,
      title       TEXT NOT NULL,
      content     TEXT NOT NULL,
      author_id   INTEGER NOT NULL REFERENCES author(author_id),
      dynasty_id  INTEGER NOT NULL REFERENCES dynasty(dynasty_id),
      genre       TEXT NOT NULL,
      form        TEXT,
      cipai       TEXT,
      line_count  INTEGER NOT NULL,
      char_count  INTEGER NOT NULL,
      is_public_domain INTEGER NOT NULL,
      weight      INTEGER NOT NULL,
      excerpt     TEXT NOT NULL,
      pack_id     TEXT NOT NULL
    )
    """,
    # 列表默认排序 weight DESC, poem_id ASC —— 索引必须与之逐字同向，否则索引失效
    "CREATE INDEX idx_poem_weight  ON poem(weight DESC, poem_id ASC)",
    "CREATE INDEX idx_poem_dynasty ON poem(dynasty_id, weight DESC, poem_id ASC)",
    "CREATE INDEX idx_poem_genre   ON poem(genre, weight DESC, poem_id ASC)",
    "CREATE INDEX idx_poem_cipai   ON poem(cipai, weight DESC, poem_id ASC) WHERE cipai IS NOT NULL",
    "CREATE INDEX idx_poem_author  ON poem(author_id, weight DESC, poem_id ASC)",
    "CREATE INDEX idx_poem_form    ON poem(form) WHERE form IS NOT NULL",
    "CREATE INDEX idx_poem_pack    ON poem(pack_id)",
    # ---- 合集 ----
    """
    CREATE TABLE collection (
      collection_id INTEGER PRIMARY KEY,
      slug        TEXT NOT NULL UNIQUE,
      name        TEXT NOT NULL,
      pack_id     TEXT,
      builtin     INTEGER NOT NULL DEFAULT 0,
      sort_order  INTEGER NOT NULL
    )
    """,
    """
    CREATE TABLE collection_member (
      collection_id INTEGER NOT NULL REFERENCES collection(collection_id),
      poem_id       INTEGER NOT NULL REFERENCES poem(poem_id),
      PRIMARY KEY (collection_id, poem_id)
    ) WITHOUT ROWID
    """,
    "CREATE INDEX idx_cm_poem ON collection_member(poem_id)",
    # ---- 数据包 ----
    """
    CREATE TABLE pack (
      pack_id     TEXT PRIMARY KEY,
      name        TEXT NOT NULL,
      tier        TEXT NOT NULL,
      version     INTEGER NOT NULL,
      poem_count  INTEGER NOT NULL,
      bytes_raw   INTEGER NOT NULL,
      bytes_gzip  INTEGER NOT NULL,
      sha256      TEXT NOT NULL,
      min_poem_id INTEGER NOT NULL,
      max_poem_id INTEGER NOT NULL,
      builtin     INTEGER NOT NULL DEFAULT 0
    )
    """,
    # ---- 每日一诗留档 ----
    """
    CREATE TABLE daily_pick (
      date        TEXT PRIMARY KEY,
      poem_id     INTEGER NOT NULL REFERENCES poem(poem_id),
      pool_size   INTEGER NOT NULL,
      is_manual   INTEGER NOT NULL DEFAULT 0,
      created_at  TEXT NOT NULL
    )
    """,
    # ---- 筛选器计数缓存 ----
    """
    CREATE TABLE facets_cache (
      id           INTEGER PRIMARY KEY CHECK (id = 1),
      payload      TEXT NOT NULL,
      generated_at TEXT NOT NULL
    )
    """,
    # ---- FTS5 全文索引 ----
    # content='' 为**无内容表**：只存倒排索引，不复制正文，省约 280 MB（docs/03 §4.2）。
    # 代价是不能用 snippet()/highlight()，而契约本就要字符区间、本来就得自算，无损。
    # 注意：无内容表不支持 UPDATE/DELETE —— 本库每次 ETL 整体重建，故无影响。
    """
    CREATE VIRTUAL TABLE poem_fts USING fts5(
      title_tok,
      author_tok,
      content_tok,
      content='',
      tokenize='unicode61'
    )
    """,
)


def create_schema(connection) -> None:
    for statement in DDL:
        connection.execute(statement)
    connection.commit()
