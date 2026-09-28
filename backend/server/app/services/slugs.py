"""筛选维度的 URL slug 映射。

`docs/02 §3.7` 要求 `/facets` 同时给出 `key`（`tang`）与 `name`（`唐`）：
**`key` 供在线查询，`name` 供本地查询与展示**（离线包里存的是中文名）。
slug 必须稳定且 ASCII —— 出现在 URL 查询串里，中文会被转义成一长串 `%E5%94%90`。

⚠️ **待办**：这张表本质是 `docs/01 §5.2` 朝代映射的一部分，理应与 `dynasty` 表同源
（在 `etl/mappings/sources.json` 的 `dynasties` 里加 `key` 字段并写进库表）。
当前放在服务端是因为 ETL 已在跑、不宜中途改 schema；下次重跑 ETL 时应迁进 `dynasty.key`，
本文件随之删除。**两处不一致时以本文件为准**（它是 API 契约的执行者）。
"""

from __future__ import annotations

#: 大期 → slug
PERIOD_KEYS: dict[str, str] = {
    "先秦": "xianqin",
    "秦汉": "qinhan",
    "魏晋南北朝": "weijin-nanbeichao",
    "隋唐五代": "suitangwudai",
    "宋辽金": "songliaojin",
    "元": "yuan",
    "明": "ming",
    "清": "qing",
    "近现代": "jinxiandai",
    "当代": "dangdai",
}

#: 朝代 → slug
DYNASTY_KEYS: dict[str, str] = {
    "先秦": "xianqin",
    "秦": "qin",
    "汉": "han",
    "魏晋": "weijin",
    "南北朝": "nanbeichao",
    "隋": "sui",
    "唐": "tang",
    "五代": "wudai",
    "宋": "song",
    "辽": "liao",
    "金": "jin",
    "元": "yuan",
    "元末明初": "yuanmo-mingchu",
    "明": "ming",
    "明末清初": "mingmo-qingchu",
    "清": "qing",
    "清末民国初": "qingmo-minguochu",
    "清末近现代初": "qingmo-jinxiandaichu",
    "民国末当代初": "minguo-mo-dangdaichu",
    "近现代": "jinxiandai",
    "近现代末当代初": "jinxiandai-mo-dangdaichu",
    "当代": "dangdai",
}

#: slug → 朝代（反向表，供 `query.resolve_dynasty` 把 `/facets` 发布的 `key` 还原成库里的 `name`）。
#: 所有 value 唯一，故反向安全。
DYNASTY_KEYS_REVERSE: dict[str, str] = {v: k for k, v in DYNASTY_KEYS.items()}

#: 体裁 → slug。枚举**仅三个值**（docs/01 §5.3）
GENRE_KEYS: dict[str, str] = {
    "诗": "shi",
    "词": "ci",
    "辞": "ci_sao",
}


def period_key(name: str) -> str:
    return PERIOD_KEYS.get(name, name)


def dynasty_key(name: str) -> str:
    return DYNASTY_KEYS.get(name, name)


def dynasty_name(key: str) -> str:
    """key（tang）→ 库里的中文名（唐）。

    找不到时原样返回，从而兼容「直接传中文名（唐）」的调用方（docs/02 §3.7 的契约
    是 `key` 供在线查询，但允许两路都能解析，避免旧客户端/手测被 400）。
    """
    return DYNASTY_KEYS_REVERSE.get(key, key)


def genre_key(name: str) -> str:
    return GENRE_KEYS.get(name, name)
