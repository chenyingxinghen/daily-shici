# 每日诗词 · 服务端

技术栈与设计见 `docs/03-后端设计.md`，接口契约见 `docs/02-API契约.md`。

## 目录

```
server/
  app/
    main.py         FastAPI 实例、路由挂载、健康检查、APScheduler 启动
    config.py       环境变量（含 INCLUDE_COPYRIGHTED）
    db.py           SQLite 连接管理（线程本地读连接 + 单写连接）
    errors.py       统一错误模型（docs/02 §1.4）
    schemas.py      Pydantic 响应模型
    routers/        daily / poems / search / facets / authors / packs
    services/       query（游标）· fts（分词与注入防护）· highlight（区间）
                    poems（列表/详情/随机）· daily（选诗）· cache（facets 缓存）· slugs
    jobs/           daily_pick.py（每日 00:05）
  data/poems.db     ★ ETL 产物，不纳入版本控制
  tests/
```

## 运行

```bash
pip install -r requirements.txt

# 先建库（在 backend/etl 下）
python run_etl.py

# 起服务（单 worker —— SQLite 写锁是全局的）
# 端口 8088：经 Caddy 的 /shici 反代对外暴露（见 F:\caddy\Caddyfile 的 handle /shici*）
uvicorn app.main:app --host 127.0.0.1 --port 8088 --workers 1
```

Base URL（经 Caddy）：`https://chenyingxinghen.cloud-ip.cc/shici/api/v1`。
直连调试（不走反代）可用 `http://127.0.0.1:8088/api/v1`。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `SHICI_DB` | `server/data/poems.db` | 库文件路径 |
| `SHICI_PACKS` | `backend/packs` | 数据包目录（线上交给 nginx） |
| `INCLUDE_COPYRIGHTED` | `true` | 置 `false` 时所有端点排除非公有领域作品 |
| `DAILY_POOL_MIN_WEIGHT` | `1` | 每日选诗候选池下限（1 = 全量） |
| `WEIGHT_W3` / `WEIGHT_W2` | `100` / `30` | 加权随机的层权重（`docs/02 §4.1`） |
| `SHICI_DISABLE_SCHEDULER` | 未设置 | 置 `1` 关闭进程内定时任务（测试用） |
| `SHICI_SERVE_PACKS` | `1` | 置 `0` 关闭内置静态包目录（线上交给 nginx） |

## 测试

```bash
SHICI_DISABLE_SCHEDULER=1 python -m pytest tests -q
```
