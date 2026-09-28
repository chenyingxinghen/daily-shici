package com.example.daily_shici.data.remote

import android.util.Log
import com.example.daily_shici.BuildConfig
import com.example.daily_shici.domain.model.AnnotationSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

/**
 * 方式一的流式问答（`docs/06 §6`）。
 *
 * ## 为什么绕过 Retrofit
 *
 * Retrofit 为「一次请求换一个对象」设计，而 SSE 是**一条连接持续吐多个事件**。
 * 用 `@Streaming` + `ResponseBody` 也能做，但要把 `Callback` 转成 Flow、
 * 自己处理取消与超时，绕的圈一点不少，还多一层类型转换。
 * 这里直接用 OkHttp 的流式响应 —— 代码更短，取消语义也更清楚。
 *
 * ## 超时为什么这么长
 *
 * 本地 9B 模型实测约 **8 tok/s**（6 GB 显存装 8.67 GB 权重，溢出到 CPU），
 * 一段 200 字的回答要一分多钟。**读超时必须按「整段回答的上限」设**，
 * 不能按普通请求的 10 s —— 设短了会在回答中途把连接掐断，
 * 用户看到的是「说了一半突然没了」。
 *
 * ## 事件协议（与服务端 `routers/ask.py` 一致）
 *
 * ```
 * event: sources   data: {"items":[{"title","url"}],"searched":true}
 * event: delta     data: {"text":"幽"}
 * event: done      data: {"ok":true}
 * event: error     data: {"message":"…"}
 * ```
 */
@Singleton
class AskStream @Inject constructor(
    okHttpClient: OkHttpClient,
) {

    private val client: OkHttpClient = okHttpClient.newBuilder()
        // 连接与首字要快失败（服务没起时别让用户干等），但整段回答可以很慢
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 就某首诗提问，返回事件流。
     *
     * **不抛异常**：所有失败都以 [AskEvent.Failed] 的形式在流里出现 ——
     * 因为失败可能发生在流中途（已经吐了半句），此时抛异常会丢掉已渲染的内容。
     * 调用方只处理事件，不必再写一层 try/catch。
     */
    fun ask(poemId: Long, question: String): Flow<AskEvent> = callbackFlow {
        // 用 buildJsonObject 而不是手拼字符串：问题里出现引号或换行时，
        // 手拼会直接产出非法 JSON，服务端 400，而错误信息看起来像「网络问题」。
        val payload = buildJsonObject { put("question", question) }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}poems/$poemId/ask")
            .post(payload)
            .header("Accept", "text/event-stream")
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(AskEvent.Failed(e.message ?: "网络请求失败"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val body = res.body
                    if (!res.isSuccessful || body == null) {
                        // 服务端在这一层仍会返回结构化的错误 JSON（404 / 400），
                        // 直接读出来给用户比「HTTP 404」有意义得多。
                        trySend(AskEvent.Failed(readError(body, res.code)))
                        close()
                        return
                    }
                    try {
                        readEvents(body) { event -> trySend(event) }
                    } catch (e: Exception) {
                        // 读流中断（超时、服务重启）。此时可能已经渲染了半句回答，
                        // 报错要说得含糊些，别让用户以为整段都白读了。
                        Log.w(TAG, "读取问答流中断", e)
                        trySend(AskEvent.Failed("回答中断：${e.message ?: "连接已关闭"}"))
                    }
                }
                close()
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** 逐块解析 SSE。事件以空行分隔，一帧可能跨多次 `read` —— 靠 `readUtf8Line` 累积。 */
    private fun readEvents(body: ResponseBody, emit: (AskEvent) -> Unit) {
        val source = body.source()
        var eventName = ""
        val dataLines = mutableListOf<String>()

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    // 一帧结束
                    if (eventName.isNotEmpty()) {
                        emit(parseFrame(eventName, dataLines.joinToString("\n")))
                    }
                    eventName = ""
                    dataLines.clear()
                }
                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trimStart())
                // 其它行（注释、心跳）直接忽略。SSE 规范里以 `:` 开头的都是注释。
            }
        }
    }

    private fun parseFrame(event: String, data: String): AskEvent = when (event) {
        "sources" -> AskEvent.Sources(parseSources(data))
        "delta" -> AskEvent.Delta(runCatching {
            json.parseToJsonElement(data).jsonObject["text"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty())
        "done" -> AskEvent.Done(runCatching {
            // 用 content 再转布尔，而不是 `booleanOrNull` —— 后者在这个
            // kotlinx-serialization 版本里不存在。
            json.parseToJsonElement(data).jsonObject["searched"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false))
        "error" -> AskEvent.Failed(runCatching {
            json.parseToJsonElement(data).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull() ?: "生成失败")
        else -> AskEvent.Ignored
    }

    private fun parseSources(data: String): List<AnnotationSource> = runCatching {
        json.parseToJsonElement(data).jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val obj = item.jsonObject
            val url = obj["url"]?.jsonPrimitive?.content.orEmpty()
            if (url.isBlank()) null
            else AnnotationSource(obj["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { url }, url)
        }
    }.getOrElse { emptyList() }

    /** 从错误响应里抠出 `{"error":{"code","message"}}` 的 message。 */
    private fun readError(body: ResponseBody?, code: Int): String {
        val raw = runCatching { body?.string() }.getOrNull().orEmpty()
        val message = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        return message ?: "请求失败（HTTP $code）"
    }

    private companion object {
        const val TAG = "AskStream"
        const val READ_TIMEOUT_SECONDS = 300L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 问答事件。**含 [Ignored]** —— 服务端将来加新事件时老客户端应当安静跳过而不是崩。 */
sealed interface AskEvent {
    /** 检索到的参考来源。**先于第一个 [Delta] 到达**，让 UI 能先显示「参考了这几页」。 */
    data class Sources(val items: List<AnnotationSource>) : AskEvent

    /** 一段回答文字（1–3 个汉字）。累加渲染。 */
    data class Delta(val text: String) : AskEvent

    /**
     * 回答结束。`searched` 表示**这轮是否真的检索过资料**。
     *
     * 它是给用户看的重要信息：模型有权选择直接作答（问题能凭原文或已有注疏回答），
     * 用户应当知道答案是「查证过的」还是「凭原文推的」—— 这两种的可信度不一样，
     * 混为一谈会让用户误以为每条都查过。
     */
    data class Done(val searched: Boolean) : AskEvent

    data object Ignored : AskEvent

    data class Failed(val message: String) : AskEvent
}
