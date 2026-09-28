"""LLM 适配器 —— 只做两件事：把消息发给模型、把 token 一个个吐出来。

## 为什么是流式

方式一是**交互式问答**：用户盯着屏幕等回答。非流式下 9B 本地模型要几十秒才吐第一个字，
用户会以为卡死了；流式下首字通常在 1–3 秒内出现，等待变成「看得见的进展」。
所以这个模块只有流式接口，没有 `chat()`。

## 两种 provider，一个理由

`ollama` 走**原生** `/api/chat`，`openai` 走兼容的 `/v1/chat/completions`。
不统一走 OpenAI 兼容层，是因为本地 Ollama 的兼容层**不认 `think` 字段**，
而关掉思考链是本项目的性能命门（实测同一任务 445 s → 28 s，约 15 倍，
见 `config.llm_disable_thinking`）。一个字段值 15 倍，值得为它多写一个分支。

换来的是：把 `SHICI_LLM_PROVIDER` 改成 `openai`、`SHICI_LLM_BASE_URL` 指向
DeepSeek / 硅基流动 / 自建 vLLM，代码一行不用动 —— 而云端模型能把首字延迟
进一步压到 1 秒内，是这个本地硬件下想要更好体验的唯一出路。
"""

from __future__ import annotations

import json
import logging
import time
from collections.abc import Iterator
from dataclasses import dataclass

import httpx

from ..config import settings

logger = logging.getLogger("shici.llm")


class LlmError(RuntimeError):
    """LLM 侧失败。调用方要把它变成流里的一个 error 事件，而不是 500。"""


@dataclass
class ToolCall:
    """模型发起的一次工具调用。"""

    name: str
    arguments: dict


@dataclass
class Chunk:
    """流式产出的一段。

    **为什么要包一层而不是直接吐 `str`**：工具调用模式下同一条流里既可能有正文
    （有的模型会先说一句「我查一下」再调工具），也可能有工具调用，
    裸字符串无法区分。客户端只需要 `text`，但编排层必须看到 `tool_calls`。
    """

    text: str = ""
    tool_calls: list[ToolCall] | None = None


def _tool_calls_from_ollama(message: dict) -> list[ToolCall] | None:
    raw = message.get("tool_calls") or []
    if not raw:
        return None
    calls: list[ToolCall] = []
    for item in raw:
        function = item.get("function") or {}
        name = function.get("name")
        if not name:
            continue
        arguments = function.get("arguments")
        # Ollama 可能直接给 dict，也可能给 JSON 字符串，两种都要能吃
        if isinstance(arguments, str):
            try:
                arguments = json.loads(arguments or "{}")
            except json.JSONDecodeError:
                arguments = {}
        calls.append(ToolCall(name=name, arguments=arguments or {}))
    return calls or None


def _accumulate_openai_tool_fragment(buffer: dict[int, dict], fragment: dict) -> None:
    """OpenAI 兼容层把一次工具调用**按片段**流式下发（`index` 分片、`arguments` 增量），
    必须按 index 累积后再解析，否则拿到的是残缺 JSON。"""
    index = int(fragment.get("index") or 0)
    slot = buffer.setdefault(index, {"name": "", "arguments": ""})
    if fragment.get("id"):
        slot["id"] = fragment["id"]
    function = fragment.get("function") or {}
    if function.get("name"):
        slot["name"] = function["name"]
    if function.get("arguments"):
        slot["arguments"] += function["arguments"]


def _tool_calls_from_openai(buffer: dict[int, dict]) -> list[ToolCall] | None:
    calls: list[ToolCall] = []
    for slot in buffer.values():
        if not slot.get("name"):
            continue
        try:
            arguments = json.loads(slot.get("arguments") or "{}")
        except json.JSONDecodeError:
            continue
        calls.append(ToolCall(name=slot["name"], arguments=arguments))
    return calls or None


def _timeout() -> float:
    return float(settings.llm_timeout_seconds)


def _iter_lines(response: httpx.Response) -> Iterator[str]:
    """逐行读响应体。

    `iter_lines` 在 httpx 里对 SSE 是安全的；这里只做去空行，具体协议解析交给各 provider。
    """
    for line in response.iter_lines():
        if line:
            yield line


@dataclass
class StreamStats:
    """流结束后才有值。用于日志诊断。"""

    chunks: int = 0
    seconds: float = 0.0
    model: str = ""


def _stream_ollama(
    messages: list[dict], stats: StreamStats, tools: list[dict] | None
) -> Iterator[Chunk]:
    """Ollama 原生 `/api/chat`，`stream=true`。

    响应是**换行分隔的 JSON**（不是 SSE）：每行 `{"message":{"content":"…"},"done":false}`，
    最后一行 `done=true` 并带 `eval_count`。

    关键参数：`think: false` 关思考链（性能命门）、`num_predict` 给足避免长回答被截断。
    """
    payload: dict = {
        "model": settings.llm_model,
        "messages": messages,
        "stream": True,
        "think": not settings.llm_disable_thinking,
        "options": {
            "temperature": settings.llm_temperature,
            "num_predict": settings.llm_max_tokens,
        },
    }
    if tools:
        payload["tools"] = tools
    try:
        with httpx.stream(
            "POST", f"{settings.llm_base_url}/api/chat", json=payload, timeout=_timeout()
        ) as response:
            if response.status_code >= 400:
                response.read()
                raise LlmError(f"模型返回 {response.status_code}：{response.text[:200]}")
            for line in _iter_lines(response):
                try:
                    body = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if body.get("error"):
                    raise LlmError(f"模型报错：{body['error']}")
                stats.model = body.get("model") or stats.model
                message = body.get("message") or {}
                delta = message.get("content") or ""
                if delta:
                    stats.chunks += 1
                    yield Chunk(text=delta)
                if body.get("done"):
                    # 工具调用只在收尾那一行给出，所以放在这里而不是每帧都看
                    calls = _tool_calls_from_ollama(message)
                    if calls:
                        yield Chunk(tool_calls=calls)
                    return
    except httpx.HTTPError as exc:
        raise LlmError(f"本地模型不可达：{type(exc).__name__}: {exc}") from exc


def _stream_openai(
    messages: list[dict], stats: StreamStats, tools: list[dict] | None
) -> Iterator[Chunk]:
    """任意 OpenAI 兼容 `/v1/chat/completions`，`stream=true`（SSE）。"""
    payload = {
        "model": settings.llm_model,
        "messages": messages,
        "stream": True,
        "temperature": settings.llm_temperature,
        "max_tokens": settings.llm_max_tokens,
    }
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    headers = {"Content-Type": "application/json"}
    if settings.llm_api_key:
        headers["Authorization"] = f"Bearer {settings.llm_api_key}"

    try:
        with httpx.stream(
            "POST",
            f"{settings.llm_base_url}/chat/completions",
            json=payload,
            headers=headers,
            timeout=_timeout(),
        ) as response:
            if response.status_code >= 400:
                response.read()
                raise LlmError(f"模型返回 {response.status_code}：{response.text[:200]}")
            # 工具调用片段按 index 累积；OpenAI 兼容层会把 `arguments` 切成好几段下发
            tool_buffer: dict[int, dict] = {}
            for line in _iter_lines(response):
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                # SSE 的结束哨兵，不是 JSON
                if data == "[DONE]":
                    return
                try:
                    body = json.loads(data)
                except json.JSONDecodeError:
                    continue
                stats.model = body.get("model") or stats.model
                choices = body.get("choices") or []
                if not choices:
                    continue
                chunk = choices[0].get("delta") or {}
                delta = chunk.get("content") or ""
                if delta:
                    stats.chunks += 1
                    yield Chunk(text=delta)
                # 工具调用是**按片段**下发的，累积到缓冲区；正文与工具片段可能交替出现
                for fragment in chunk.get("tool_calls") or []:
                    _accumulate_openai_tool_fragment(tool_buffer, fragment)
                if choices[0].get("finish_reason") == "tool_calls":
                    calls = _tool_calls_from_openai(tool_buffer)
                    if calls:
                        yield Chunk(tool_calls=calls)
    except httpx.HTTPError as exc:
        raise LlmError(f"模型服务不可达：{type(exc).__name__}: {exc}") from exc


def stream_chat(
    messages: list[dict], tools: list[dict] | None = None
) -> Iterator[Chunk]:
    """流式产出回答片段与工具调用。

    **不在这一层做重试**：流已经开始吐字之后再重试，用户会看到同一句话的两个开头。
    重试的责任在调用方 —— 它知道流有没有开始（见 `services/ask.py` 的注释）。
    """
    stats = StreamStats()
    started = time.monotonic()
    if settings.llm_provider == "ollama":
        generator = _stream_ollama(messages, stats, tools)
    elif settings.llm_provider == "openai":
        generator = _stream_openai(messages, stats, tools)
    else:
        raise LlmError(f"未知的 LLM provider：{settings.llm_provider}")

    try:
        yield from generator
    finally:
        stats.seconds = time.monotonic() - started
        if stats.chunks:
            logger.info(
                "流式作答完成：%d 段 / %.1fs / %s", stats.chunks, stats.seconds, stats.model
            )


def ping() -> dict:
    """连通性自检。给 `/healthz` 与排查用。

    只发一个极短的问题，验证「模型能不能流式回字」—— 不覆盖长回答等路径，
    但能抓到「服务没起 / 模型名写错」这类最常见的故障。
    """
    started = time.monotonic()
    chunks = 0
    try:
        for chunk in stream_chat([{"role": "user", "content": "只回复一个字：好"}]):
            if chunk.text:
                chunks += 1
            if chunks >= 3:
                break
        return {"ok": True, "model": settings.llm_model,
                "first_chunk_seconds": round(time.monotonic() - started, 2)}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "model": settings.llm_model, "error": f"{type(exc).__name__}: {exc}"}
