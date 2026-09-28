"""`snippet` 与 `highlights` 计算（`docs/03 §4.5`）。

因为 FTS 表是**无内容表**（`content=''`），`snippet()` / `highlight()` 辅助函数不可用
—— 它们需要原文。但这对本契约无损：`docs/02 §2.3` 要的本来就是**字符区间**而不是
标记串，所以无论如何都要自算。

⚠️ **索引单位必须是 Unicode 码点**，不是 Python 的 `str` 下标。
Python 的 `str` 下标按**码点**计（与契约一致），但 Kotlin 的 `String` 是 UTF-16
code unit —— 生僻字（代理对）在两端会对不上。契约统一按码点，
客户端用 `codePointCount` 换算，两端各有单测。
"""

from __future__ import annotations

#: snippet 目标长度（docs/02 §3.6：命中句及前后各一句，总长控制在约 60 字）。
SNIPPET_TARGET = 60

_SENTENCE_ENDINGS = "。！？；\n"


def _split_sentences(content: str) -> list[str]:
    """按句切分。**保留分隔符**，否则 snippet 会缺标点、读起来断气。"""
    sentences: list[str] = []
    buffer: list[str] = []
    for ch in content:
        if ch in _SENTENCE_ENDINGS:
            if ch != "\n":
                buffer.append(ch)
            piece = "".join(buffer).strip()
            if piece:
                sentences.append(piece)
            buffer = []
        else:
            buffer.append(ch)
    tail = "".join(buffer).strip()
    if tail:
        sentences.append(tail)
    return sentences


def build_snippet(content: str, query: str) -> tuple[str, list[list[int]]]:
    """返回 `(snippet, highlights)`。

    `highlights` 是 snippet 内的 `[[start, end], …]`，**左闭右开、按 Unicode 码点计**。

    策略（docs/03 §4.5）：
    1. 按句切分，找出含查询词的句子作为命中句；
    2. snippet = 命中句（必要时带上前一句），超长则截断；
    3. 在 snippet 内标出查询词的全部出现位置，合并重叠区间。
    """
    if not content:
        return "", []

    # 查询词可能被分词拆开，用「最长词」去定位命中句更稳
    needles = _candidate_needles(query)
    sentences = _split_sentences(content) or [content]

    hit_index = 0
    for index, sentence in enumerate(sentences):
        if any(needle in sentence for needle in needles):
            hit_index = index
            break

    # 命中句 + 前一句（若有），让用户看到上下文
    start_index = max(0, hit_index - 1)
    snippet = "".join(sentences[start_index : hit_index + 1]).strip()
    if len(snippet) > SNIPPET_TARGET:
        snippet = snippet[:SNIPPET_TARGET]

    return snippet, find_highlights(snippet, needles)


def _candidate_needles(query: str) -> list[str]:
    """从查询串里取用于定位的子串。

    先按分段标点切开，再对每段取原串本身；这样「落霞与孤鹜齐飞」这种整句
    能直接命中，而「明月」这种短词也不会被切碎。
    """
    raw = [part.strip() for part in query.replace("，", " ").replace(",", " ").split()]
    needles = [p for p in raw if p]
    # 长词优先，短词兜底
    return sorted(set(needles), key=len, reverse=True) or [query.strip()]


def find_highlights(text: str, needles: list[str]) -> list[list[int]]:
    """找出全部命中区间并**合并重叠**（docs/03 §4.5 第 3 步）。

    用 `str.find` 逐段推进而不是正则：查询串可能含正则元字符，走正则还得再写一层转义。
    Python 的 `str` 下标已经是**码点**，故直接可用，无需换算。
    """
    spans: list[tuple[int, int]] = []
    for needle in needles:
        if not needle:
            continue
        from_index = 0
        while True:
            found = text.find(needle, from_index)
            if found < 0:
                break
            spans.append((found, found + len(needle)))
            from_index = found + 1

    if not spans:
        return []

    spans.sort()
    merged: list[list[int]] = [list(spans[0])]
    for start, end in spans[1:]:
        if start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], end)
        else:
            merged.append([start, end])
    return merged
