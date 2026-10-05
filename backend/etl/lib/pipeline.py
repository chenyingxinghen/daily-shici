"""ETL 管线各阶段实现。

对应 `docs/01 §7.1` 的九个阶段。**阶段名与职责与文档一致**，但实现上做了两处收敛，
理由都是「可重跑」这一目标（§7.2）在不牺牲它的前提下没必要付出额外代价：

1. **中间产物只落两份**（`build/parsed.jsonl`、`build/derived.jsonl`），
   而不是每个阶段各落一份 —— 859k 首的 JSONL 每份约 300 MB，
   九份就是 2.7 GB，而真正需要「不重下 283 MB 就能重跑」的切点在
   `derived`（改分包规则/改派生字段都从这里往后跑）。
2. **不再拆成 8 个独立可执行文件**，而是本模块内 8 个同名函数 + 一个 `run_etl.py` 编排。
   每个阶段仍是纯函数式的「读入 → 产出」，可单独调用、可单测。

已同步更新 `docs/01 §7.3` 的目录结构说明。
"""

from __future__ import annotations

import gzip
import hashlib
import json

import sqlite3
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .ids import IdAllocator
from .schema import PRAGMAS, create_schema
from .text import (
    build_cipai_candidates,
    clean_content,
    count_chars,
    count_lines,
    dedup_key,
    extract_cipai,
    first_line,
    infer_form,
    normalize_author,
)

ETL_DIR = Path(__file__).resolve().parent.parent
DATA_RAW = ETL_DIR / "data_raw"
BUILD = ETL_DIR / "build"
MAPPINGS = ETL_DIR / "mappings"
ID_MAP = ETL_DIR / "id_map.jsonl"
PACKS_DIR = ETL_DIR.parent / "packs"
DB_PATH = ETL_DIR.parent / "server" / "data" / "poems.db"
#: 客户端 APK 内的资源目录。内置（L0）包直接写到这里，
#: 使「ETL 产物」与「客户端内置内容」只有一个来源，不再有一个会脱节的独立脚本。
ASSETS_DIR = ETL_DIR.parent.parent / "frontend" / "app" / "src" / "main" / "assets"

#: 大期排序。按时间线递增，用于 `facets` 的顶部 Tab。
PERIOD_ORDER = {
    "先秦": 1,
    "秦汉": 2,
    "魏晋南北朝": 3,
    "隋唐五代": 4,
    "宋辽金": 5,
    "元": 6,
    "明": 7,
    "清": 8,
    "近现代": 9,
    "当代": 10,
}

#: 清及以前为公有领域（docs/01 §5.2）。order > 16 的全部保守判为非公有领域。
PUBLIC_DOMAIN_MAX_ORDER = 16

#: 批插入大小。SQLite 单事务内 2000 行是内存与事务开销的平衡点。
BATCH = 2000


# ---------------------------------------------------------------------------
# 中间产物读写
# ---------------------------------------------------------------------------


def _write_jsonl(path: Path, rows) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            count += 1
    return count


def _read_jsonl(path: Path):
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                yield json.loads(line)


def load_mappings() -> dict:
    data = json.loads((MAPPINGS / "sources.json").read_text(encoding="utf-8"))
    data["by_file"] = {s["file"]: s for s in data["sources"]}
    data["pack_by_id"] = {p["pack_id"]: p for p in data["packs"]}
    data["dynasty_by_name"] = {d["name"]: d for d in data["dynasties"]}
    return data


# ---------------------------------------------------------------------------
# 1. parse
# ---------------------------------------------------------------------------


@dataclass
class Counts:
    """各阶段计数。最终进 `etl_report.json`（docs/01 §8 的验收口径）。"""

    per_file: dict[str, int] = field(default_factory=dict)
    rejected: dict[str, int] = field(default_factory=dict)
    excluded_files: list[str] = field(default_factory=list)
    merged: int = 0
    unique: int = 0


def stage_parse(mappings: dict) -> tuple[Path, Counts]:
    """读 `data_raw/*.json` → `build/parsed.jsonl`。

    上游格式统一为扁平 JSON 数组，每条 4 个字段（docs/01 §1.2）。
    本阶段只做「取字段 + 记来源」，不做任何清洗与派生。
    """
    counts = Counts()
    exclusions = MAPPINGS / "exclude.json"
    if exclusions.exists():
        counts.excluded_files = json.loads(exclusions.read_text(encoding="utf-8"))["files"]

    def rows():
        for source in mappings["sources"]:
            path = DATA_RAW / source["file"]
            if not path.exists():
                raise SystemExit(f"缺少原始数据 {path}（请先运行 fetch.py）")
            records = json.loads(path.read_text(encoding="utf-8"))
            counts.per_file[source["file"]] = len(records)
            for entry in records:
                yield {
                    "file": source["file"],
                    "source_id": str(entry.get("id") or "").strip(),
                    "title": entry.get("title") or "",
                    "author": entry.get("author") or "",
                    "content": entry.get("content") or "",
                }

    total = _write_jsonl(BUILD / "parsed.jsonl", rows())
    print(f"  parse: {total:,} 条原始记录，来自 {len(counts.per_file)} 个文件")
    return BUILD / "parsed.jsonl", counts


# ---------------------------------------------------------------------------
# 2+3. normalize + derive
# ---------------------------------------------------------------------------


def _builtin_pack_ids(mappings: dict) -> set[str]:
    return {p["pack_id"] for p in mappings["packs"] if p["builtin"]}


def stage_normalize_derive(mappings: dict, parsed_path: Path, counts: Counts) -> Path:
    """清洗 + 派生 → `build/derived.jsonl`。

    两步合并为一个 pass：它们都是**逐条无状态**的转换，中间再落一次盘没有收益。

    归一与拒收规则见 docs/01 §4.2 / §5；派生字段见 §5.1–§5.4。
    词牌提取需要**全语料的候选集**（短且重复出现的标题），故先扫一遍标题。
    """
    builtin_pack_ids = _builtin_pack_ids(mappings)
    raw_rows = list(_read_jsonl(parsed_path))

    # 词牌候选：只看 genre=词 的源
    ci_titles = [
        row["title"]
        for row in raw_rows
        if mappings["by_file"][row["file"]]["genre"] == "词"
    ]
    cipai_candidates = build_cipai_candidates(ci_titles)
    print(f"  derive: 词牌候选（数据推断，短且重复）{len(cipai_candidates)} 个")

    def rows():
        for row in raw_rows:
            source = mappings["by_file"][row["file"]]
            source_id = row["source_id"]
            title = (row["title"] or "").strip()
            content = clean_content(row["content"])
            author = normalize_author(row["author"])

            if not source_id or not title or not content:
                counts.rejected[row["file"]] = counts.rejected.get(row["file"], 0) + 1
                continue

            dynasty = mappings["dynasty_by_name"][source["dynasty"]]
            genre = source["genre"]

            yield {
                "source_id": source_id,
                "file": row["file"],
                "title": title,
                "author": author,
                "content": content,
                "excerpt": first_line(content),
                "dynasty": source["dynasty"],
                "period": source["period"],
                "dynasty_order": dynasty["order"],
                "period_order": PERIOD_ORDER[source["period"]],
                "genre": genre,
                "form": infer_form(content, genre),
                "cipai": extract_cipai(title, cipai_candidates) if genre == "词" else None,
                "line_count": count_lines(content),
                "char_count": count_chars(content),
                "is_public_domain": 1 if dynasty["order"] <= PUBLIC_DOMAIN_MAX_ORDER else 0,
                "weight": source["weight"],
                "priority": source["priority"],
                "pack_id": source["pack_id"],
                # 同优先级（精选集之间）需要决胜依据：内置包优先。
                # 内置包会随 APK 下发，**必须完整** —— 否则「已内置 唐诗三百首」
                # 却比合集实际首数少，用户看到的是「内置包缺诗」。
                "builtin_pack": source["pack_id"] in builtin_pack_ids,
                "collection": source["collection"],
            }

    kept = _write_jsonl(BUILD / "derived.jsonl", rows())
    rejected_total = sum(counts.rejected.values())
    print(f"  derive: 保留 {kept:,} 条，拒收 {rejected_total} 条 {counts.rejected or ''}")
    return BUILD / "derived.jsonl"


# ---------------------------------------------------------------------------
# 4+5. dedup + assign_id
# ---------------------------------------------------------------------------


def stage_dedup_assign(mappings: dict, derived_path: Path, counts: Counts) -> Path:
    """去重合并 + 分配 `poem_id` → `build/deduped.jsonl`。

    去重键 `(author_norm, content_norm[:80])`（docs/01 §4.2），**标题不参与** ——
    同一首诗在不同选本里标题常带异文或卷次后缀。

    冲突时保留**优先级最高**的来源，并**保留全部合集归属** ——
    「这首诗收录于哪些选本」是选本类 App 的核心信息，不能因为去重而丢。

    宁可漏去不可错并：错并会永久丢诗且不可逆，漏去只是留几条重复。
    """
    allocator = IdAllocator(ID_MAP)

    merged: dict[tuple[str, str], dict] = {}

    for row in _read_jsonl(derived_path):
        key = dedup_key(row["author"], row["content"])
        existing = merged.get(key)

        if existing is None:
            merged[key] = row
            continue

        counts.merged += 1
        # 合集归属**全部保留** —— 「这首诗收录于哪些选本」是选本类 App 的核心信息，
        # 不能因为去重而丢。先在旧记录上累积，胜出者若变化再把集合带过去。
        slugs = existing.setdefault("_merged_collections", set())
        for record in (existing, row):
            collection = record.get("collection")
            if collection:
                slugs.add(collection["slug"])

        # 胜出条件：优先级更高；**同优先级时内置包优先**（见 derive 阶段的注释）。
        # 注意这只是决定「诗归哪个下载包」，**合集归属仍然全部保留** ——
        # 所以 水墨唐诗 的合集计数不会因为这一条而少。
        outranks = row["priority"] > existing["priority"] or (
            row["priority"] == existing["priority"]
            and row.get("builtin_pack")
            and not existing.get("builtin_pack")
        )
        if outranks:
            row["_merged_collections"] = slugs
            merged[key] = row

    counts.unique = len(merged)
    print(f"  dedup: 唯一 {counts.unique:,} 首，合并掉 {counts.merged:,} 条重复")

    def rows():
        for row in merged.values():
            row["poem_id"] = allocator.assign(row["source_id"])
            # 合集 = 自身来源的合集 ∪ 合并过程中收集到的合集
            slugs = set(row.pop("_merged_collections", set()))
            if row.get("collection"):
                slugs.add(row["collection"]["slug"])
            row["collection_slugs"] = sorted(slugs)
            row.pop("collection", None)
            yield row

    kept = _write_jsonl(BUILD / "deduped.jsonl", rows())
    allocator.save()
    print(
        f"  assign_id: {kept:,} 首已分配 poem_id"
        f"（本次新增 {allocator.allocated_new:,}，映射总数 {len(allocator.mapping):,}）"
    )
    return BUILD / "deduped.jsonl"


# ---------------------------------------------------------------------------
# 6. load
# ---------------------------------------------------------------------------


def _tokenize(text: str, jieba_module) -> str:
    """jieba 搜索模式分词，空格连接（docs/03 §4.3）。

    FTS5 内置分词器都不支持中文：`unicode61` 会把整段中文当成一个 token。
    故必须**入库前预分词**，查询串同样分词。
    """
    return " ".join(t for t in jieba_module.cut_for_search(text) if t.strip())


def stage_load(mappings: dict, deduped_path: Path, counts: Counts, with_fts: bool = True) -> Path:
    """写入 `poems.db`（含 FTS5 索引与作者作品数回填）。

    **全程流式 + 分批提交**，不把 85 万条材料化到内存。
    这里曾经写成先 `list(...)` 再插入，粗算峰值约 3 GB（记录本体 + FTS 分词结果各一份），
    在普通开发机上会直接被 OOM 杀掉；改为逐条读取、每 [BATCH] 条提交一次后，
    峰值只取决于本批，与语料规模无关。
    """
    import jieba

    jieba.setLogLevel(60)  # 关掉加载词典时的 stderr 噪音
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    if DB_PATH.exists():
        DB_PATH.unlink()
    for suffix in ("-wal", "-shm"):
        stale = Path(str(DB_PATH) + suffix)
        if stale.exists():
            stale.unlink()

    connection = sqlite3.connect(DB_PATH)
    for pragma in PRAGMAS:
        connection.execute(pragma)
    create_schema(connection)

    # ---- 朝代 ----
    dynasty_id_by_name: dict[str, int] = {}
    for dynasty in mappings["dynasties"]:
        cursor = connection.execute(
            "INSERT INTO dynasty(name, period, period_order, dynasty_order, is_public_domain)"
            " VALUES (?,?,?,?,?)",
            (
                dynasty["name"],
                dynasty["period"],
                PERIOD_ORDER[dynasty["period"]],
                dynasty["order"],
                1 if dynasty["order"] <= PUBLIC_DOMAIN_MAX_ORDER else 0,
            ),
        )
        dynasty_id_by_name[dynasty["name"]] = cursor.lastrowid
    connection.commit()

    # ---- 作者：按**归一化名字**分组（docs/02 §3.8：author_id 即按名字聚合）----
    # 第一遍只统计「每个名字最常出现在哪个朝代」，内存里只有名字与朝代，与语料规模弱相关。
    dynasty_votes: dict[str, Counter] = defaultdict(Counter)
    total = 0
    for row in _read_jsonl(deduped_path):
        dynasty_votes[row["author"]][row["dynasty"]] += 1
        total += 1

    author_id_by_name: dict[str, int] = {}
    for name, votes in dynasty_votes.items():
        cursor = connection.execute(
            "INSERT INTO author(name, dynasty_id, poem_count) VALUES (?,?,0)",
            (name, dynasty_id_by_name[votes.most_common(1)[0][0]]),
        )
        author_id_by_name[name] = cursor.lastrowid
    connection.commit()
    print(f"  load: {total:,} 首 / 作者 {len(author_id_by_name):,} 位，开始入库…")

    # ---- 合集 ----
    collection_id_by_slug: dict[str, int] = {}
    for source in mappings["sources"]:
        collection = source.get("collection")
        if not collection or collection["slug"] in collection_id_by_slug:
            continue
        pack = mappings["pack_by_id"][source["pack_id"]]
        cursor = connection.execute(
            "INSERT INTO collection(slug, name, pack_id, builtin, sort_order) VALUES (?,?,?,?,?)",
            (
                collection["slug"],
                collection["name"],
                source["pack_id"],
                1 if pack["builtin"] else 0,
                collection["order"],
            ),
        )
        collection_id_by_slug[collection["slug"]] = cursor.lastrowid
    connection.commit()

    poem_insert = (
        "INSERT INTO poem(poem_id, source_id, title, content, author_id, dynasty_id, genre,"
        " form, cipai, line_count, char_count, is_public_domain, weight, excerpt, pack_id)"
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    )
    fts_insert = "INSERT INTO poem_fts(rowid, title_tok, author_tok, content_tok) VALUES (?,?,?,?)"
    member_insert = "INSERT INTO collection_member(collection_id, poem_id) VALUES (?,?)"

    poems_batch: list[tuple] = []
    fts_batch: list[tuple] = []
    member_batch: list[tuple] = []
    members_total = 0
    started = time.time()

    def flush() -> None:
        if not poems_batch:
            return
        connection.executemany(poem_insert, poems_batch)
        if with_fts:
            connection.executemany(fts_insert, fts_batch)
        if member_batch:
            connection.executemany(member_insert, member_batch)
        connection.commit()
        poems_batch.clear()
        fts_batch.clear()
        member_batch.clear()

    for index, row in enumerate(_read_jsonl(deduped_path), start=1):
        poems_batch.append(
            (
                row["poem_id"], row["source_id"], row["title"], row["content"],
                author_id_by_name[row["author"]], dynasty_id_by_name[row["dynasty"]],
                row["genre"], row["form"], row["cipai"], row["line_count"],
                row["char_count"], row["is_public_domain"], row["weight"],
                row["excerpt"], row["pack_id"],
            )
        )
        for slug in row["collection_slugs"]:
            member_batch.append((collection_id_by_slug[slug], row["poem_id"]))
            members_total += 1
        if with_fts:
            fts_batch.append(
                (
                    row["poem_id"],
                    _tokenize(row["title"], jieba),
                    _tokenize(row["author"], jieba),
                    _tokenize(row["content"], jieba),
                )
            )

        if len(poems_batch) >= BATCH:
            flush()
            if index % 20_000 < BATCH:
                elapsed = time.time() - started
                rate = index / elapsed if elapsed else 0
                print(
                    f"    {index:,}/{total:,}  已用 {elapsed:.0f}s  ({rate:.0f} 首/s)",
                    flush=True,
                )

    flush()
    print(f"  load: 诗词入库完成；合集归属 {members_total:,} 行")

    connection.execute(
        "UPDATE author SET poem_count = ("
        "  SELECT COUNT(*) FROM poem WHERE poem.author_id = author.author_id)"
    )
    connection.commit()
    connection.execute("ANALYZE")  # 让查询计划器拿到真实统计，索引才用得上
    connection.commit()

    counts.unique = connection.execute("SELECT COUNT(*) FROM poem").fetchone()[0]
    connection.close()
    print(f"  load: poems.db 完成，{counts.unique:,} 首 → {DB_PATH}")
    return DB_PATH


# ---------------------------------------------------------------------------
# 7. pack
# ---------------------------------------------------------------------------


def stage_pack(mappings: dict, db_path: Path) -> list[dict]:
    """按包切分并产出 `packs/*.jsonl[.gz]` + `manifest.json`（docs/01 §6）。"""
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row
    PACKS_DIR.mkdir(parents=True, exist_ok=True)

    fields = (
        "poem_id", "title", "author", "dynasty", "period", "genre", "form", "cipai",
        "line_count", "char_count", "is_public_domain", "content",
    )
    manifests: list[dict] = []

    for pack in mappings["packs"]:
        rows = connection.execute(
            """
            SELECT p.poem_id, p.title, p.content, a.name AS author, d.name AS dynasty,
                   d.period AS period, p.genre, p.form, p.cipai, p.line_count,
                   p.char_count, p.is_public_domain
            FROM poem p
            JOIN author a ON a.author_id = p.author_id
            JOIN dynasty d ON d.dynasty_id = p.dynasty_id
            WHERE p.pack_id = ?
            ORDER BY p.poem_id
            """,
            (pack["pack_id"],),
        ).fetchall()

        payload = "\n".join(
            json.dumps(
                {
                    **{k: row[k] for k in fields if k != "is_public_domain"},
                    "is_public_domain": bool(row["is_public_domain"]),
                },
                ensure_ascii=False,
                separators=(",", ":"),
            )
            for row in rows
        ) + ("\n" if rows else "")
        raw = payload.encode("utf-8")
        compressed = gzip.compress(raw, compresslevel=6)

        # 内置（L0）包写未压缩 JSONL，交给 APK 自身的 zip 再压一次（docs/01 §6.1）；
        # 其余包写 .jsonl.gz，由 nginx 直接吐给客户端。
        if pack["builtin"]:
            (PACKS_DIR / f"{pack['pack_id']}.jsonl").write_bytes(raw)
            # 内置包同时写进 APK 资源：内置内容随包下发，必须与库内完全一致
            ASSETS_DIR.mkdir(parents=True, exist_ok=True)
            (ASSETS_DIR / f"{pack['pack_id']}.jsonl").write_bytes(raw)
        else:
            (PACKS_DIR / f"{pack['pack_id']}.jsonl.gz").write_bytes(compressed)

        manifest = {
            "pack_id": pack["pack_id"],
            "name": pack["name"],
            "tier": pack["tier"],
            "version": 1,
            "poem_count": len(rows),
            "bytes_raw": len(raw),
            "bytes_gzip": len(compressed),
            # sha256 算在 **gzip 字节**上：下载包是 .jsonl.gz，下载方校验的是 gzip 文件本身。
            "sha256": hashlib.sha256(compressed).hexdigest(),
            # sha256_raw 算在 **原始 JSONL 字节**上：内置包随 APK 以未压缩 JSONL 下发，
            # 客户端 importBuiltins 校验的是解压后的原始字节，口径必须一致，否则必报「校验失败」。
            "sha256_raw": hashlib.sha256(raw).hexdigest(),
            "min_poem_id": rows[0]["poem_id"] if rows else 0,
            "max_poem_id": rows[-1]["poem_id"] if rows else 0,
            "builtin": pack["builtin"],
            "download_url": f"/packs/{pack['pack_id']}.v1.jsonl.gz",
            "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "requires": [],
            # 客户端需要 slug/name/sort_order 三个字段（据此登记 collection 表），
            # 而不是只要 slug —— 包里没有 collection 维表，名字只能由索引带进去。
            "collections": [
                {
                    "slug": s["collection"]["slug"],
                    "name": s["collection"]["name"],
                    "sort_order": s["collection"]["order"],
                }
                for s in mappings["sources"]
                if s["pack_id"] == pack["pack_id"] and s.get("collection")
            ],
        }
        (PACKS_DIR / f"{pack['pack_id']}.manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        manifests.append(manifest)

        # pack 表回填（docs/02 §3.9 的清单直接读这张表）
        connection.execute(
            "INSERT OR REPLACE INTO pack(pack_id, name, tier, version, poem_count, bytes_raw,"
            " bytes_gzip, sha256, min_poem_id, max_poem_id, builtin)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (
                pack["pack_id"], pack["name"], pack["tier"], 1, len(rows), len(raw),
                len(compressed), manifest["sha256"], manifest["min_poem_id"],
                manifest["max_poem_id"], 1 if pack["builtin"] else 0,
            ),
        )

    connection.commit()
    connection.close()

    _write_builtin_index(manifests)
    print(f"  pack: 产出 {len(manifests)} 个包 → {PACKS_DIR}")
    for manifest in sorted(manifests, key=lambda m: -m["poem_count"]):
        print(
            f"    {manifest['pack_id']:<14} {manifest['tier']}  {manifest['poem_count']:>7,} 首"
            f"  gzip {manifest['bytes_gzip'] / 1048576:>6.2f} MB"
        )
    return manifests


# ---------------------------------------------------------------------------
# 8. verify
# ---------------------------------------------------------------------------


def _write_builtin_index(manifests: list[dict]) -> None:
    """把内置包清单汇总成 `assets/builtin_packs.json`。

    客户端**遍历这份索引**导入内置包，而不是硬编码包列表 ——
    新增内置包只需改 `mappings/sources.json` 的 `packs`，Kotlin 侧零改动。
    """
    from datetime import datetime, timezone

    # 内置包随 APK 以**未压缩原始 JSONL** 下发，校验口径必须是原始字节，
    # 故内置索引里的 `sha256` 用 `sha256_raw` 覆盖（下载/DB 仍用 gzip 的 `sha256`）。
    builtin = []
    for m in manifests:
        if not m["builtin"]:
            continue
        entry = dict(m)
        entry["sha256"] = m["sha256_raw"]
        builtin.append(entry)
    if not builtin:
        return
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    (ASSETS_DIR / "builtin_packs.json").write_text(
        json.dumps({"builtin": builtin}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"  pack: 内置包索引 → {ASSETS_DIR / 'builtin_packs.json'}")


def stage_verify(mappings: dict, counts: Counts, manifests: list[dict], db_path: Path) -> dict:
    """产出 `etl_report.json` 并核对 `docs/01 §8` 的验收标准。"""
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row

    genres = {row["genre"]: row["n"] for row in connection.execute(
        "SELECT genre, COUNT(*) AS n FROM poem GROUP BY genre")}
    cipai_total = connection.execute(
        "SELECT COUNT(*) FROM poem WHERE genre='词'").fetchone()[0]
    cipai_filled = connection.execute(
        "SELECT COUNT(*) FROM poem WHERE genre='词' AND cipai IS NOT NULL").fetchone()[0]
    forms = {row["form"] or "（未推断）": row["n"] for row in connection.execute(
        "SELECT form, COUNT(*) AS n FROM poem GROUP BY form ORDER BY n DESC")}
    empty_fields = connection.execute(
        "SELECT COUNT(*) FROM poem WHERE title='' OR content=''").fetchone()[0]
    pack_sum = sum(m["poem_count"] for m in manifests)
    pack_gzip_total = sum(m["bytes_gzip"] for m in manifests)

    # 跨包重复：poem_id 只在 poem 表出现一次，故只要 pack 之和 == 总数即无重复
    report = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "parse": {"per_file": counts.per_file, "total": sum(counts.per_file.values())},
        "excluded": {"files": counts.excluded_files, "count": len(counts.excluded_files)},
        "rejected": {"per_file": counts.rejected, "total": sum(counts.rejected.values())},
        "dedup": {"merged": counts.merged, "unique": counts.unique},
        "empty_title_or_content": empty_fields,
        "genres": genres,
        "form_distribution": forms,
        "cipai_coverage": {
            "total_ci": cipai_total,
            "filled": cipai_filled,
            "rate": round(cipai_filled / cipai_total, 4) if cipai_total else 0,
        },
        "packs": [
            {k: m[k] for k in ("pack_id", "tier", "poem_count", "bytes_raw", "bytes_gzip", "sha256")}
            for m in manifests
        ],
        "pack_total_poems": pack_sum,
        "pack_total_gzip_bytes": pack_gzip_total,
    }
    connection.close()

    # ---- docs/01 §8 的阈值核对 ----
    checks = [
        ("去重后唯一诗词数 830,000–860,000", 830_000 <= counts.unique <= 860_000, f"{counts.unique:,}"),
        ("genre 仅 诗/词/辞", set(genres) <= {"诗", "词", "辞"}, "/".join(sorted(genres))),
        ("title/content 非空率 100%", empty_fields == 0, f"空 {empty_fields} 条"),
        ("排除文件数 == 12", len(counts.excluded_files) == 12, f"{len(counts.excluded_files)}"),
        ("包数与 poem 之和一致", pack_sum == counts.unique, f"{pack_sum:,} vs {counts.unique:,}"),
        ("全量 gzip < 120 MB", pack_gzip_total < 120 * 1024 * 1024, f"{pack_gzip_total / 1048576:.1f} MB"),
        ("cipai 覆盖率 ≥ 90%", report["cipai_coverage"]["rate"] >= 0.90,
         f"{report['cipai_coverage']['rate']:.1%}"),
    ]
    report["acceptance"] = [
        {"check": name, "pass": bool(ok), "value": value} for name, ok, value in checks
    ]

    (BUILD / "etl_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print("  verify: 验收核对")
    for name, ok, value in checks:
        print(f"    {'PASS' if ok else 'FAIL'}  {name:<34} {value}")
    print(f"  verify: 报告 → {(BUILD / 'etl_report.json')}")
    return report
