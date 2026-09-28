package com.example.daily_shici.data.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 内置包清单（`assets/builtin_packs.json`，由 `backend/etl/build_builtin_packs.py` 产出）。
 *
 * 字段是 snake_case，与 `docs/01 §6.3` 的 manifest 一致 —— 并由 `@SerialName` 显式绑定。
 *
 * ⚠️ kotlinx-serialization **默认按属性名匹配**，属性名与 JSON 键不一致时会静默取默认值。
 * 这里配合 `ignoreUnknownKeys = true` 尤其危险：解码「成功」但全是默认值。
 * 因此凡是与 JSON 键名不同的字段，一律显式写 [SerialName]。
 */
@Serializable
data class BuiltinPackIndexDto(
    val builtin: List<BuiltinPackManifestDto> = emptyList(),
)

@Serializable
data class BuiltinPackManifestDto(
    @SerialName("pack_id") val packId: String,
    val name: String = "",
    val version: Int = 1,
    @SerialName("poem_count") val poemCount: Int = 0,
    @SerialName("bytes_raw") val bytesRaw: Long = 0,
    @SerialName("bytes_gzip") val bytesGzip: Long = 0,
    val sha256: String = "",
    @SerialName("min_poem_id") val minPoemId: Long = 0,
    @SerialName("max_poem_id") val maxPoemId: Long = 0,
    /** 包体在 assets 中的文件名。 */
    val asset: String = "",
    val collections: List<BuiltinCollectionDto> = emptyList(),
) {
    /** 未显式给出 `asset` 时按约定回退到 `<pack_id>.jsonl`。 */
    val assetName: String get() = asset.ifBlank { "$packId.jsonl" }
}

@Serializable
data class BuiltinCollectionDto(
    val slug: String,
    val name: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
)
