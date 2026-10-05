"""`poem_id` 分配（docs 01 §4.3）。

**核心不变量：poem_id 一经分配永不改变。**

一旦上游重排就重新分配 id，全量客户端的本地库与增量包会立刻对不上号，
而且这种错位不会报错、只会表现为「下载了却搜不到」。
所以映射落盘 `id_map.jsonl` 并纳入版本控制，是全项目增量一致性的唯一依据。
"""

from __future__ import annotations

import json
from pathlib import Path


class IdAllocator:
    """`source_id → poem_id` 的持久映射。"""

    def __init__(self, map_path: Path) -> None:
        self.map_path = map_path
        self.mapping: dict[str, int] = {}
        self.allocated_new = 0
        self._cursor = 1
        self._load()

    def _load(self) -> None:
        if not self.map_path.exists():
            return
        with self.map_path.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                row = json.loads(line)
                self.mapping[row["source_id"]] = row["poem_id"]
        # 只用一次 max，避免 assign 里反复求最大值变成 O(n^2)
        self._cursor = max(self.mapping.values(), default=0) + 1

    def assign(self, source_id: str) -> int:
        """命中则复用，否则追加分配。幂等。"""
        existing = self.mapping.get(source_id)
        if existing is not None:
            return existing
        poem_id = self._cursor
        self._cursor += 1
        self.mapping[source_id] = poem_id
        self.allocated_new += 1
        return poem_id

    def save(self) -> None:
        """按 source_id 排序写出，保证 diff 稳定（顺序变了 git 会显示全文件改动）。"""
        self.map_path.parent.mkdir(parents=True, exist_ok=True)
        with self.map_path.open("w", encoding="utf-8", newline="\n") as fh:
            for source_id in sorted(self.mapping):
                fh.write(
                    json.dumps(
                        {"source_id": source_id, "poem_id": self.mapping[source_id]},
                        ensure_ascii=False,
                        separators=(",", ":"),
                    )
                    + "\n"
                )
