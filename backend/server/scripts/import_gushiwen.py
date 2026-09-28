"""导入古诗文网注疏 —— **方式二的主路径**（`docs/06 §2`）。

    # 先看清能匹配多少、写什么（不落库）
    .venv/Scripts/python.exe scripts/import_gushiwen.py --dry-run

    # 正式导入
    .venv/Scripts/python.exe scripts/import_gushiwen.py

    # 换一份数据文件
    .venv/Scripts/python.exe scripts/import_gushiwen.py --input D:/data/gushiwen.jsonl

## 这是「导入」不是「生成」

注疏是**真人编辑成果**，本脚本只做三件事：流式读上游 JSONL → 解析（`services/gushiwen.py`）
→ 按 `(作者, 标题)` 归一化匹配我方语料 → 幂等落库。
**全程不调用任何模型**，几秒钟跑完 745 MB。

## 匹配是怎样的

键是 `(归一化作者, 归一化标题)`。上游 43.4 万行里只有约 1.14 万首有注疏，
去掉匹配不上的，实际入库约 5 千首 —— 这是**数据本身的覆盖率上限**，
不是匹配没写好（实测过更激进的归一化只多 256 首，见 `services/gushiwen.py`）。

匹配不上的会打印前若干条，方便抽查是「我方没有这首诗」还是「名字对不上」。
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
from collections import Counter
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER_DIR))

from app import annotation_db  # noqa: E402
from app.config import settings  # noqa: E402
from app.services import gushiwen  # noqa: E402

#: 默认数据文件位置（`.tmp` 下，由调研时下载留用）。
DEFAULT_INPUT = SERVER_DIR.parent.parent / ".tmp" / "gushiwen.jsonl"

#: 每批落库条数。500 条约几 MB，足够摊薄事务开销又不会让内存涨。
BATCH = 500

#: 参与匹配的最短正文长度。单句残句（几个字）算不出有意义的相似度，
#: 放进去只会制造误配 —— 宁可少匹配，也不能把注疏挂错诗。
MIN_BODY_CHARS = 8


def load_poem_index() -> tuple[
    dict[tuple[str, str], tuple[int, str]],
    dict[tuple[str, str], list[tuple[int, str]]],
]:
    """两张索引：精确题名 → 单条；题名主干 → 候选列表。

    主干索引**必须保留全部候选**而不是只留第一个 —— 同作者同词牌常常有好几首
    （辛弃疾光是《好事近》就有多首），只留一个会把注疏挂到错的那一首上。
    消歧交给正文相似度（`gushiwen.match`）。

    精确索引重复键取**第一条**（`setdefault`）：同一 (作者, 标题) 在我方语料里
    确实有重复（不同来源收同一首诗），注疏对它们是同一份，挂给哪条都一样；
    用 setdefault 而非覆盖是为了让结果与遍历顺序无关 —— 否则每次导入可能挂到
    不同 poem_id 上，导入就不幂等了。
    """
    connection = sqlite3.connect(settings.db_path)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute(
            """
            SELECT p.poem_id, p.title, p.content, a.name AS author
            FROM poem p JOIN author a ON a.author_id = p.author_id
            """
        ).fetchall()
    finally:
        connection.close()

    exact: dict[tuple[str, str], tuple[int, str]] = {}
    stems: dict[tuple[str, str], list[tuple[int, str]]] = {}
    for row in rows:
        author = gushiwen.normalize_key(row["author"])
        body = gushiwen.body_key(row["content"])
        # 正文太短的诗（如单句残句）不参与匹配：几个字的正文算不出有意义的相似度
        if not author or len(body) < MIN_BODY_CHARS:
            continue
        exact.setdefault((author, gushiwen.normalize_key(row["title"])), (row["poem_id"], body))
        stems.setdefault((author, gushiwen.title_stem(row["title"])), []).append(
            (row["poem_id"], body)
        )
    return exact, stems


def main() -> int:
    parser = argparse.ArgumentParser(description="导入古诗文网注疏（方式二）")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="gushiwen.jsonl 路径")
    parser.add_argument("--dry-run", action="store_true", help="只报告匹配结果，不写库")
    parser.add_argument("--limit", type=int, default=None, help="最多处理多少行（试跑用）")
    parser.add_argument("--show-misses", type=int, default=10, help="打印多少条匹配失败的样本")
    args = parser.parse_args()

    if not args.input.exists():
        print(f"找不到数据文件：{args.input}")
        print("下载：curl -L -o gushiwen.jsonl "
              "https://huggingface.co/datasets/Papersnake/gushiwen/resolve/main/gushiwen.jsonl")
        return 1

    annotation_db.ensure_schema()
    print("=" * 70)
    print(f"数据文件：{args.input}  ({args.input.stat().st_size / 1e9:.2f} GB)")
    print(f"注疏库  ：{settings.annotations_db_path}")
    print("=" * 70)

    started = time.time()
    exact_index, stem_index = load_poem_index()
    print(f"我方语料索引：精确题名 {len(exact_index):,} 键 / 题名主干 {len(stem_index):,} 键")

    counters = Counter()
    pending: list[dict] = []
    misses: list[tuple[str, str]] = []
    kind_hits = Counter()
    match_kinds = Counter()

    with args.input.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            counters["lines"] += 1
            if args.limit and counters["lines"] > args.limit:
                break
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                counters["bad_json"] += 1
                continue

            parsed = gushiwen.parse_row(row)
            if parsed is None:
                counters["no_content"] += 1          # 上游绝大多数行都是这种空壳
                continue
            counters["with_content"] += 1

            hit = gushiwen.match(
                parsed["upstream_author"],
                parsed["upstream_title"],
                gushiwen.body_key((row.get("tb_gushiwen") or {}).get("cont")),
                exact_index,
                stem_index,
            )
            if hit is None:
                counters["unmatched"] += 1
                if len(misses) < args.show_misses:
                    misses.append((parsed["upstream_author"] or "佚名", parsed["upstream_title"]))
                continue

            poem_id, kind, score = hit
            counters["matched"] += 1
            match_kinds[kind] += 1
            for field in ("translation", "annotation", "appreciation", "background", "citation"):
                if parsed.get(field):
                    kind_hits[field] += 1
            pending.append({
                "poem_id": poem_id,
                "match_kind": kind,
                "match_score": round(score, 3),
                **parsed,
            })

            if len(pending) >= BATCH and not args.dry_run:
                annotation_db.upsert_many(pending)
                pending.clear()
                print(f"  已导入 {counters['matched']:,} 首…", flush=True)

    if pending and not args.dry_run:
        annotation_db.upsert_many(pending)

    elapsed = time.time() - started
    print()
    print(f"扫描行数        : {counters['lines']:,}")
    print(f"  其中有注疏内容: {counters['with_content']:,}   ← 空壳（上游没配注疏）{counters['no_content']:,}")
    print(f"  匹配到我方    : {counters['matched']:,}"
          f"   (精确题名 {match_kinds['exact']:,} / 主干消歧 {match_kinds['stem']:,})")
    print(f"  匹配不上      : {counters['unmatched']:,}")
    if counters["bad_json"]:
        print(f"  JSON 解析失败 : {counters['bad_json']:,}")
    print()
    print("字段覆盖（已匹配的）：")
    for field in ("translation", "annotation", "appreciation", "background", "citation"):
        print(f"  {field:<13} {kind_hits[field]:,}")
    if misses:
        print()
        print(f"匹配失败样本（前 {len(misses)} 条，用于抽查是「我方没有」还是「名字对不上」）：")
        for author, title in misses:
            print(f"  {author}《{title}》")
    print()
    print(f"耗时 {elapsed:.1f} s")
    if args.dry_run:
        print("\n--dry-run，未写库。")
    else:
        print(f"库内统计：{annotation_db.stats()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
