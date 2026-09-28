"""把 tools/seed_source.txt 编译成客户端内置数据包 app/src/main/assets/seed_pack.jsonl。

为什么要有这一步：包格式是 JSONL（一行一首诗，content 里的换行必须是 JSON 转义的 \\n）。
手写这种转义极易出错且错了不报错，只是正文少一行。所以源数据用「|」分隔的纯文本写，
由脚本负责转义、统计与校验。

用法：
    python tools/build_seed.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools" / "seed_source.txt"
TARGET = ROOT / "app" / "src" / "main" / "assets" / "seed_pack.jsonl"

PACK_ID = "seed"
COLUMNS = 8


def parse(source: Path) -> list[dict]:
    rows: list[dict] = []
    for lineno, raw in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if lineno == 1 and line.lower().startswith("id|"):
            continue  # 表头

        parts = line.split("|")
        if len(parts) != COLUMNS:
            raise SystemExit(f"{source.name}:{lineno} 期望 {COLUMNS} 列，实际 {len(parts)} 列")

        poem_id, title, author, dynasty, period, genre, weight, content = parts
        # 源文件里的 \n 是两个字符（反斜杠 + n），这里还原成真实换行。
        content = content.replace("\\n", "\n")

        if not title.strip():
            raise SystemExit(f"{source.name}:{lineno} 标题为空")
        if not content.strip():
            raise SystemExit(f"{source.name}:{lineno} 正文为空")

        rows.append(
            {
                "poemId": int(poem_id),
                "title": title.strip(),
                "content": content,
                "author": author.strip(),
                "dynasty": dynasty.strip(),
                "period": period.strip(),
                "genre": genre.strip(),
                "lineCount": content.count("\n") + 1,
                "charCount": sum(1 for ch in content if not ch.isspace()),
                "isPublicDomain": True,
                "weight": int(weight),
            }
        )
    return rows


def validate(rows: list[dict]) -> None:
    if not rows:
        raise SystemExit("源数据为空")

    ids = [r["poemId"] for r in rows]
    if len(set(ids)) != len(ids):
        raise SystemExit("poemId 有重复")

    # 同一作者 + 同一标题视为重复条目（区别于「无题」这类同名不同作的合法情况）。
    keys = [(r["author"], r["title"]) for r in rows]
    dupes = {k for k in keys if keys.count(k) > 1}
    if dupes:
        raise SystemExit(f"存在重复条目：{sorted(dupes)}")

    for r in rows:
        if "\r" in r["content"]:
            raise SystemExit(f"poemId={r['poemId']} 正文含 \\r")
        if "\n\n" in r["content"]:
            print(f"  ! poemId={r['poemId']} 正文含空行，列表摘要是取首个非空行，确认是否有意为之")

    missing = [r["poemId"] for r in rows if not r["author"] or not r["dynasty"]]
    if missing:
        raise SystemExit(f"以下条目缺作者或朝代：{missing}")


def main() -> int:
    rows = parse(SOURCE)
    validate(rows)
    rows.sort(key=lambda r: r["poemId"])

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    with TARGET.open("w", encoding="utf-8", newline="\n") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")))
            fh.write("\n")

    dynasties = sorted({r["dynasty"] for r in rows})
    genres = sorted({r["genre"] for r in rows})
    print(f"写出 {len(rows)} 首 → {TARGET.relative_to(ROOT)}  ({TARGET.stat().st_size} 字节)")
    print(f"  packId   : {PACK_ID}")
    print(f"  朝代     : {' / '.join(dynasties)}")
    print(f"  体裁     : {' / '.join(genres)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
