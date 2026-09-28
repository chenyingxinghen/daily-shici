"""全文检索：jieba 预分词 + FTS5 查询 + **注入防护**（`docs/03 §4`）。

这是全项目**唯一的注入面**，必须把用户输入当**数据**而非**语法**处理。

FTS5 的 `MATCH` 右值不接受参数化占位符做语法解析 —— 占位符能防住 SQL 注入，
但防不住 **FTS5 查询语法注入**：一个裸 `"` 或 `*` 就能让 `MATCH` 报语法错、
直接 500。所以做法是把每个词包成**短语**并转义内部引号，再拼成表达式。
"""

from __future__ import annotations

import jieba

#: `scope` → FTS 列名（docs/03 §4.3 的列限定）。
SCOPE_COLUMNS = {
    "title": "title_tok",
    "author": "author_tok",
    "content": "content_tok",
}

#: 词长低于这个值时词级检索会漏（古诗里「月」「风」这类单字查询很常见），
#: 改用 LIKE 兜底（docs/03 §4.4）。
MIN_TOKEN_LEN = 2


def tokenize(text: str) -> str:
    """入库与查询必须用**同一个**分词器，否则永远搜不到。"""
    return " ".join(t for t in jieba.cut_for_search(text) if t.strip())


def _escape_phrase(token: str) -> str:
    """把一个词转成 FTS5 短语字面量：整体加双引号，内部双引号翻倍。

    这样 `a"b` 会变成 `"a""b"`，被解析为一个普通短语而不是两个 token 的语法。
    """
    return '"' + token.replace('"', '""') + '"'


def build_match(query: str, scope: str | None = None) -> str | None:
    """把用户查询串转成安全的 `MATCH` 表达式。

    - 分词后**逐个包成短语**，用空格连接（FTS5 里空格 = AND）；
    - **末词加 `*`** 支持前缀匹配，即「边打边搜」（docs/03 §4.3）；
    - `scope` 给定时做列限定：`title_tok : ("落霞"*)`。

    返回 None 表示没有可用 token —— 调用方应走 LIKE 兜底，而不是拿空表达式去 MATCH。
    """
    tokens = [t for t in jieba.cut_for_search(query) if t.strip()]
    if not tokens:
        return None

    phrases = [_escape_phrase(t) for t in tokens]
    phrases[-1] += "*"
    expression = " ".join(phrases)

    column = SCOPE_COLUMNS.get((scope or "all").lower())
    if column:
        return f"{column} : ({expression})"
    return f"({expression})"


def needs_like_fallback(query: str) -> bool:
    """是否该改用 LIKE 子串匹配。

    判据：**分词后所有词都短于 [MIN_TOKEN_LEN]**。古诗里单字查询（「月」「风」）
    很常见，纯词级检索会大面积漏掉。
    """
    tokens = [t for t in jieba.cut_for_search(query) if t.strip()]
    if not tokens:
        return True
    return all(len(t) < MIN_TOKEN_LEN for t in tokens)
