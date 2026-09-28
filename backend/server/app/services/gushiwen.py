"""古诗文网数据集（`Papersnake/gushiwen`）的解析 —— **纯函数，无 I/O**。

数据来源与许可见 `docs/06 §2`。本模块只做一件事：
把上游那一行嵌套 JSON 变成注疏库的行。**不做匹配、不写库**（那是导入脚本的事），
这样解析规则可以单独测。

## 上游行的形状

```json
{"tb_gushiwen": {"nameStr": "静夜思", "author": "李白", "chaodai": "唐代", "cont": "<p>床前明月光…</p>", …},
 "tb_fanyis":   {"fanyis":   [{"nameStr": "译文及注释", "cont": "<p><strong>译文<br/></strong>…</p><p><strong>注释</strong><br/>…</p>"}]},
 "tb_shangxis": {"shangxis": [{"nameStr": "创作背景", "cont": "…", "cankao": "…"},
                              {"nameStr": "赏析",     "cont": "…", "cankao": ""}]},
 "tb_author":   {…}}
```

## ⚠️ 两个必须记住的陷阱

1. **`tb_gushiwen.yizhu` 不是注释正文**，是形如 `'9933003333ff'` 的**校验位掩码**。
   真正的译文与注释都在 `tb_fanyis.fanyis[*].cont` 里 —— 译文和注释**同处一个 HTML 块**，
   靠 `<strong>译文</strong>` / `<strong>注释</strong>` 两个内层标题分段。
   按 `yizhu` 取正文会得到一串十六进制垃圾。
2. **`cankao` 可能是 `'0'`**（占位符，不是出处）。直接落库会让客户端显示
   一行「参考资料：0」—— 必须过滤。

## 另外：上游没有可回溯的页面 URL

`shiID` / `shiIDnew` 实测恒为 0，数据集里也没有 slug。所以 `source_url` 一律用
**古诗文网的站内检索链接**（`so.gushiwen.cn/search.aspx?value=<标题>`），
用户点进去就是这首诗的检索结果页。这是「可达」与「不爬站」之间的务实折中。
"""

from __future__ import annotations

import html as html_module
import re
import urllib.parse

#: 上游标识与许可。写进每一行 —— 这张库存的是别人的编辑成果，来源不能丢。
SOURCE = "gushiwen"
LICENSE = "CC0-1.0"

#: 站内检索入口。**必须用 `so.` 子域**：`www.gushiwen.cn/search.aspx` 会 302 到登录页。
SEARCH_URL = "https://so.gushiwen.cn/search.aspx?value="

#: 译文块的**内层**标题 → 我们的字段。未列出的标题一律并入注释
#: （`文言知识`、`字词` 之类都是语言层面的说明，归注释最自然）。
#: ⚠️ 这里只放内层标题；`译文及注释` 这类是**外壳**名字，不进这张表。
_FANYI_HEADINGS = {
    "译文": "translation",
    "译文二": "translation",
    "白话译文": "translation",
    "注释": "annotation",
    "文言知识": "annotation",
}

#: 赏析块的外层标题前缀 → 我们的字段。
_BACKGROUND_PREFIXES = ("创作背景", "背景")
_APPRECIATION_PREFIXES = (
    "赏析", "鉴赏", "简析", "评析", "评解", "题解", "解析", "说明", "评点", "点评",
)

#: 内层标题的哨兵。用控制字符而非普通标记，避免与正文撞车。
_OPEN, _CLOSE = "\x01", "\x02"


def html_to_text(raw: str) -> str:
    """HTML → 纯文本，**保留段落与换行结构**。

    顺序很关键：`<br>` / `</p>` 必须在剥标签**之前**转成换行，否则段落会粘成一行 ——
    注释是逐条一行的，粘起来就没法读了。`&nbsp;` 与全角空格是上游的缩进习惯，
    统一收敛成普通空格，否则客户端会渲染出诡异的空档。
    """
    if not raw:
        return ""
    text = re.sub(r"(?i)<br\s*/?>", "\n", raw)
    text = re.sub(r"(?i)</p\s*>", "\n\n", text)
    text = re.sub(r"(?i)<p[^>]*>", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = html_module.unescape(text)
    text = text.replace("\u3000", " ").replace("\xa0", " ")
    text = re.sub(r"[ \t]+", " ", text)
    # ⚠️ 收敛空行**必须放在逐行 strip 之后**：上游常写成 "。\n \n\n首先"，
    # 空行里夹着空格，`\n{3,}` 在 strip 之前匹配不到；先 strip 再收敛才能真的压成一段间隔。
    lines = [line.strip() for line in text.split("\n")]
    collapsed: list[str] = []
    blank = 0
    for line in lines:
        if line:
            blank = 0
            collapsed.append(line)
        else:
            blank += 1
            # 最多保留一个空行 = 一个段落间隔；更多只是上游的排版噪音
            if blank == 1:
                collapsed.append("")
    return "\n".join(collapsed).strip()


def _sections(raw: str) -> list[tuple[str, str]]:
    """把一块 HTML 按 `<strong>标题</strong>` 切成 `[(标题, 正文)]`。

    这就是「译文及注释」块内部的分段方式。标题本身留在结果里 ——
    调用方要靠它决定内容归属。
    """
    if not raw:
        return []
    marked = re.sub(r"(?i)<strong[^>]*>", _OPEN, raw)
    marked = re.sub(r"(?i)</strong\s*>", _CLOSE, marked)

    sections: list[tuple[str, str]] = []
    position = 0
    while True:
        start = marked.find(_OPEN, position)
        if start == -1:
            break
        end = marked.find(_CLOSE, start)
        if end == -1:
            break
        heading = html_to_text(marked[start + 1 : end]).strip()
        next_start = marked.find(_OPEN, end)
        body = marked[end + 1 : next_start if next_start != -1 else len(marked)]
        sections.append((heading, html_to_text(body)))
        position = end + 1
    return sections


def parse_fanyi(entries: list[dict]) -> tuple[str | None, str | None]:
    """译文 + 注释。返回 `(translation, annotation)`。

    上游的 `nameStr` 有 `译文及注释` / `注释` / `译文` / `文言知识` 几种，
    但它们**只是外壳**：真正的分界在块内的 `<strong>` 标题上。
    所以统一走「先按内层标题切、切不出来再退回外壳名」——
    这样 `译文及注释` 和 `注释` 两种外壳用同一段代码处理，不会漏。
    """
    translation_parts: list[str] = []
    annotation_parts: list[str] = []

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        body = (entry.get("cont") or "").strip()
        if not body:
            continue
        outer = (entry.get("nameStr") or "").strip()
        sections = [(h, b) for h, b in _sections(body) if b]

        if sections:
            for heading, text in sections:
                # 表里没有的内层标题一律归注释 —— 归错到译文会直接改变文本性质，
                # 而「多一条注释」最坏也只是多一条语言说明，是安全的一侧。
                target = _FANYI_HEADINGS.get(heading, "annotation")
                (translation_parts if target == "translation" else annotation_parts).append(text)
        else:
            text = html_to_text(body)
            if not text:
                continue
            if outer.startswith("译文"):
                translation_parts.append(text)
            else:
                annotation_parts.append(text)

    return _join(translation_parts), _join(annotation_parts)


def parse_shangxi(entries: list[dict]) -> tuple[str | None, str | None, str | None]:
    """赏析 + 创作背景 + 文献出处。返回 `(appreciation, background, citation)`。

    赏析块是**多条**的：`创作背景` / `赏析` / `鉴赏` / `简析` / `评析` / `版本说明` …
    把它们全部塞进一个 `appreciation` 字段会丢信息，所以：
    创作背景单列；其余按类型合并，并在**标题不是默认的「赏析/鉴赏」时保留 `【标题】` 前缀** ——
    用户需要知道这一段是「评析」还是「版本说明」，这跟「赏析」不是一回事。
    """
    appreciation_parts: list[str] = []
    background_parts: list[str] = []
    citations: list[str] = []

    for entry in entries:
        if not isinstance(entry, dict):
            continue
        heading = (entry.get("nameStr") or "").strip()
        text = html_to_text(entry.get("cont") or "")
        if text:
            if any(heading.startswith(p) for p in _BACKGROUND_PREFIXES):
                background_parts.append(text)
            else:
                is_default = any(heading == p for p in ("赏析", "鉴赏"))
                appreciation_parts.append(text if is_default else f"【{heading}】\n{text}")

        citation = clean_citation(entry.get("cankao"))
        if citation:
            citations.append(citation)

    return _join(appreciation_parts), _join(background_parts), _join(citations, sep="；")


def clean_citation(raw) -> str | None:
    """清洗文献出处。

    `'0'` 是上游的占位符（「无出处」的意思）—— 不过滤客户端就会显示「参考资料：0」。
    多条出处用 `&` 连接，保留原样（它们本身就是书目条目，改写反而丢信息）。
    """
    if raw is None:
        return None
    text = str(raw).strip()
    if not text or text in ("0", "-", "无"):
        return None
    # 单条 cankao 内部也用 `&` 分册，同样会重复（同一书目被引用两次），一并去重
    items = [part.strip() for part in text.split("&") if part.strip()]
    return "；".join(_dedupe(items)) or None


def _dedupe(parts: list[str]) -> list[str]:
    """按出现顺序去重。

    上游的 `创作背景` 与 `赏析` 两条常常带**同一条** `cankao`
    （比如都引自《唐诗鉴赏辞典》的同一页），不去重就会在客户端看到
    「王运熙 等．唐诗鉴赏辞典…；王运熙 等．唐诗鉴赏辞典…」这种明显是 bug 的重复。
    """
    seen: set[str] = set()
    result: list[str] = []
    for part in parts:
        if part and part not in seen:
            seen.add(part)
            result.append(part)
    return result


def _join(parts: list[str], sep: str = "\n\n") -> str | None:
    cleaned = _dedupe([p.strip() for p in parts if p and p.strip()])
    return sep.join(cleaned) if cleaned else None


def source_url(title: str) -> str:
    """站内检索链接。上游没有页面 URL，用检索页替代（见模块 docstring）。"""
    return SEARCH_URL + urllib.parse.quote(title or "")


def normalize_key(value: str | None) -> str:
    """匹配用的归一化：剥标点与空白 + NFKC（全角→半角）。"""
    if not value:
        return ""
    import unicodedata

    text = re.sub(r"[\s\u3000，。！？、；：“”‘’《》（）〈〉【】\[\]—…·,.!?;:\"'()\-]", "", value)
    return unicodedata.normalize("NFKC", text).strip()


# ---------------------------------------------------------------------------
# 匹配（`docs/06 §3.2`）
#
# 上游与我方的**题名习惯不同**，精确题名只能命中一半：
#   - 上游把组诗合成一条：`关山月`    ↔ 我方 `关山月二首 其一` / `其二`
#   - 上游用「词牌·首句」：`夜行船·忆昔西都欢纵` ↔ 我方 `夜行船`
#   - 上游带宫调：        `双调·寿阳曲…`        ↔ 我方 `双调·寿阳曲`
# 所以先按「题名主干」兜一层，再用**正文相似度**把关。
#
# 实测（2026-09-25，我方 83.4 万首）：
#   精确题名候选 5,122 + 主干候选 3,018 = 8,140
#   随机取 4,000 对**不同**的诗，字符二元组 Dice 相似度 p99 = 0.02、最大 0.04
#   ⇒ 阈值取 0.6 是噪声上限的 15 倍，接受 7,463 首
# 换句话说：这个阈值几乎不可能把「另一首诗」判成同一首，
# 它筛掉的都是**版本异文过多**（如「一迳」vs「一径」）导致正文对不齐的真匹配。
# ---------------------------------------------------------------------------

#: 宫调前缀（元曲/词）。必须在归一化**之前**剥 —— 归一化会把 `·` 当标点删掉。
_GONGDIAO = re.compile(
    r"^(双调|中吕|南吕|仙吕|正宫|越调|商调|般涉调|大石调|小石调|黄钟|高平调|歇指调|林钟商)[·、\s]*"
)

#: 组诗序数后缀：`关山月二首其一` → `关山月二首` → `关山月`。要反复剥。
_ORDINAL = re.compile(
    r"(其[一二三四五六七八九十]+|第[一二三四五六七八九十]+|[二三四五六七八九十]+首|[一二三四五六七八九十]+绝句)$"
)

_BODY_PUNCT = re.compile(r"[\s\u3000，。！？、；：\u201c\u201d\u2018\u2019《》（〉〈〉【】\[\]—…·,.!?;:\"'()\-]")


def title_stem(title: str | None) -> str:
    """题名主干：剥宫调、取 `·` 之前、反复剥组诗序数。

    **顺序不可换**：`双调·寿阳曲·江天暮雪` 必须先在原始串上剥宫调与 `·`，
    再去归一化；反过来做的话 `·` 已经没了，`夜行船·忆昔西都欢纵` 会变成
    `夜行船忆昔西都欢纵`，永远对不上我方的 `夜行船`。
    """
    text = (title or "").strip()
    text = _GONGDIAO.sub("", text)
    text = re.split(r"[·•・]", text)[0]
    text = normalize_key(text)
    for _ in range(3):
        stripped = _ORDINAL.sub("", text)
        if stripped == text:
            break
        text = stripped
    return text


def body_key(text: str | None) -> str:
    """正文的归一化：剥 HTML、剥标点空白、NFKC。

    用于相似度比对 —— 只关心「是不是同一串字」，标点与换行不该影响判定，
    因为上游与我方的断句/标点习惯完全不同。
    """
    import unicodedata

    plain = re.sub(r"<[^>]+>", "", text or "")
    return unicodedata.normalize("NFKC", _BODY_PUNCT.sub("", plain))


def _bigrams(text: str, size: int = 2) -> set[str]:
    if len(text) < size:
        return {text} if text else set()
    return {text[i : i + size] for i in range(len(text) - size + 1)}


def similarity(a: str, b: str) -> float:
    """字符二元组 Dice 系数，0~1。

    选它而不是子串包含：两边的正文存在**版本异文**（`一迳入云斜` vs `一径入云斜`、
    `便留于道士` vs `便于于道上`），一比就能看到 39/40、148/152 字相同 ——
    子串判定会把这些**正确的匹配全部误杀**（实测精确同名也只过 46%）。
    """
    left, right = _bigrams(a), _bigrams(b)
    if not left or not right:
        return 0.0
    return 2 * len(left & right) / (len(left) + len(right))


#: 接受阈值。见本节开头的实测依据（噪声上限 0.04 的 15 倍）。
MATCH_THRESHOLD = 0.6


def match(
    upstream_author: str | None,
    upstream_title: str | None,
    upstream_body: str,
    exact_index: dict[tuple[str, str], tuple[int, str]],
    stem_index: dict[tuple[str, str], list[tuple[int, str]]],
) -> tuple[int, str, float] | None:
    """把上游一条配到我方某首诗。返回 `(poem_id, 匹配方式, 相似度)`，配不上返回 `None`。

    两级尝试：
    1. **精确题名** —— 直接取，再看正文相似度是否过阈；
    2. **题名主干** —— 同作者下取主干候选里正文最像的那一首。

    第 2 级必须带正文把关：同作者同词牌往往有好几首（辛弃疾光是《好事近》就有多首），
    只按主干取「第一个」会把注疏挂到**错的那一首**上 —— 那比没有注疏更坏。
    """
    author = normalize_key(upstream_author)
    if not author or not upstream_body:
        return None

    hit = exact_index.get((author, normalize_key(upstream_title)))
    if hit is not None:
        score = similarity(upstream_body, hit[1])
        return (hit[0], "exact", score) if score >= MATCH_THRESHOLD else None

    candidates = stem_index.get((author, title_stem(upstream_title)))
    if not candidates:
        return None
    best_id, best_score = max(
        ((poem_id, similarity(upstream_body, body)) for poem_id, body in candidates),
        key=lambda pair: pair[1],
    )
    return (best_id, "stem", best_score) if best_score >= MATCH_THRESHOLD else None


def parse_row(row: dict) -> dict | None:
    """上游一行 → 注疏库的一行（不含 `poem_id`，由导入脚本填）。

    **没有任何可用内容的行返回 `None`** —— 上游 43.4 万行里只有 1.14 万首有注疏，
    其余都是空壳，不该在库里留下空行。
    """
    gushiwen = row.get("tb_gushiwen") or {}
    title = (gushiwen.get("nameStr") or "").strip()
    author = (gushiwen.get("author") or "").strip()
    if not title:
        return None

    fanyis = (row.get("tb_fanyis") or {}).get("fanyis") or []
    shangxis = (row.get("tb_shangxis") or {}).get("shangxis") or []
    translation, annotation = parse_fanyi(fanyis)
    appreciation, background, citation = parse_shangxi(shangxis)

    if not any((translation, annotation, appreciation, background)):
        return None

    return {
        "translation": translation,
        "annotation": annotation,
        "appreciation": appreciation,
        "background": background,
        "citation": citation,
        "source": SOURCE,
        "source_url": source_url(title),
        "license": LICENSE,
        "upstream_title": title,
        "upstream_author": author or None,
    }
