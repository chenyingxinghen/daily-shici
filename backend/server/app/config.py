"""服务端配置（`docs/03 §5.4` 的 `INCLUDE_COPYRIGHTED` 是这里最要紧的一项）。"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = SERVER_DIR.parent


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    # ---- 数据位置 ----
    db_path: Path = field(default_factory=lambda: Path(
        os.getenv("SHICI_DB", SERVER_DIR / "data" / "poems.db")))
    packs_dir: Path = field(default_factory=lambda: Path(
        os.getenv("SHICI_PACKS", REPO_ROOT / "backend" / "packs")))

    # ---- 版权开关（docs/02 §6.4）----
    #: 置 False 后 /poems、/search、/daily、/poems/random、/facets 的计数**全部**排除
    #: is_public_domain=false 的诗。**是运营决策，客户端不能控制。**
    include_copyrighted: bool = field(default_factory=lambda: _env_bool("INCLUDE_COPYRIGHTED", True))

    # ---- 每日一诗（docs/02 §4.1）----
    #: 候选池下限权重。W1=1 表示**不排除任何层**（默认池 = 全量）。
    daily_pool_min_weight: int = field(default_factory=lambda: _env_int("DAILY_POOL_MIN_WEIGHT", 1))
    #: 加权随机的层权重。W1 档内无区分能力（docs/02 §4.1 已注明），故落到 W1 时实为均匀。
    weight_w3: int = field(default_factory=lambda: _env_int("WEIGHT_W3", 100))
    weight_w2: int = field(default_factory=lambda: _env_int("WEIGHT_W2", 30))

    #: 每日选诗的滚动窗口（docs/02 §3.1）：过去 365 天、未来 90 天可查。
    daily_past_days: int = 365
    daily_future_days: int = 90
    #: 同一首诗多少天内不重复。
    daily_no_repeat_days: int = 365

    # ---- 分页与检索 ----
    default_limit: int = 20
    max_limit: int = 100
    #: 搜索串长度上限，超出返回 INVALID_PARAM（docs/03 §4.4）。
    max_query_len: int = 64
    #: bm25 列权重，实现「标题 > 作者 > 正文」（docs/02 §3.6）。**调参项。**
    bm25_title: float = 10.0
    bm25_author: float = 5.0
    bm25_content: float = 1.0

    # ---- 缓存 ----
    facets_cache_seconds: int = 86400
    db_cache_kb: int = 65536

    # ---- 注疏（`docs/06`）----
    #: 注疏库存**独立文件**。原因见 `docs/06 §4`：ETL 的 load 阶段会
    #: `unlink` 重建 `poems.db`（`etl/lib/pipeline.py:329`）。
    annotations_db_path: Path = field(default_factory=lambda: Path(
        os.getenv("SHICI_ANNOTATIONS_DB", SERVER_DIR / "data" / "annotations.db")))
    #: 总开关。置 False 时 `/poems/{id}` 不再合并注疏、注疏端点恒返 `missing`。
    annotations_enabled: bool = field(default_factory=lambda: _env_bool("SHICI_ANNOTATIONS", True))
    #: 是否回传参考来源与文献出处。**不要关** —— 它们是「内容有据可查」的凭据（`docs/06 §7`）。
    annotations_include_sources: bool = field(
        default_factory=lambda: _env_bool("SHICI_ANNOTATIONS_SOURCES", True))

    # ---- 检索（AnySearch）---- 方式一「AI 按需调用搜索工具」用的
    #: 搜索服务基址。换成别的检索服务时，只要实现 `services/search.py` 的同名接口即可。
    search_base_url: str = field(default_factory=lambda: os.getenv(
        "SHICI_SEARCH_BASE_URL", "https://api.anysearch.com").rstrip("/"))
    #: **可选**：AnySearch 匿名即可用，只是限额更低。有 key 时走 Bearer。
    search_api_key: str = field(default_factory=lambda: os.getenv("SHICI_SEARCH_API_KEY", ""))
    #: 单次检索返回条数（1–10，服务端上限 10）。
    search_max_results: int = field(default_factory=lambda: _env_int("SHICI_SEARCH_MAX_RESULTS", 6))
    #: 送给模型的最大资料字符数 —— 直接决定 prompt 长度与首字延迟。
    search_context_chars: int = field(default_factory=lambda: _env_int("SHICI_SEARCH_CONTEXT_CHARS", 6000))

    # ---- 生成（LLM，方式一的流式问答）----
    #: `ollama` = 本地 Ollama 原生 `/api/chat`（**必须走原生接口**，见 `llm_disable_thinking`）；
    #: `openai` = 任意 OpenAI 兼容 `/chat/completions`（DeepSeek / 硅基流动 / vLLM …）。
    llm_provider: str = field(default_factory=lambda: os.getenv("SHICI_LLM_PROVIDER", "ollama").strip().lower())
    llm_base_url: str = field(default_factory=lambda: os.getenv(
        "SHICI_LLM_BASE_URL", "http://127.0.0.1:11434").rstrip("/"))
    llm_api_key: str = field(default_factory=lambda: os.getenv("SHICI_LLM_API_KEY", ""))
    llm_model: str = field(default_factory=lambda: os.getenv("SHICI_LLM_MODEL", "qwen3.5:9b"))
    #: 问答温度。比注释任务高一点 —— 问答需要组织语言，但仍不该信口开河。
    llm_temperature: float = 0.3
    #: 单次回答的最大输出 token。
    llm_max_tokens: int = field(default_factory=lambda: _env_int("SHICI_LLM_MAX_TOKENS", 700))
    #: 关闭模型思考链。**影响首字延迟的关键项**，见 `docs/06 §6.2`。
    #:
    #: 实测（RTX 4050 Laptop 6 GB，qwen3.5:9b，8.67 GB 权重只有 4.43 GB 进显存、
    #: 其余溢出到 CPU，吞吐恒定约 8 tok/s）：
    #:   - 开启思考：同一任务 `completion_tokens` 3365、耗时 **445 s**，正文只有 357 字；
    #:   - 关闭思考：`eval_count` 221、耗时 **28 s**。
    #: 即约 15 倍。**问答是交互场景，这个差距直接决定能不能用。**
    #: Ollama 的 OpenAI 兼容层不认 `think` 字段，故 ollama provider 走原生 `/api/chat`。
    llm_disable_thinking: bool = field(
        default_factory=lambda: _env_bool("SHICI_LLM_DISABLE_THINKING", True))
    #: 流式请求超时（秒）。流式下这是「整段回答的上限」，本地 8 tok/s 时要留足。
    llm_timeout_seconds: int = field(default_factory=lambda: _env_int("SHICI_LLM_TIMEOUT", 600))
    #: 失败重试次数（不含首次）。
    llm_retries: int = field(default_factory=lambda: _env_int("SHICI_LLM_RETRIES", 1))

    # ---- 问答（方式一，`docs/06 §6`）----
    #: 单次提问的最大字数。过长的问题没有意义，而且会把上下文预算吃光。
    ask_max_question_chars: int = 200
    #: 是否强制先检索再作答。**默认开启** —— 古典诗词的考据性内容，
    #: 不检索就答等于让模型凭记忆编（见 `services/ask.py` 的说明）。
    ask_search_first: bool = field(default_factory=lambda: _env_bool("SHICI_ASK_SEARCH_FIRST", True))
    #: 往详情页塞的正文上限，防止超长诗把上下文撑满。
    ask_max_poem_chars: int = 2000

    @property
    def copyright_clause(self) -> str:
        """注入所有查询的 SQL 片段。

        ⚠️ **全项目唯一允许拼进 SQL 的字符串**（`docs/03 §5.4`）。值域只有两个字面量，
        绝不含用户输入；其余一切值一律走参数化占位符。

        ⚠️ **不能借 `weight` 实现这个开关** —— `data2/*` 并入 W1 后，受版权保护的诗
        与公有领域的 W1 诗权重同为 1，`weight >= 1` 过滤不掉任何东西。
        """
        return "1" if self.include_copyrighted else "p.is_public_domain = 1"


settings = Settings()
