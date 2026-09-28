"""方式一：用户就具体词句提问的**流式问答**（`docs/06 §6`）。

## 与方式二的分工

| | 方式二（注疏） | 方式一（本模块） |
|---|---|---|
| 内容 | 从网络导入的**真人注疏** | 模型**现场作答** |
| 覆盖 | 7,388 首（0.88%） | **任意一首** |
| 延迟 | 零（纯查表） | 首字 1–3 s，随回答增长 |
| 落库 | 是（`annotation_db`） | **否** —— 问答不写库 |

两者不是替代关系：注疏解决「经典篇目读得深」，问答解决「冷门诗/具体疑问有人答」。
用户问的问题千奇百怪（「这个字怎么读」「为什么用这个典故」「跟另一首比怎么样」），
预制注疏永远覆盖不全，这才是问答存在的理由。

## 流程

```
question + poem
  → search.gather()        按需检索（约 2 s），拿到资料与来源
  → build_messages()       正文 + 资料 + 严格边界声明
  → llm.stream_chat()      逐字产出
```

## ⚠️ 检索失败不阻断作答

检索挂了（网络、限额）就**退化为只用原文作答**，而不是报错 ——
用户问「这首诗的作者是谁」本来就不需要检索，为检索失败拒答是舍本逐末。
但此时必须**在系统提示里明确告知模型「没有资料」**，让它更保守（不知道就说不知道），
否则它会用权重里的模糊印象补出一个像模像样的错误答案。

## ⚠️ 不落库

问答结果**不写进 `annotation_db`**。理由：一次问答是针对**某个具体问题**的，
不是对整首诗的系统注疏；把它塞进注疏字段会让「这首诗有没有注疏」变成一个
含义模糊的状态。用户觉得答得好，那是模型答得好，不代表这首诗被收录了。
"""

from __future__ import annotations

import logging
import re
from collections.abc import Iterator
from dataclasses import dataclass

from . import llm, search
from ..config import settings

logger = logging.getLogger("shici.ask")

#: SSE 事件的类型。客户端按 `event` 分支，`data` 一律是 JSON。
EVENT_SOURCES = "sources"
EVENT_DELTA = "delta"
EVENT_ERROR = "error"
EVENT_DONE = "done"

_SYSTEM_WITH_EVIDENCE = """你是中国古典诗词的讲解者，正在回答用户关于某一首诗的疑问。

铁则：
1. **优先依据「本诗已有的注疏」与「参考资料」及诗的原文**。后者是检索来的公开网页内容。
2. 资料不足以回答时，**明确说「这一点资料里没有明确记载」**，然后只谈你能从原文
   直接确认的部分。**绝不编造**典故出处、生卒年、地名、异文。
3. 用户问的是**字词、句意、典故、背景**这类具体问题，就事论事地回答，
   不要展开成整首诗的赏析 —— 用户没问那么多。
4. 语言简洁平实，像一位耐心的老师。**不要用 Markdown 标题**，
   不要「首先/其次/最后」这类套话。两三句话能说清就两三句话。
5. 涉及读音时给出拼音；涉及异文时说明是哪个版本。
6. ⚠️ **注音必须逐字核对**：同一个字的读音前后不能写成两种。注疏里标了音的一律以注疏为准。"""

_SYSTEM_TOOL = """你是中国古典诗词的讲解者，正在回答用户关于某一首诗的疑问。

**你有一个 `search` 工具，可以检索中国古典诗词的资料（注释、译文、赏析、典故、读音等）。**

何时调用（**默认应当调用，除非问题纯属字面理解**）：
- **以下情况一律先调用**：读音、生僻字词释义、典故出处、作者经历与生卒、地名沿革、
  版本异文、创作背景、与他人作品的比较。这些都是**考据性**内容，
  凭印象回答极易出错 —— 你上一次就把「怆然」的读音前后写成了两种。
- **可以不调用**：用户只问这句诗的字面意思、句法或明显修辞，
  且仅凭原文就能确定。这类问题调用工具只是浪费时间。
- ⚠️ **拿不准就调用。** 检索只要 2 秒，而答错会直接让用户读错一首诗 ——
  这个代价完全不对称，**倾向于多查一次**。

怎样组词（**实测经验，别忽略**）：
- 检索词要短、要**像搜索框里敲的那种短语**：`王昌龄 出塞 秦时明月汉时关`、
  `怆然 读音 登幽州台歌`。
- **不要**把整个问题原样丢进去（「这首诗里的生僻字词是什么意思？」这种句子
  实测命中 **0 条**，而同一首诗换成首句能命中 6 条）。
- 一定要带上**诗题或作者**来定位；如果问的是某个具体字词，把那个字词也放进去。

铁则：
1. 回答**只依据**本诗已有的注疏（若已给出）、检索结果与诗的原文。**绝不编造**
   典故出处、生卒年、地名、异文。
2. 资料不足以回答时，**明确说「这一点资料里没有明确记载」**，
   然后只谈你能从原文直接确认的部分。
3. 用户问的是**字词、句意、典故、背景**这类具体问题，就事论事地回答，
   不要展开成整首诗的赏析 —— 用户没问那么多。
4. 语言简洁平实，像一位耐心的老师。**不要用 Markdown 标题**，
   不要「首先/其次/最后」这类套话。两三句话能说清就两三句话。
5. 涉及读音时给出拼音；涉及异文时说明是哪个版本。
6. ⚠️ **注音必须逐字核对**：同一个字的读音前后不能写成两种。注疏里标了音的一律以注疏为准。"""

_SYSTEM_NO_EVIDENCE = """你是中国古典诗词的讲解者，正在回答用户关于某一首诗的疑问。

**注意：检索不到任何资料，也没有现成的注疏，你只能依据诗的原文作答。**

铁则：
1. 只谈你能**从原文直接确认**的内容（字面意思、句法、明显的修辞）。
2. 涉及典故出处、作者生卒、地名沿革、版本异文等**需要考据**的内容，
   一律回答「这个需要查证，我手头没有可靠资料」，**绝不凭印象编造**。
3. **读音尤其容易出错：没有把握就明说「读音请以辞书为准」，不要硬给。**
4. 回答简短平实，**不要用 Markdown 标题**，不要套话。"""

#: 暴露给模型的检索工具。**只此一个** —— 工具越少，小模型越不容易选错。
SEARCH_TOOLS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "search",
            "description": "检索中国古典诗词的资料（注释、译文、赏析、典故、读音等）。"
                           "需要考据性信息时调用；凭诗文本身就能回答的问题不必调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        # 这段描述是**实测调出来的**：不加这段约束，模型会把整句问句
                        # 原样塞进去，实测命中率是 0（见 `answer` 的 docstring）。
                        "description": "检索词。简短的关键词短语，必须含诗题或作者；"
                                       "若问的是具体字词，把该字词也带上。"
                                       "不要写完整句子或疑问句。",
                    },
                },
                "required": ["query"],
            },
        },
    }
]

#: 允许模型自主检索的轮次。**1 = 一轮**：再放宽命中率会继续升，
#: 但本地 9B 每多一轮就多约 20 s 才出第一个字（8 tok/s）。交互场景下不划算。
MAX_TOOL_ROUNDS = 1

_USER_TEMPLATE = """【诗】
{title}
{dynasty}·{author}

{content}
{annotation_block}
【参考资料】
{context}

【用户的问题】
{question}"""

_USER_NO_CONTEXT = """【诗】
{title}
{dynasty}·{author}

{content}
{annotation_block}
【用户的问题】
{question}"""

#: 本诗已有注疏的呈现模板。**放在参考资料之前** —— 位置即优先级：
#: 这是我们导入的真人编辑内容，可信度高于检索回来的泛泛网页。
_ANNOTATION_BLOCK = """
【本诗已有的注疏】（真人编辑内容，可靠，优先采信）
{annotation}
"""


@dataclass
class PoemContext:
    """`ask` 只需要诗词的这几个字段。**刻意不复用 `PoemDetail`** ——
    这一层不该耦合到响应模型，否则改契约会连带改问答。"""

    title: str
    author: str
    dynasty: str
    content: str
    #: 本诗已导入的注疏（方式二的产物）。有就喂给模型当**高可信证据**。
    #:
    #: ⚠️ 这不是锦上添花，是实测发现的必需项：问「『怆然』怎么读」时，
    #: 模型答出「"怆"（chuàng），"怆然"读音为 chuáng rán」—— 前后自相矛盾；
    #: 而我们的注疏里明明写着「怆（chuàng）然：悲伤凄恻的样子」。
    #: **手里有权威内容却不给模型，等于让它凭印象猜。**
    annotation: str | None = None


#: 拼音证据的真实形态：**紧挨着汉字的拉丁串** —— 语料里写作 `怆（chuàng）然`、
#: `将（qiāng）进酒` 这种，即汉字后紧跟（可含括号与声调的）拼音。
#:
#: ⚠️ 判据必须要求**紧邻汉字**，两处实测教训：
#:   1. 只找「任意拉丁串」会把英文页面标题当成拼音 —— 片段里出现
#:      `Bring in the Wine, by Li Bai`，导致守卫误判「有注音」而没生效；
#:   2. 也不能只算普通字母 —— `qiāng` 里只有 `qi` 两个，必须把带声调的字母也算进去。
_HAS_PINYIN = re.compile(
    # 汉字后**紧跟**括号或紧跟拼音；不允许中间有空格 ——
    # `將進酒 translation: Bring in the Wine` 这种「中文+空格+英文」不算注音。
    r"[\u4e00-\u9fff](?:[（(]\s*)?"
    r"[a-zA-Z\u0101\u00e1\u01ce\u00e0\u0113\u00e9\u011b\u00e8\u012b\u00ed\u01d0\u00ec\u014d\u00f3\u01d2\u00f2\u016b\u00fa\u01d4\u00f9\u01d6\u01d8\u01da\u01dc\u00fc]{2,}"
)

#: 问读音的问题
_ASK_PRONUNCIATION = ("怎么读", "读音", "拼音", "念什么", "怎么念")


def pronunciation_guard(question: str, context: str | None) -> str | None:
    """读音类问题的额外约束。

    **实测触发的修法**：问《鼓吹曲辞 将进酒》「『将』怎么读」时，检索到了 12 条资料，
    但资料里**没有任何拼音标注**（各诗词站点通常不标音）。模型仍然编了一个 ——
    「读音是 jiāng（一声）」，而正确是 **qiāng**。带注音的资料有，但不在这些 snippet 里。

    所以要在资料里确实没有拼音时**明令禁止猜读音**：宁可让用户去查辞书，
    也不能给一个错音 —— 读音错了，用户会一直读错这首诗。
    """
    if not any(keyword in (question or "") for keyword in _ASK_PRONUNCIATION):
        return None
    if context and _HAS_PINYIN.search(context):
        return None   # 资料里有注音，正常作答即可
    # ⚠️ 这段约束**必须进系统提示**而不是用户消息的末尾：
    # 实测放在消息尾部时 9B 模型会直接无视，甚至给出「并非读作 qiāng」这种反向的错误论断。
    return (
        "⚠️ 本次额外约束（针对读音问题）：\n"
        "检索到的资料里**没有出现任何拼音标注**，本诗的已有注疏中也未标音。\n"
        "此时**严禁给出读音** —— 凭印象给的读音极可能是错的，"
        "而用户会照着错音去读这首诗。\n"
        "正确做法：只解释这个字的意思与用法，并**明确写出**"
        "「资料中未标注读音，读音请以辞书为准」。"
    )


def build_messages(
    poem: PoemContext, question: str, context: str | None, use_tools: bool = False
) -> list[dict]:
    """组装提示词。

    **三套系统提示，按「手里有什么」选**（选错会直接伤害回答质量）：
    - 有资料 → [_SYSTEM_WITH_EVIDENCE]
    - 没有资料、但**模型有工具可自己查** → [_SYSTEM_TOOL]
      （⚠️ 此处**不能**用「无资料」那套 —— 那套会说「检索不到任何资料」，
      可工具还没跑，等于先骗模型一次，它就会走上保守路径而不再调工具）
    - 没有资料、也没有工具 → [_SYSTEM_NO_EVIDENCE]（最保守）

    **本诗已有注疏则单独成块并排在检索资料之前**，明说「可靠，优先采信」——
    它是真人编辑成果，与检索来的泛泛网页不是一个可信等级。
    """
    # 超长诗（如《离骚》级别的长诗）要截断，否则正文会把上下文预算吃光，
    # 资料反而进不去 —— 而用户问的几乎总是诗里的某一句，不需要全文。
    content = (poem.content or "")[: settings.ask_max_poem_chars]
    annotation_block = (
        _ANNOTATION_BLOCK.format(annotation=poem.annotation)
        if poem.annotation
        else ""
    )
    if context:
        template, system = _USER_TEMPLATE, _SYSTEM_WITH_EVIDENCE
    else:
        template = _USER_NO_CONTEXT
        system = _SYSTEM_TOOL if use_tools else _SYSTEM_NO_EVIDENCE
    # 守卫放在**系统提示**里：实测放用户消息尾部时 9B 模型会无视它
    guard = pronunciation_guard(question, context)
    system = f"{system}\n\n{guard}" if guard else system
    return [
        {"role": "system", "content": system},
        {
            "role": "user",
            "content": template.format(
                title=poem.title,
                dynasty=poem.dynasty,
                author=poem.author,
                content=content,
                annotation_block=annotation_block,
                context=context or "",
                question=question,
            ),
        },
    ]


def compose_annotation(annotation: dict | None) -> str | None:
    """把注疏库的一行拼成给模型看的纯文本。

    **只拼与「解释性」有关的字段**（注释/译文/创作背景），
    不拼 `citation` 与 `source_url` —— 那是给出处用的，塞进提示词只会占掉上下文预算。
    """
    if not annotation:
        return None
    parts: list[str] = []
    if annotation.get("annotation"):
        parts.append(f"注释：\n{annotation['annotation']}")
    if annotation.get("translation"):
        parts.append(f"译文：\n{annotation['translation']}")
    if annotation.get("background"):
        parts.append(f"创作背景：\n{annotation['background']}")
    if annotation.get("appreciation"):
        # 赏析通常最长，截断后再进提示词 —— 用户问的是具体词句，
        # 完整赏析对回答帮助有限，却会挤掉检索资料的预算。
        parts.append(f"赏析（节选）：\n{annotation['appreciation'][:600]}")
    return "\n\n".join(parts) if parts else None


#: 需要**考据**的问题关键词。命中即强制先给资料，不把「要不要检索」交给模型决定。
#:
#: 为什么要强制：实测问冷门诗《鼓吹曲辞 将进酒》「『将』怎么读」时，模型**没调工具**，
#: 凭记忆答出「读音为 jiàng… 在《将进酒》中读音为 jiāng」—— 自相矛盾且两个都是错的
#: （正确是 qiāng）。而同一首诗在**有检索结果**时它答的正是 qiāng。
#: 读音、典故、地名这类问题答错的代价是用户读错一首诗，
#: 而「让模型自己决定」在这种小模型上并不可靠（3 次里跑了 2 次，不稳定）。
_EVIDENCE_KEYWORDS = (
    "怎么读", "读音", "拼音", "念什么", "怎么念",
    "什么意思", "什么意思", "释义", "何意", "作何解",
    "典故", "出处", "出自", "化用",
    "创作背景", "写作背景", "为什么写", "背景",
    "生平", "经历", "哪一年", "何时", "何人", "作者是谁",
    "异文", "版本", "哪个版本",
)


def needs_evidence(question: str) -> bool:
    """这个问题是否**必须**有资料才能答。

    判定刻意用关键词而不是再问一次模型 —— 后者又是一轮 8 tok/s 的等待，
    而这个判断本身没有歧义到需要模型出马的程度。
    """
    text = (question or "").strip()
    return any(keyword in text for keyword in _EVIDENCE_KEYWORDS)


def answer(poem: PoemContext, question: str, use_search: bool = True) -> Iterator[tuple[str, dict]]:
    """产出 SSE 事件流：`(事件名, 数据字典)`。

    ## 两条检索路径，按问题类型分流

    | 问题类型 | 路径 | 理由 |
    |---|---|---|
    | **考据类**（读音/典故/背景/地名/异文/释义） | 服务端**先检索**再作答 | 答错代价太高，而模型自主决定是否检索在小模型上不稳定（实测 3 次跑 2 次） |
    | 其它（字面理解、修辞、整体感受） | **模型自主**调 `search` 工具 | 它能为「『怆然』怎么读」组出 `怆然 读音 登幽州台歌`，比固定模板准 |

    考据类走服务端预检索还有个**意外的好处：更快**。让模型先决定要不要搜
    要多花一轮（约 10–30 s），而预检索只要 2 s，跳过的这一轮正好抵掉检索开销。
    所以「强制检索」在这个硬件上**既不慢也不劣**，只是去掉了模型的错误选择权。

    两条路径都**保留工具定义**：模型拿到资料后若觉得不够，仍可自己再检索一次。

    ## 检索词的实测教训

    早期版本拼的是「朝代 作者 《标题》 **用户的问句**」，命中率极差：

    | 检索词 | 命中 |
    |---|---|
    | 唐 王昌龄 《横吹曲辞 出塞 一》 这首诗里的生僻字词是什么意思？ | **0 条** |
    | 王昌龄《横吹曲辞 出塞 一》 秦时明月汉时关，万里长征人未还。 | **6 条** |

    自然语言问句对搜索引擎是噪声，**正文首句才是对这首诗最强的锚点**。

    ## 三重兜底

    1. 模型选择直接作答（非考据类）→ 尊重它，不空等也不强制检索。
    2. 模型不会用工具（没调工具也没吐字）→ 回退固定模板检索并塞进上下文。
    3. 检索服务失败 → 不阻断，走无资料路径（提示词已声明「没有资料」）。

    ## 只给一轮工具调用

    多给几轮能继续提升命中率（模型可以换词再搜），但**每多一轮就多约 20 s 才出字**。
    一轮是交互场景的取舍 —— 用户要的是回答，不是深度研究。
    """
    if use_search and needs_evidence(question):
        yield from _evidence_first(poem, question)
    elif use_search:
        yield from _tool_mode(poem, question)
    else:
        yield EVENT_SOURCES, {"items": [], "searched": False}
        yield from _final_stream(build_messages(poem, question, None), False)


def _evidence_first(poem: PoemContext, question: str) -> Iterator[tuple[str, dict]]:
    """考据类问题：服务端先检索，资料直接进上下文，模型据此作答。"""
    evidence = _fallback_search(poem, question)
    searched = bool(evidence.sources)
    yield EVENT_SOURCES, {
        "items": [s.as_dict() for s in evidence.sources], "searched": searched,
    }
    # 资料已给全，但仍**保留工具** —— 冷门诗可能搜不到，模型可以自己换词再试一次
    messages = build_messages(poem, question, evidence.context or None, use_tools=not searched)
    yield from _final_stream(messages, searched)


def _tool_mode(poem: PoemContext, question: str) -> Iterator[tuple[str, dict]]:
    """非考据类问题：模型自主决定是否检索。"""
    # 第一轮**带工具定义**，且用「有工具可自查」那套系统提示
    # （不能用「无资料」那套，否则模型会先被误导到保守路径而不去调工具）
    messages = build_messages(poem, question, None, use_tools=True)
    sources: list[search.Source] = []
    searched = False

    for _ in range(MAX_TOOL_ROUNDS):
        pending_calls: list[llm.ToolCall] = []
        produced_text = False

        for chunk in llm.stream_chat(messages, tools=SEARCH_TOOLS):
            if chunk.text:
                # 有的模型会先说一句「我查一下」再调工具 —— 那也是回答的一部分，照常输出
                produced_text = True
                yield EVENT_DELTA, {"text": chunk.text}
            if chunk.tool_calls:
                pending_calls.extend(chunk.tool_calls)

        if pending_calls:
            sources = _run_tool_calls(pending_calls)
            searched = bool(sources)
            # 先给来源再继续：用户要在下一个字出现之前就知道系统查了什么
            yield EVENT_SOURCES, {
                "items": [s.as_dict() for s in sources], "searched": searched,
            }
            if not sources:
                break   # 没搜到就别再让它空转一轮
            messages = _append_tool_results(messages, pending_calls, sources)
            continue

        if not produced_text:
            # 模型既没调工具也没吐字 —— 多半是这个模型不会用工具。
            # 回退到固定模板检索，并且**必须把资料塞进上下文**，否则这次检索等于白做。
            fallback = _fallback_search(poem, question)
            if fallback.sources:
                sources = fallback.sources
                searched = True
                yield EVENT_SOURCES, {
                    "items": [s.as_dict() for s in sources], "searched": True,
                }
                messages = build_messages(poem, question, fallback.context)
        else:
            # 模型已直接作答完 —— 尊重它，不再强行检索
            yield EVENT_SOURCES, {"items": [], "searched": False}
        break

    yield from _final_stream(messages, searched)


def _run_tool_calls(calls: list[llm.ToolCall]) -> list[search.Source]:
    """执行模型发起的检索。未知工具名直接忽略 —— 模型偶尔会瞎编一个。"""
    sources: list[search.Source] = []
    for call in calls:
        if call.name != "search":
            logger.warning("模型调用了未定义的工具：%s", call.name)
            continue
        query = str(call.arguments.get("query") or "").strip()
        if not query:
            continue
        try:
            found = search.search(query)
        except Exception as exc:  # noqa: BLE001 - 检索失败不该中断问答
            logger.warning("模型发起的检索失败（query=%r）：%s", query, exc)
            continue
        logger.info("模型自主检索 query=%r → %d 条", query, len(found))
        sources.extend(found)
    return _dedupe_sources(sources)


def _dedupe_sources(sources: list[search.Source]) -> list[search.Source]:
    """按 URL 去重。模型可能一次发多个相近的 query，命中同一批站点，
    不去重会让同一页的摘要在提示词里出现两遍，白占上下文预算。"""
    seen: set[str] = set()
    result: list[search.Source] = []
    for source in sources:
        if source.url and source.url not in seen:
            seen.add(source.url)
            result.append(source)
    return result


def _tool_result_text(sources: list[search.Source]) -> str:
    """把检索结果拼成喂回模型的文本。"""
    if not sources:
        # 明确告诉模型「没搜到」，让它有机会换词或改走保守路径 ——
        # 比返回空字符串好，空串会让它以为工具没执行。
        return "（没有检索到任何结果。可以更简短地重试：只用诗题、作者，或所问的那个字词。）"
    blocks: list[str] = []
    for source in sources[:6]:
        text = (source.snippet or "").strip()[:700]
        if text:
            blocks.append(f"### {source.title}\n{text}")
    return "\n\n".join(blocks)[: settings.search_context_chars]


def _append_tool_results(
    messages: list[dict], calls: list[llm.ToolCall], sources: list[search.Source]
) -> list[dict]:
    """把工具结果接进消息列表。

    ⚠️ Ollama 与 OpenAI 都要求：assistant 那条（含 `tool_calls`）必须先出现，
    随后的 `tool` 消息才能对应上。缺了 assistant 那条会直接报错
    「messages with role tool must be a response to a preceeding message with tool_calls」。
    所以这里**必须原样补一条 assistant 消息**，不能只 append tool 结果。
    """
    updated = list(messages)
    updated.append({
        "role": "assistant",
        "content": "",
        "tool_calls": [
            {
                "type": "function",
                "function": {"name": call.name, "arguments": call.arguments},
            }
            for call in calls
        ],
    })
    for call in calls:
        updated.append({
            "role": "tool",
            "name": call.name,
            "content": _tool_result_text(sources),
        })
    return updated


def _fallback_search(poem: PoemContext, question: str) -> search.Evidence:
    """模型不会用工具时的兜底：走固定模板检索（首句做锚点）。"""
    try:
        return search.gather(question, poem.title, poem.author, poem.dynasty, poem.content)
    except Exception as exc:  # noqa: BLE001
        logger.warning("兜底检索也失败：%s", exc)
        return search.Evidence()


def _final_stream(messages: list[dict], searched: bool) -> Iterator[tuple[str, dict]]:
    """最后一轮：**不带工具定义**，强制模型输出正文而不是再调一次工具。

    不这么做的话，部分模型会在拿到工具结果后仍然回一个空的 tool_calls，
    用户就永远看不到回答。
    """
    produced = False
    try:
        for chunk in llm.stream_chat(messages):
            if chunk.text:
                produced = True
                yield EVENT_DELTA, {"text": chunk.text}
    except llm.LlmError as exc:
        # 已经吐过字就不再报错 —— 用户看到了半句回答，此时弹「出错了」只会困惑。
        # 只有**一个字都没出**才算真失败。
        if not produced:
            yield EVENT_ERROR, {"message": str(exc)}
            return
        logger.warning("流式作答中途失败（已有输出）：%s", exc)
    yield EVENT_DONE, {"ok": produced, "searched": searched}
