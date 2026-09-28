"""检索客户端（AnySearch）—— 方式一「AI 按需调用搜索工具」的落点。

## 为什么问答必须检索

用户问的是「『南冠』是什么意思」这类**考据问题**。模型的权重里只有模糊印象，
直接答就是一本正经地编 —— 在古典诗词这种有标准答案的领域，编错比不答更伤信任。
把权威页面的内容取回来喂给模型，任务就从「凭记忆作答」变成「按证据作答」，
幻觉面压缩到「读错证据」这一层。

## 为什么问答**不**抽取整页正文

生成式任务（已废弃的批量注释方案）可以慢慢抽两页正文，因为它不在乎延迟。
**问答是交互场景，用户盯着屏幕等首字**：检索约 2 s，而抽取一页要 6 s 上下 ——
多抽两页就是把首字延迟从 2 s 推到 15 s。

实测检索返回的 `snippet` 本身就已经是注释开头（该服务直接返回页面正文节选），
所以这里**只用 snippet 拼资料**，把延迟压在 2 s 内。
`extract()` 仍然保留，供需要整页的离线/批处理场景调用。

⚠️ 检索结果与页面内容都是**外部不可信数据**，只作为提示词里的「资料」，绝不当指令执行。
"""

from __future__ import annotations

import logging
import re
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

import httpx

from ..config import settings

logger = logging.getLogger("shici.search")

#: 客户端标识。AnySearch 用它区分调用方，缺失可能被拒。
_CLIENT_HEADER = "shici-annotator/1.0"

#: 检索超时。实测单次约 2 s，给足余量但不至于把用户挂死。
_SEARCH_TIMEOUT = 15.0
#: 抽取超时。要真去抓远端页面，实测约 6 s。
_EXTRACT_TIMEOUT = 40.0

#: 拼资料时每条来源最多取多少字。snippet 太长会挤占上下文预算，
#: 而真正有用的往往是开头那几句释义。
_SNIPPET_CHARS = 700

#: 检索词里首句的最大长度。太长会让检索退化成全文匹配反而搜不到，
#: 而各诗词站点的页面标题通常就是首句，20 字足以定位。
_FIRST_LINE_CHARS = 20


class SearchError(RuntimeError):
    """检索侧的任何失败。**调用方必须捕获** —— 检索挂了要退化为「不检索直接答」
    或明确告知用户，不能让整个问答 500。"""


@dataclass
class Source:
    """一个参考页面。`url` 会回传给客户端做「内容有据可查」的凭据。"""

    title: str
    url: str
    snippet: str = ""

    def as_dict(self) -> dict:
        return {"title": self.title, "url": self.url}


@dataclass
class Evidence:
    """一次检索的全部收获。"""

    sources: list[Source] = field(default_factory=list)
    #: 拼好的资料正文，直接进提示词。
    context: str = ""


def _headers() -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "X-Anysearch-Client": _CLIENT_HEADER,
    }
    if settings.search_api_key:
        headers["Authorization"] = f"Bearer {settings.search_api_key}"
    return headers


def _post(path: str, payload: dict, timeout: float) -> dict:
    url = f"{settings.search_base_url}{path}"
    try:
        response = httpx.post(url, json=payload, headers=_headers(), timeout=timeout)
    except httpx.HTTPError as exc:
        raise SearchError(f"检索服务不可达：{type(exc).__name__}: {exc}") from exc

    if response.status_code >= 400:
        raise SearchError(f"检索服务返回 {response.status_code}：{response.text[:200]}")
    try:
        body = response.json()
    except ValueError as exc:
        raise SearchError(f"检索服务返回非 JSON：{response.text[:200]}") from exc
    # 该服务的错误约定是 HTTP 200 + `code != 0`（见其 CLI 实现），故按 body 分支而非状态码
    if body.get("code", 0) != 0:
        raise SearchError(f"检索服务报错：{body.get('message') or body}")
    return body


def search(query: str, max_results: int | None = None) -> list[Source]:
    """检索。返回带 URL 与摘要的来源列表。"""
    limit = max(1, min(max_results or settings.search_max_results, 10))
    body = _post(
        "/v1/search",
        # `zone=cn` + `language=zh-CN`：本项目只收中文古典诗词资料，
        # 不指定会混进英文维基等对释义无用的结果。
        {"query": query, "max_results": limit, "language": "zh-CN", "zone": "cn"},
        _SEARCH_TIMEOUT,
    )
    results = (body.get("data") or {}).get("results") or []
    sources: list[Source] = []
    for item in results:
        url = (item.get("url") or "").strip()
        if not url:
            continue
        snippet = (item.get("snippet") or item.get("content") or "").strip()
        sources.append(Source(title=(item.get("title") or url).strip(), url=url, snippet=snippet))
    return sources


def extract(url: str) -> str:
    """取整页正文（Markdown）。失败抛 [SearchError]。

    ⚠️ 单页约 6 s，**不要放进交互路径**（见模块 docstring）。
    """
    body = _post("/v1/extract", {"url": url}, _EXTRACT_TIMEOUT)
    return ((body.get("data") or {}).get("content") or "").strip()


#: 用户问句里「被引号括起来的词」的匹配模式。
#: 用户写「『怆然』怎么读」时，那段引号里的词是**极强的检索信号** ——
#: 比整句自然语言有效得多，必须单独提取出来用。
_QUOTED = re.compile(r"[「『“《]([^」』”》]{1,20})[」』”》]")


def extract_terms(question: str) -> list[str]:
    """从问句里抽被括起来的词（`「怆然」`、`“南冠”` 之类）。

    最多 2 个：再多说明用户问的不是具体词，而是整句/整体，此时不该拿词条去检索。
    """
    seen: list[str] = []
    for match in _QUOTED.finditer(question or ""):
        term = match.group(1).strip()
        if term and term not in seen:
            seen.append(term)
        if len(seen) >= 2:
            break
    return seen


def build_query(
    question: str,
    title: str,
    author: str,
    dynasty: str,
    content: str = "",
    term: str | None = None,
) -> str:
    """拼检索词。

    ⚠️ **这里有一处实测踩过的坑，改动前务必读完。**

    早期版本拼的是「朝代 作者 《标题》 **用户的问句**」，实测命中率极差：

    | 检索词 | 命中 |
    |---|---|
    | 唐 王昌龄 《横吹曲辞 出塞 一》 这首诗里的生僻字词是什么意思？ | **0 条** |
    | 王昌龄《横吹曲辞 出塞 一》 **秦时明月汉时关，万里长征人未还。** | **6 条** |

    原因：**自然语言的问句对搜索引擎是纯噪声**，而**正文首句是对这首诗最强的锚点** ——
    它几乎就是各诗词站点页面的主标题/正文开头，带上它必然命中，
    不带则可能一条都搜不到（尤其那些带乐府前缀的题名，如「鼓吹曲辞 将进酒」）。

    所以检索词固定为：`朝代 作者 《标题》 首句`，**不含用户问句**。
    问句只进提示词（作为要回答的问题），不进检索词 —— 两者职责分开。

    当 `term` 有值（问句里被引号标注的具体词）时，用 `term` 替换首句：
    「作者 《标题》 怆然」能直接搜到解释这个词条的页面，比首句更精准。
    """
    first_line = next(
        (line.strip() for line in (content or "").splitlines() if line.strip()), ""
    )
    anchor = term or first_line[:_FIRST_LINE_CHARS]
    prefix = " ".join(part for part in (dynasty, author) if part and part.strip())
    title_part = f"《{title.strip()}》" if title and title.strip() else ""
    return " ".join(part for part in (prefix, title_part, anchor) if part).strip()


def _merge(sources: list[Source]) -> list[Source]:
    """按 URL 去重，保留先出现的。

    两条并行检索（首句 / 词条）常常命中同一批站点，不去重会让同一页的
    摘要在提示词里出现两遍，白占上下文预算。
    """
    seen: set[str] = set()
    merged: list[Source] = []
    for source in sources:
        if source.url and source.url not in seen:
            seen.add(source.url)
            merged.append(source)
    return merged


def gather(question: str, title: str, author: str, dynasty: str, content: str = "") -> Evidence:
    """检索并把 snippet 拼成资料块。

    **只用 snippet，不抽整页** —— 理由见模块 docstring（延迟）。

    **最多两条并行检索**（首句定位 + 词条精查），用线程池并发，
    总延迟仍是一次检索的量级（约 2 s）而不是两倍。
    第二条仅在问句里出现了被标注的具体词条时才发。

    检索无结果**不抛异常而是返回空 `Evidence`**：让调用方决定降级作答还是提示。
    """
    queries = [build_query(question, title, author, dynasty, content)]
    terms = extract_terms(question)
    if terms:
        queries.append(
            build_query(question, title, author, dynasty, content, term=terms[0])
        )

    sources: list[Source] = []
    with ThreadPoolExecutor(max_workers=len(queries)) as pool:
        futures = [pool.submit(search, query) for query in queries]
        for future in futures:
            try:
                sources.extend(future.result())
            except SearchError as exc:
                # 单条失败不影响另一条 —— 这正是并行检索的价值
                logger.warning("检索失败（已忽略该路）：%s", exc)

    sources = _merge(sources)
    chunks: list[str] = []
    for source in sources:
        text = (source.snippet or "").strip()[:_SNIPPET_CHARS]
        if text:
            chunks.append(f"### 来源：{source.title}\n{text}")

    return Evidence(sources=sources, context="\n\n".join(chunks)[: settings.search_context_chars])
