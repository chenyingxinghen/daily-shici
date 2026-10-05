"""文本归一与派生字段计算。

这是 ETL 管线里 `normalize` 与 `derive` 两个阶段的共用逻辑，
被内置包构建与全量管线同时使用，故单独成模块。

规则全部来自 `docs/01-数据源与ETL.md` §4.2 / §5.1 / §5.4，改动时请同步文档。
"""

from __future__ import annotations

import re
import unicodedata

# ---------------------------------------------------------------------------
# 标点与空白
# ---------------------------------------------------------------------------

#: 需要从正文里剔除的标点（中英文都要，上游已做英文→中文标点转换，但不能假设做全了）
_PUNCTUATION = set(
    "，。、；：？！…—～·「」『』《》〈〉（）〔〕【】〖〗"
    ",.;:?!\"'`~-_/\\|()[]{}<>*&#@$%^+="
    "\u2018\u2019\u201c\u201d"  # 弯引号
    "\u3000\u00a0"             # 全角空格、不换行空格
)

#: 归一化时剔除的空白（含全角空格与零宽字符）
_WHITESPACE = set(" \t\r\n\u3000\u00a0\u200b\ufeff")


def strip_punctuation(text: str) -> str:
    """去掉全部中文/英文标点。用于 char_count 与去重键。"""
    return "".join(ch for ch in text if ch not in _PUNCTUATION)


def strip_whitespace(text: str) -> str:
    return "".join(ch for ch in text if ch not in _WHITESPACE)


def count_chars(content: str) -> int:
    """字数 = 去空白**且去标点**后的字符数（docs 01 §5.1）。

    注意不是 `len(content)`：后者会把标点算进去，导致「五言绝句 24 字」
    这种明显不对的数字出现在客户端。
    """
    return len(strip_punctuation(strip_whitespace(content)))


def count_lines(content: str) -> int:
    return content.count("\n") + 1


def first_line(content: str) -> str:
    """`excerpt` = 正文第一行（docs 02 §2.1）。空行跳过。"""
    for line in content.split("\n"):
        stripped = line.strip()
        if stripped:
            return stripped
    return ""


def normalize_author(author: str) -> str:
    """作者归一（docs 01 §4.2）。

    - 去所有空白
    - 剥离括注：`李白（唐）` → `李白`
    - 剥离上游残留的 `〖〗`
    - 空作者统一为 `作者待考`（docs 01 §8 要求非空率 100%）
    """
    name = strip_whitespace(author or "")
    name = re.sub(r"[（(【\[][^）)】\]]*[）)】\]]", "", name)
    name = name.replace("〖", "").replace("〗", "")
    name = unicodedata.normalize("NFKC", name).strip()
    return name or "作者待考"


def dedup_key(author: str, content: str) -> tuple[str, str]:
    """去重键 = `(author_norm, content_norm[:80])`（docs 01 §4.2）。

    **标题不参与** —— 同一首诗在不同选本中标题常带异文或卷次后缀
    （《凉州词二首 一》vs《凉州词》），带标题会漏去一大批。
    取前 80 字：足以区分绝大多数诗，同时容忍尾联异文。
    """
    return normalize_author(author), strip_punctuation(strip_whitespace(content))[:80]


# ---------------------------------------------------------------------------
# 词牌
# ---------------------------------------------------------------------------

#: 标题里常见的词牌分隔符：`菩萨蛮·书江西造口壁` / `浣溪沙 一曲新词酒一杯`
_CIPAI_SEPARATORS = ("·", "・", "•", "　")


#: 断「句」的字符。**逗号必须在内** —— 古典诗的「一句」以逗号或句号收尾
#: （`春眠不觉晓，处处闻啼鸟。` 是两个五言句，不是一个十字句）。
#: 只切句号会让每句都变成 10 字，于是「五言绝句」被整体误判。
_VERSE_ENDINGS = "，。！？；：、"

#: 常用词牌引导表。
#:
#: 只在标题**没有分隔符**、且整串就是词牌时用得上（如标题恰好是「水调歌头」）。
#: 这张表只覆盖最常见的部分，其余靠 `build_cipai_candidates` 从数据里频次推断 ——
#: 靠外部词典文件的做法留待全量管线（词典约 1000 条，属独立数据文件）。
COMMON_CIPAI = frozenset(
    """菩萨蛮 忆江南 浣溪沙 临江仙 蝶恋花 水调歌头 念奴娇 鹧鸪天 满江红 沁园春
    一剪梅 虞美人 相见欢 浪淘沙 定风波 雨霖铃 青玉案 醉花阴 如梦令 点绛唇
    渔家傲 江城子 苏幕遮 卜算子 清平乐 西江月 南乡子 望江南 木兰花 踏莎行
    玉楼春 生查子 阮郎归 采桑子 诉衷情 少年游 乌夜啼 破阵子 千秋岁 谒金门
    天仙子 更漏子 河传 南歌子 何满子 长相思 醉太平 声声慢 凤凰台上忆吹箫 扬州慢
    暗香 疏影 齐天乐 高阳台 瑞鹤仙 摸鱼儿 兰陵王 六丑 花犯 解语花
    拜星月慢 尉迟杯 西河 大酺 琐窗寒 渡江云 忆旧游 风入松 一萼红 霓裳中序第一
    绮罗香 双双燕 东风第一枝 三姝媚 庆春宫 曲游春 月下笛 湘春夜月 眉妩 长亭怨慢
    淡黄柳 惜红衣 琵琶仙 玲珑四犯 侧犯 法曲献仙音 秋宵吟 凄凉犯 翠楼吟 杏花天影
    一剪梅 眼儿媚 好事近 桃源忆故人 烛影摇红 秋波媚 朝中措 太常引 夜行船 霜天晓角
    小重山 唐多令 画堂春 醉桃源 武陵春 燕归梁 锦缠道 殢人娇 系裙腰 撼庭秋"""
    .split()
)


def extract_cipai(title: str, known: frozenset[str] | set[str] | None = None) -> str | None:
    """从标题提取词牌（docs 01 §5.4）。

    三条路径，按可靠性从高到低：

    1. 有分隔符（`·` / 全角空格 / 半角空格）→ 取前段。**最可靠**，直接用。
    2. 无分隔符，但整串命中词牌词表（[COMMON_CIPAI] 或数据推断出的候选）→ 整串即词牌。
       `水调歌头` 这种标题没有任何分隔符，若不做这一步会白白丢掉高价值字段。
    3. 都未命中 → 返回 None。

    **返回 None 好于猜一个错的词牌** —— 客户端已正确处理 null。
    """
    if not title:
        return None

    for sep in _CIPAI_SEPARATORS:
        if sep in title:
            head = title.split(sep, 1)[0].strip()
            if head:
                return head

    if " " in title:
        head = title.split(" ", 1)[0].strip()
        # 首段足够短才认为是词牌，否则可能把诗句当成词牌
        if head and len(head) <= 7:
            return head

    plain = title.strip()
    table = set(COMMON_CIPAI)
    if known:
        table |= set(known)
    if plain in table:
        return plain
    return None


def build_cipai_candidates(titles: list[str], min_occurrences: int = 2) -> set[str]:
    """从数据里推断词牌候选：**重复出现的短标题**几乎必然是词牌。

    理由：词牌是形式名，一个词牌下会有多位作者的多首作品；而具体词作的标题
    （如「书江西造口壁」）不会重复。故「短 + 重复」是相当强的信号，
    且完全不依赖外部词典。

    只对 `genre=词` 的标题调用。
    """
    counts: dict[str, int] = {}
    for raw in titles:
        title = (raw or "").strip()
        # 带分隔符的标题，词牌部分已被规则 1 拿到，不参与统计
        if not title or any(sep in title for sep in _CIPAI_SEPARATORS) or " " in title:
            continue
        if len(title) > 5:
            continue
        counts[title] = counts.get(title, 0) + 1
    return {t for t, c in counts.items() if c >= min_occurrences}


# ---------------------------------------------------------------------------
# 诗体推断（docs 01 §5.4）
# ---------------------------------------------------------------------------


def split_into_verses(content: str) -> list[str]:
    """把正文切成**句**（而非行）。

    这一步不能省：上游的换行粒度是「联」，一行常含两句
    （`春眠不觉晓，处处闻啼鸟。`）。若按行统计长度，每行都是 10 字，
    于是五言绝句会被误判成「其他」—— 这正是最初实现的真实故障。
    """
    verses: list[str] = []
    for line in content.split("\n"):
        buf = []
        for ch in line:
            if ch in _VERSE_ENDINGS:
                piece = "".join(buf).strip()
                if piece:
                    verses.append(piece)
                buf = []
            else:
                buf.append(ch)
        tail = "".join(buf).strip()
        if tail:
            verses.append(tail)
    return verses


def infer_form(content: str, genre: str) -> str | None:
    """推断诗体。**尽力而为字段**，客户端不得用于核心逻辑分支。

    规则（docs 01 §5.4，以「句」为单位计）：
    - 各句字数不一致 → 杂言
    - 各句一致且为 5/7 字：4 句 = 绝句，8 句 = 律诗，>8 句 = 古体
    - 各句一致且为 4/6 字 → 四言/六言
    - 其他 → 其他
    """
    if genre != "诗":
        return None

    verses = split_into_verses(content)
    if not verses:
        return None

    lengths = {count_chars(v) for v in verses}
    if len(lengths) != 1:
        return "杂言"

    unit = lengths.pop()
    count = len(verses)

    if unit in (5, 7):
        prefix = "五言" if unit == 5 else "七言"
        if count == 4:
            return f"{prefix}绝句"
        if count == 8:
            return f"{prefix}律诗"
        if count > 8:
            return f"{prefix}古体"
        return "古体"
    if unit == 4:
        return "四言"
    if unit == 6:
        return "六言"
    return "其他"


def clean_content(content: str) -> str:
    """正文清洗：统一换行、去掉行首尾空白、丢弃空行、压缩连续空行。

    **不做繁简转换** —— 上游刻意保留少量生僻字繁体以保证 Android/Linux 正常显示
    （docs 01 §3.4），二次转换会误伤。
    """
    text = (content or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = [ln.strip() for ln in text.split("\n")]
    kept = [ln for ln in lines if ln]
    return "\n".join(kept)
