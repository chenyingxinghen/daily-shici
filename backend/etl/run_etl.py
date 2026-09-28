"""ETL 编排入口。

各阶段的实现见 `lib/pipeline.py`（阶段名与 `docs/01 §7.1` 一致）。

    python run_etl.py                 # 全流程
    python run_etl.py --from dedup    # 从某个阶段开始（复用已有中间产物）
    python run_etl.py --only pack     # 只重跑某个阶段（改分包规则时用）
    python run_etl.py --no-fts        # 跳过 FTS 建索引（调试用，快很多）

**中间产物**：`build/parsed.jsonl`、`build/derived.jsonl` 会落盘，
所以改分包规则或改派生逻辑时不必重下 283 MB 原始数据（`docs/01 §7.2`）。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lib import pipeline  # noqa: E402

STAGES = ("parse", "derive", "dedup", "load", "pack", "verify")


def main() -> int:
    parser = argparse.ArgumentParser(description="每日诗词 ETL")
    parser.add_argument("--from", dest="start", choices=STAGES, default="parse",
                        help="从该阶段开始（之前的阶段复用已有中间产物）")
    parser.add_argument("--only", dest="only", choices=STAGES, help="只跑这一个阶段")
    parser.add_argument("--no-fts", action="store_true", help="跳过 FTS5 建索引")
    args = parser.parse_args()

    started = time.time()
    mappings = pipeline.load_mappings()
    print(
        f"源文件 {len(mappings['sources'])} 个，数据包 {len(mappings['packs'])} 个，"
        f"朝代 {len(mappings['dynasties'])} 个\n"
    )

    def should(stage: str) -> bool:
        if args.only:
            return stage == args.only
        return STAGES.index(stage) >= STAGES.index(args.start)

    counts = pipeline.Counts()
    derived_path = pipeline.BUILD / "derived.jsonl"
    deduped_path = pipeline.BUILD / "deduped.jsonl"
    db_path = pipeline.DB_PATH
    manifests: list[dict] = []

    if should("parse"):
        print("[1/6] parse")
        _, counts = pipeline.stage_parse(mappings)

    if should("derive"):
        print("[2/6] normalize + derive")
        parsed = pipeline.BUILD / "parsed.jsonl"
        if not parsed.exists():
            raise SystemExit("缺少 build/parsed.jsonl，请先跑 parse 阶段")
        derived_path = pipeline.stage_normalize_derive(mappings, parsed, counts)

    if should("dedup"):
        print("[3/6] dedup + assign_id")
        if not derived_path.exists():
            raise SystemExit("缺少 build/derived.jsonl，请先跑 derive 阶段")
        deduped_path = pipeline.stage_dedup_assign(mappings, derived_path, counts)

    if should("load"):
        print("[4/6] load")
        if not deduped_path.exists():
            raise SystemExit("缺少 build/deduped.jsonl，请先跑 dedup 阶段")
        db_path = pipeline.stage_load(mappings, deduped_path, counts, with_fts=not args.no_fts)

    if should("pack"):
        print("[5/6] pack")
        if not db_path.exists():
            raise SystemExit("缺少 poems.db，请先跑 load 阶段")
        manifests = pipeline.stage_pack(mappings, db_path)

    if should("verify"):
        print("[6/6] verify")
        if not db_path.exists():
            raise SystemExit("缺少 poems.db")
        if not manifests:
            import json
            manifests = [
                json.loads(p.read_text(encoding="utf-8"))
                for p in sorted(pipeline.PACKS_DIR.glob("*.manifest.json"))
            ]
        pipeline.stage_verify(mappings, counts, manifests, db_path)

    print(f"\n完成，用时 {time.time() - started:.0f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
