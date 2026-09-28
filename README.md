# 每日诗词 · daily-shici

一款「每日一首古诗词」的 Android 应用与配套后端。每天推送一首诗词，支持浏览、检索、收藏，并提供 AI 注释与赏析。

## 项目结构

```
daily-shici/
├── backend/          后端
│   ├── etl/          数据清洗流水线（去重、朝代映射、生成数据包）
│   ├── packs/        产出的诗词数据包（*.jsonl / *.jsonl.gz）
│   └── server/       FastAPI 服务（每日推送、检索、注释、AI 问答）
├── frontend/         Android 客户端（Kotlin + Jetpack Compose + Room）
└── docs/             设计文档
    ├── 01-数据源与ETL.md
    ├── 02-API契约.md
    ├── 03-后端设计.md
    ├── 04-客户端设计.md
    ├── 05-客户端与API契约差异汇报.md
    └── 06-注释与赏析.md
```

## 技术栈

- **后端**：Python / FastAPI，SQLite（含 FTS 全文检索），LLM 接口生成注释与赏析
- **前端**：Kotlin，Jetpack Compose，Room，WorkManager（每日推送）
- **数据**：ETL 流水线对上游诗词数据集去重、规范化、按体裁/朝代切分为数据包

> 完整设计见 [`docs/`](docs/) 目录。

## 数据来源与引用说明

本项目的诗词数据来自开源数据集，经本项目 ETL 流水线（[`backend/etl`](backend/etl)）清洗、去重与规范化后使用。

| 项目 | 说明 | 许可证 |
| --- | --- | --- |
| [CanvaChen/llm-dataset-chinese-poetry](https://github.com/CanvaChen/llm-dataset-chinese-poetry) | 直接上游，聚合下述两个数据集 | Apache-2.0 |
| [chinese-poetry/chinese-poetry](https://github.com/chinese-poetry/chinese-poetry) | 二次来源（数据集 `data/`） | MIT |
| [Werneror/Poetry](https://github.com/Werneror/Poetry) | 二次来源（数据集 `data2/`） | — |

数据处理中已进行的主要工作：

- **去重**：上游集合间存在大量重复（如御定全唐诗与 poet.tang 重复约 48%），ETL 已做跨集合去重。
- **稳定 ID**：上游 `id` 跨版本不稳定，本项目自建稳定 `poem_id` 并维护 `id_map.jsonl` 映射。
- **朝代规范化**：为无法从路径推断朝代的数据手工建立映射表。
- **内容过滤**：剔除混入的非诗词条目（如论语、蒙学等）；统一简繁体规范。
- **版权提示**：数据集含少量 20 世纪后作品，如需商用请自行核实相关作品版权。

如你在其他项目中复用本仓库的数据产物，请一并保留对上述上游数据集的引用与其许可证要求。

## 许可证

本仓库代码的许可证待定；数据部分遵循上游数据集各自的许可证（见上表）。
