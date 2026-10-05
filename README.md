# 每日诗词 · daily-shici

一款「每日一首古诗词」的 Android 应用与配套后端。核心语料 **83.5 万首**（先秦至当代），
在此之上做三件事：每日加权推送、按需提问的流式 AI 问答、以及可考证的注释与赏析。

---

## 这个项目在做什么

上游公开数据集只提供 **4 个扁平字段**（`id` / `title` / `author` / `content`），
没有朝代、没有体裁、没有词牌、没有稳定主键、没有版权标记、没有全文索引。
本项目的主要工作是把这样一份原始文本库变成一个**可检索、可考据、可维护**的产品：

| 能力 | 规模 | 关键实现 |
|---|---|---|
| 结构化语料 | 834,817 首 / 28,948 位作者 / 22 个朝代 / 11 个合集 | 跨来源去重、自建稳定 `poem_id`、朝代与体裁映射 |
| 体裁推断 | 诗 813,971 / 词 20,781 / 辞 65 | 由换行结构与篇幅推断诗体，词牌填充率 **98.19%** |
| 全文检索 | jieba 预分词 + SQLite FTS5 | BM25 列权重实现「标题 > 作者 > 正文」 |
| 分层数据包 | 13 个包（L0–L3），gzip 合计约 115 MB | 按热度分级，客户端按需下载 |
| 每日一诗 | 365 天滚动窗口 | 三层权重加权随机 + 同诗 365 天不重复 |
| 注释与赏析 | 7,388 首有真人注疏 | 独立 `annotations.db`，附来源与文献出处 |
| AI 问答 | 覆盖全部语料 | 检索增强 + 本地 LLM 流式作答（SSE） |

设计取舍与踩坑记录都写在 [`docs/`](docs/) 里，那些文档是本项目的主要资产。

---

## 两条注释路线（有意为之，不是重复建设）

这是本项目最关键的一个产品判断：**注疏和问答不是替代关系**。

| | 方式一 · 流式问答 | 方式二 · 预制注疏 |
|---|---|---|
| 形态 | 用户就具体词句提问，AI 检索后流式作答 | 从公开数据集导入真人注疏 |
| 内容来源 | 模型现场组织 | **真人编辑成果**（古诗文网，CC0-1.0） |
| 覆盖 | **任意一首** | 7,388 首（全量的 0.88%） |
| 首字延迟 | 1–3 s，随后逐字 | 零（纯查表） |
| 是否落库 | 否 | 是 |

- **注疏解决「读得深」**：经典篇目有据可查，附参考来源，用户可自行核对。
- **问答解决「问得到」**：冷门诗、具体字音、典故用法，千奇百怪的问题预制注疏永远覆盖不全。

我们**评估并否决了**「用 LLM 批量生成全量注释」这条路：古典诗词注释是考据不是创作，
模型会自相矛盾（实测把《登幽州台歌》「怆然」写成 `chuáng rán`，而同段前文写 `chuàng`），
无法流式输出，且本地模型单首 30–60 s——精选两千首也要 30 小时以上。详见 `docs/06 §1.1`。

---

## 架构

```
backend/
├── etl/                    数据流水线（fetch → … → verify 九阶段）
│   ├── fetch.py            幂等可续传地拉取上游原始数据
│   ├── run_etl.py          阶段编排入口
│   ├── lib/pipeline.py     各阶段实现
│   └── mappings/           朝代映射、排除清单
├── packs/                  产出的分层数据包（*.jsonl.gz + manifest）
└── server/                 FastAPI 服务
    ├── app/routers/        daily / poems / search / facets / authors / packs / annotations / ask
    ├── app/services/       业务逻辑（检索、每日选诗、LLM、注疏、搜索）
    ├── app/jobs/           APScheduler 定时任务（每日选诗）
    └── scripts/            注疏导入等运维脚本
frontend/                   Android 客户端（Kotlin + Compose + Room + WorkManager）
docs/                       设计文档（见下）
```

后端：**Python / FastAPI / SQLite**（含 FTS5）。单 worker——SQLite 写锁全局，
本项目读多写近乎零；要扩容应换 PostgreSQL，而不是加 worker。定时任务用 APScheduler
跑在同一进程内，不引 Celery。零编译依赖、无需 Docker。

客户端：**Kotlin / Jetpack Compose / Room**，WorkManager 负责每日推送与数据包下载，
SSE 接收流式回答。

---

## 数据来源与许可

本项目使用**两个相互独立**的公开数据集，分别服务于语料与注疏：

### 1. 诗词正文语料

| 项目 | 角色 | 许可证 |
| --- | --- | --- |
| [CanvaChen/llm-dataset-chinese-poetry](https://github.com/CanvaChen/llm-dataset-chinese-poetry) | 直接上游，聚合下述两者 | Apache-2.0 |
| [chinese-poetry/chinese-poetry](https://github.com/chinese-poetry/chinese-poetry) | 二次来源（上游 `data/`） | MIT |
| [Werneror/Poetry](https://github.com/Werneror/Poetry) | 二次来源（上游 `data2/`） | — |

ETL（[`backend/etl`](backend/etl)）在此之上完成的工作：

- **跨集合去重**：上游集合间存在重复（御定全唐诗与 `poet.tang` 重复 19,494 首，
  占前者 48.4%）。实测解析 859,591 条、剔除非诗词条目 58 条后进入去重 859,533 条，
  合并重复 24,716 条 → **834,817** 条唯一。
- **稳定 ID**：上游 `id` 跨版本不稳定，本项目自建 `poem_id` 并维护 `id_map.jsonl` 映射。
- **派生字段**：朝代（22 个）、体裁、诗体、词牌、行列数、字数——上游一概没有。
- **版权标记**：逐条标注 `is_public_domain`（746,650 首公有领域 / 88,167 首受保护），
  可由 `INCLUDE_COPYRIGHTED` 一键切换，**服务端强制生效，客户端不可绕过**。
- **内容过滤**：剔除混入的非诗词条目（论语、四书五经、蒙学、幽梦影共 12 个文件 / 270 条）。
- **分级权重**：W1/W2/W3 三层，供每日选诗加权使用。

### 2. 注释与赏析语料

| 项目 | 角色 | 许可证 |
| --- | --- | --- |
| [Papersnake/gushiwen](https://huggingface.co/datasets/Papersnake/gushiwen) | 唯一来源（古诗文网镜像，433,841 行） | **CC0-1.0**（可商用，无署名义务） |

**注疏不经过任何模型加工**——它是真人编辑成果，导入脚本只做「流式读取 → 解析 →
按 `(作者, 标题)` 归一化匹配我方语料 → 幂等落库」，几秒钟跑完 745 MB。
上游 43.4 万行中仅约 1.14 万首有注疏，去掉匹配不上的实际入库 7,388 首，
**这是数据本身的覆盖率上限**，不是匹配算法的问题。详见 `docs/06 §2`。

### 引用要求

复用本仓库的数据产物时，请一并保留对上述上游数据集的引用与其许可证要求。
注意含少量 20 世纪后作品，如需商用请自行核实相关作品版权。

---

## 运行

### 后端

```bash
cd backend/server
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt

# 导入注疏（可选，缺失时注疏端点返回 missing，不影响主功能）
.venv/Scripts/python.exe scripts/import_gushiwen.py --dry-run   # 先看匹配率
.venv/Scripts/python.exe scripts/import_gushiwen.py

.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
```

交互式 API 文档：`http://127.0.0.1:8000/docs`。

### 环境变量（均有默认值，可全部不设）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `INCLUDE_COPYRIGHTED` | `true` | 置 `false` 后所有查询与计数排除非公有领域作品 |
| `SHICI_ANNOTATIONS` | `true` | 注疏功能总开关 |
| `SHICI_LLM_PROVIDER` | `ollama` | `ollama`（本地，走原生 `/api/chat`）或 `openai`（任意兼容服务） |
| `SHICI_LLM_MODEL` | `qwen3.5:9b` | 6 GB 显存下须关闭思考链，见下 |
| `SHICI_LLM_DISABLE_THINKING` | `true` | **性能关键项**，见下 |
| `SHICI_SEARCH_BASE_URL` | `https://api.anysearch.com` | 问答的检索后端，匿名即可用 |
| `SHICI_ASK_SEARCH_FIRST` | `true` | 强制先检索再作答 |

> **性能提示**：本地 6 GB 显存跑 9B 模型时，思考链是头号性能杀手。
> 实测同一任务开启思考需 445 s / 3365 tokens，关闭后 28 s / 221 tokens——约 **15 倍**。
> 问答是交互场景，这个差距直接决定能不能用。Ollama 的 OpenAI 兼容层不认 `think` 字段，
> 故 `ollama` provider 必须走原生 `/api/chat`。

### 健康检查

`GET /healthz` 返回语料统计、每日选诗最新日期与注疏覆盖率。
后两者是**会静默失效的链路**——它们坏了 API 照常 200，只是内容不更新，所以主动暴露。

---

## API 概览

前缀 `/api/v1`，共 11 个端点：

| 端点 | 说明 |
| --- | --- |
| `GET /daily` · `/daily/batch` | 每日一诗（支持前后日期窗口查询） |
| `GET /poems` · `/poems/{id}` | 列表（keyset 游标分页）与详情 |
| `GET /poems/random` | 随机漫游，**在筛选范围内均匀采样** |
| `GET /poems/{id}/annotation` | 注释、译文、赏析与出处 |
| `POST /poems/{id}/ask` | 流式问答（SSE：sources → delta → done） |
| `GET /search` | 全文检索 |
| `GET /facets` | 分面计数（朝代 / 体裁 / 词牌 / 合集） |
| `GET /authors/{id}` | 作者详情 |
| `GET /packs` | 数据包清单与下载 |

---

## 设计文档

`docs/` 是本项目的主要资产，所有结论均有实测数据支撑，建议按序阅读：

| 文档 | 内容 |
| --- | --- |
| [01 · 数据源与 ETL](docs/01-数据源与ETL.md) | 上游清单实测、九阶段流水线、派生规则、分包方案 |
| [02 · API 契约](docs/02-API契约.md) | 全部端点的请求/响应契约，前后端唯一分界 |
| [03 · 后端设计](docs/03-后端设计.md) | schema、索引、缓存、版权开关、定时任务 |
| [04 · 客户端设计](docs/04-客户端设计.md) | Room schema、数据包安装、导航、离线策略 |
| [05 · 差异汇报](docs/05-客户端与API契约差异汇报.md) | 客户端实现与契约的已知偏差（**尚未收敛**） |
| [06 · 注释与赏析](docs/06-注释与赏析.md) | 两条路线的取舍依据、注疏导入、性能实测 |

---

## 已知问题

- `docs/05` 记录的客户端与契约差异**尚未收敛**，联调前需按方案 A 统一。
- `tests/test_api.py` 中 `TestAskRouting` 与 `TestPronunciationGuard` 各被重复定义一次，
  后定义覆盖前定义，会静默丢掉部分用例。

---

## 许可证

本仓库代码的许可证待定。数据部分遵循上游数据集各自的许可证，见上文「数据来源与许可」。
