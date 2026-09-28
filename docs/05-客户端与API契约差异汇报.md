# 05 · 客户端与 API 契约差异汇报

> **状态：已全部修复（2026-09-23）。**
> 决定是**契约以 `docs/02` 为准** —— 客户端逐项对齐，服务端实现也以 `02` 为准。
> 下文保留原始差异清单以便回溯；每项对应到客户端的实际改法可参见
> `docs/04 §10.11`。唯一的例外是 §2.6 的「主题」维度与 §2.10 的未使用端点，
> 处理方式单独记录在该节。

> 起因：在开始写服务端前通读 `docs/02-API契约.md`，发现 **`04` 实现阶段写的客户端 DTO 是按猜测建的，
> 与 `02` 的契约不一致**。本文逐项列出差异，供决策。
>
> 图例：🔴 = 会导致数据静默丢失/结构错位；🟡 = 字段缺失或多余；🟢 = 仅命名风格差异

---

## 0. 为什么这批差异特别危险

客户端网络层是这么配的（`di/NetworkModule.kt:28`）：

```kotlin
Json {
    ignoreUnknownKeys = true   // 服务端多给的字段直接忽略
    explicitNulls = false
    coerceInputValues = true   // 显式 null 落到有默认值的字段上时用默认值
}
```

加上 `Dtos.kt` 里**每个字段都给了默认值**，后果是：

- 字段名对不上（camelCase vs snake_case）→ 解码**不报错**，该字段**静默取默认值**；
- 结构对不上（对象当成字符串）→ 同样不报错，静默取默认值。

也就是说：**编译通过、单元测试通过、真机上是一片空白或 0**。
这类不一致如果不是在联调时主动核对，很容易被当成「后端有问题」排查很久。

`ignoreUnknownKeys = true` 本身是对的（服务端加字段不该让客户端崩），
**问题在于没有配对使用 `@SerialName`**。

---

## 1. 必改项汇总表

| # | 位置（文件:行） | 客户端现状 | `02` 契约 | 级别 |
|---|---|---|---|---|
| 1 | `data/remote/dto/Dtos.kt` 全文 | 字段名 camelCase | **snake_case** | 🔴 |
| 2 | `Dtos.kt:47` `PoemDetailDto.author` | `String` | **对象** `{author_id,name,dynasty}` | 🔴 |
| 3 | `Dtos.kt:55` `PoemDetailDto.collections` | `List<String>` | **对象数组** `[{slug,name}]` | 🔴 |
| 4 | `Dtos.kt:80-84` `SearchHitDto` | `{poem: PoemSummaryDto, highlights:[{field,ranges}]}` | 扁平 `{poem_id,title,author,dynasty,hit,snippet,highlights:[[start,end]]}` | 🔴 |
| 5 | `Dtos.kt:62-67` `PoemListResponseDto` | `{items,total,notDownloadedPackIds}` | `{items,next_cursor,total,applied_filters}` | 🔴 |
| 6 | `Dtos.kt:106-112` `FacetsDto` | `period/dynasty/genre/theme` + `{value,count}` | `periods/dynasties/genres/cipais/collections` + `{key,name,count}` | 🔴 |
| 7 | `data/remote/ShiciApi.kt` 全部查询参数 | 传中文（`dynasty=唐`） | 传 key（`dynasty=tang`） | 🔴 |
| 8 | `Dtos.kt:13-30` `PackDto` | 缺 `tier`/`min_poem_id`/`max_poem_id`/`download_url`/`collections`；用了 `url` | 全部要有，字段名 `download_url` | 🟡 |
| 9 | `Dtos.kt:39` `PoemSummaryDto.weight` | 有 | 契约**没有** `weight` | 🟡 |
| 10 | `Dtos.kt:57` `PoemDetailDto.weight` / `packId` | 有 | 契约**没有** | 🟡 |
| 11 | `Dtos.kt:43` `PoemDetailDto` | 缺 `annotation`/`translation`/`appreciation` | 契约有（v1 恒 null） | 🟡 |
| 12 | `ShiciApi.kt` 缺少端点 | — | `/poems/random`、`/authors/{id}` 未声明 | 🟡 |
| 13 | 分页 | 无 `cursor`/`limit` 语义，只有 `limit` | keyset 游标，客户端只回传不解析 | 🟡 |

---

## 2. 逐项细节

### 2.1 🔴 字段命名：camelCase → snake_case

契约全部用 snake_case（`poem_id`、`line_count`、`char_count`、`is_public_domain`、
`catalog_version`、`bytes_gzip`、`generated_at`、`next_cursor`、`applied_filters` …），
客户端全用 camelCase。

**修法**：每个与 JSON 键名不同的属性加 `@SerialName`，例如

```kotlin
@Serializable
data class PoemSummaryDto(
    @SerialName("poem_id") val poemId: Long,
    val title: String = "",
    val author: String = "",
    val dynasty: String = "",
    val genre: String = "",
    val excerpt: String = "",
)
```

**注意**：不要图省事直接把属性名改成 snake_case —— Kotlin 侧会到处出现 `dto.poem_id`，
与 `04 §1.2` 定的「领域模型用 Kotlin 命名习惯」冲突。`@SerialName` 是正解。

已有一个正确范例：`data/pack/BuiltinPacks.kt`（内置包清单）就是按这个方式写的。

### 2.2 🔴 `PoemDetail.author` 是对象不是字符串

契约（`02 §2.2`）：

```json
"author": { "author_id": 88, "name": "曹操", "dynasty": "汉" }
```

客户端 `Dtos.kt:47` 声明为 `val author: String`。
应对齐为嵌套 DTO：

```kotlin
@Serializable
data class AuthorRefDto(
    @SerialName("author_id") val authorId: Long = 0,
    val name: String = "",
    val dynasty: String = "",
)
```

`Mappers.kt` 的 `PoemDetailDto.toDomain()` 需取 `author.name`。
顺带：`PoemDetail` 领域模型应保留 `authorId`，以便在线时调 `/authors/{id}`。

### 2.3 🔴 `collections` 是对象数组

契约：`"collections": [{ "slug": "caocao-shiji", "name": "曹操诗集" }]`。
客户端 `Dtos.kt:55` 是 `List<String>`。

**注意 `02 §2.2` 还规定「最多返回 5 个，按合集权重排序」** —— 客户端详情页当前只取 `first()`
（`DetailScreen.kt` 的 `noteOf()`），与契约的最大 5 条不冲突，但应在 UI 上体现「收录于多部选本」。

### 2.4 🔴 `SearchHit` 结构完全不同

| | 客户端 | 契约（`02 §2.3`） |
|---|---|---|
| 结构 | 嵌套 `poem: PoemSummaryDto` | **扁平**：`poem_id`/`title`/`author`/`dynasty` 直接在顶层 |
| 命中位置 | 无 | `hit`: `title` / `author` / `content` |
| 摘要 | 复用 `excerpt` | **独立的 `snippet`**（取命中句及前后各一句，约 60 字） |
| 高亮 | `highlights: [{field, ranges:[{start,end}]}]` | `highlights: [[start, end], …]`（**二元组数组，无字段名**） |

影响面：
- `SearchRepository.kt` 的 `searchRemote()` 与 `rangesOf()` 扩展函数要重写；
- `ui/components/HighlightedText.kt` 接收的 `List<HighlightRange>` 可以保留，
  但转换来源从「按字段分组的区间」变成「无字段名的二元组」——
  **契约里高亮只针对 `snippet`，不区分标题/摘要**，故 `SearchHit.titleHighlights` 应删除；
- 客户端要用 `snippet` 而不是 `excerpt` 展示搜索结果。

### 2.5 🔴 列表响应缺 `next_cursor` / `applied_filters`

契约 `{items, next_cursor, total, applied_filters}`；客户端只有 `{items, total, notDownloadedPackIds}`。

- `next_cursor`：契约 `01 §1.3` 要求 keyset 分页，客户端**必须回传**这个不透明游标，否则翻不到第二页。
- `applied_filters`：`02 §3.3` 明确它用于排查「我传了参数却没过滤」。
- `notDownloadedPackIds` 是**我们编的字段**，契约里没有。§2.3 的「未下载」判定应改用
  `04 §10.3` 的本地逻辑（用 `facets_cache` 的应有条数比对），不依赖服务端送这个字段。
  **这一条要顺带确认**：若希望服务端提供，得在 `02` 里新增字段（属向后兼容变更，不必升版本）。

### 2.6 🔴 `/facets` 响应结构不同

| | 客户端 | 契约（`02 §3.7`） |
|---|---|---|
| 顶层键 | `period`/`dynasty`/`genre`/`theme` | `periods`/`dynasties`/`genres`/`cipais`/`collections` |
| 元素 | `{value, count}` | `{key, name, count}`；`dynasties[]` 另带 `period` |
| 词牌 | **没有** | `cipais: [{name, count}]`（约 1000 条） |
| 合集 | **没有** | `collections: [{slug, name, count, builtin}]` |
| 主题（theme） | 有 | 契约里**没有** |

两个需要产品决策的点：

1. **`theme`（主题：山水/思乡/送别…）在契约里不存在。** `01 §5.5` 明确写了
   「`tags`/`theme` 上游无此数据，v1 不做」。但设计稿的筛选行里**有「主题」**。
   → 要么砍掉设计稿的「主题」，要么在 `02` 里新增该维度（需先解决数据来源）。
2. **`cipais` 一次返回约 1000 条约 40 KB**，客户端必须有缓存与懒渲染，否则筛选面板会卡。

### 2.7 🔴 查询参数要传 key 而不是中文

契约：`period=suitangwudai`、`dynasty=tang`、`genre=shi|ci|ci_sao`、
`collection=tangshi-sanbai`、`form=wuyan-jueju`。

客户端 `BrowseViewModel` 目前把**中文名**直接当参数传（`dynasty=唐`）。
而**本地 Room 查询用的是中文名**（包里 `dynasty` 字段就是中文，`04 §2.2`）。

因此 `FacetOption` 必须**同时带 key 与 name**：

```kotlin
data class FacetOption(
    val key: String,     // 在线查询用：tang
    val label: String,   // 本地查询与展示用：唐
    val count: Int = 0,
)
```

这类「双份标识」在 `02 §3.8` 已经为作者定过同样的规矩：
**本地导航键是作者名，`author_id` 仅用于在线**。朝代、体裁、词牌同理。

### 2.8 🟡 `/packs` 字段缺失

契约的包对象比客户端多 `tier`（L0–L3）、`min_poem_id`、`max_poem_id`、`collections`，
且下载地址字段叫 **`download_url`** 而不是 `url`。

- `tier` 是**下载行为**的依据（`01 §6.1`：L1 静默下、L2 展示体积、L3 强制 Wi-Fi + 二次确认），
  客户端 `ui/packs/` 需要它才能做分级确认；
- `min/max_poem_id` 用于判断与已装包冲突、按 id 区间增量补齐；
- `download_url` 指向静态文件（不经 API 服务）。

### 2.9 🟡 多余字段与缺失字段

- **多余**：`PoemSummaryDto.weight`、`PoemDetailDto.weight`、`PoemDetailDto.packId`
  —— 契约都没返回。留着无害（永远是默认值），但会误导后续维护者以为服务端会给。
  **建议删掉**，权重排序一律以本地包内的 `weight` 为准。
- **缺失**：`PoemDetailDto` 没有 `annotation`/`translation`/`appreciation`。
  `02 §6.3` 要求「客户端必须容忍 null 并隐藏对应区块」——
  客户端现在连字段都没有，二期服务端填充后也无法使用。**建议一并补上**。

### 2.10 🟡 未声明的端点

`02 §1.2` 共 9 个端点，客户端 `ShiciApi.kt` 声明了 7 个，缺：

- `GET /poems/random` —— 「随机漫游」，设计稿里没有入口，但契约有；
- `GET /authors/{id}` —— 作者页，设计稿也没有入口。

两者是否需要，取决于产品是否要补这两个功能。**若不做，应在 `02` 里标注为「客户端暂不使用」**，
避免文档与实现长期不一致。

---

## 3. 影响面与工作量估计

按 2.1–2.7 全改的话，涉及文件：

| 文件 | 改动性质 |
|---|---|
| `data/remote/dto/Dtos.kt` | 重写（字段名 + 结构） |
| `data/remote/ShiciApi.kt` | 参数取值语义 + 补 2 个端点 |
| `data/repository/Mappers.kt` | `toDomain()` 全部要改（author 对象、snippet、key/name） |
| `data/repository/SearchRepository.kt` | 搜索命中映射重写 |
| `data/repository/FacetsRepository.kt` | 新形状 + `FacetOption` 加 key |
| `data/repository/PoemRepository.kt` | `describeEmpty` 的应有条数取值改路径 |
| `ui/browse/BrowseScreen.kt` | 筛选传 key、本地查 name |
| `ui/search/SearchScreen.kt` | 用 `snippet` 替换 `excerpt` 展示 |
| `ui/packs/PacksScreen.kt` | 用 `tier` 做分级下载确认 |

**估计 1 个完整工作批次**（改完需重新跑编译 + 单测 + 与真服务端联调）。

---

## 4. 建议

**必须在写服务端之前定下来**，否则会两边同时漂移。三个选项：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A. 改客户端对齐 `02`**（推荐） | 以契约为准，客户端逐项修正 | `02` 已经过完整设计（keyset 游标、snake_case、对象化 author 都是深思熟虑的），且是**前后端唯一分界**；改客户端是让实现追文档 |
| B. 改 `02` 迁就客户端 | 把契约改成 camelCase + 嵌套结构 | 等于迁就一个**未经过设计评审**的实现产物；且要连带改 `01`/`03`/`04` 三份文档 |
| C. 暂不处理 | 等联调时暴露 | 差异会以「一片空白/0」的形式出现，排查成本最高 |

另外两处**需要产品拍板**、不是纯技术问题：

1. **筛选维度的「主题」**：契约与数据源都没有，但设计稿有（§2.6）。
2. **`notDownloadedPackIds`**：是否要在 `02` 里新增这个字段，还是客户端按 `04 §10.3` 自行判定（§2.5）。

---

*相关文档：数据源与 ETL 见 `01`，接口契约见 `02`，服务端设计见 `03`，客户端设计见 `04`。*
