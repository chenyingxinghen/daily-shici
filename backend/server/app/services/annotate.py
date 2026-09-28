"""注疏的读取侧 —— 把 `annotation_db` 的一行变成响应所需的结构。

**这里不再有生成。** 需求纠正后（`docs/06 §1`）：

- 方式二 = 从网络导入的**真人注疏**（`annotation_db`，由 `scripts/import_gushiwen.py` 写入）；
- 方式一 = 用户就具体词句提问的**流式问答**（`services/ask.py`），**不落这张库**。

所以本模块从「生成编排」退化成纯粹的读取映射：没有提示词、没有队列、没有 LLM。
它存在的唯一理由是**让「一行数据怎么变成响应」只有一处实现** ——
`services/poems.py` 与 `routers/annotations.py` 都用它，避免两处各写一遍字段映射而漂移。
"""

from __future__ import annotations

from .. import annotation_db
from ..config import settings


def read(poem_id: int) -> dict | None:
    """读一首诗的注疏。没有则返回 `None`。

    **`None` 是常态而不是异常**：上游 43.4 万行里只有 1.14 万首配了注疏，
    匹配进我方语料后是 7,388 首（占全量 0.88%，`docs/06 §3.3`）。
    客户端遇到 `None` 应当去引导用户「问一问」（方式一），而不是显示「加载失败」。
    """
    if not settings.annotations_enabled:
        return None
    return annotation_db.render(poem_id)


def stats() -> dict:
    """给 `/healthz` 用的概览。"""
    return annotation_db.health()
