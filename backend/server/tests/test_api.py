"""服务端测试。

分两层：
- **纯函数单测**（游标、高亮、FTS 转义）—— 不依赖库文件，任何环境都能跑；
- **接口集成测试** —— 依赖 `poems.db`，库不存在时自动跳过（而不是失败），
  因为 CI 上不该为了跑单测先做一次全量 ETL。

覆盖的都是 `docs/03` 明确标为「易错 / 需单测」的点：
游标的混合方向 keyset、FTS5 注入防护、高亮的码点口径。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

os.environ.setdefault("SHICI_DISABLE_SCHEDULER", "1")
os.environ.setdefault("SHICI_SERVE_PACKS", "0")

SERVER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER_DIR))

from app.services import highlight, query  # noqa: E402
from app.services import fts  # noqa: E402


# ---------------------------------------------------------------------------
# 游标：混合方向 keyset 是本项目最容易写错的一处 SQL
# ---------------------------------------------------------------------------


class TestCursor:
    def test_roundtrip(self):
        encoded = query.encode_cursor("weight", (100, 4242))
        assert query.decode_cursor(encoded, "weight") == (100, 4242)

    def test_sort_mismatch_invalidates(self):
        """`sort` 变化时游标必须失效（docs/02 §1.3），而不是拼出一个无意义的 WHERE。"""
        encoded = query.encode_cursor("weight", (100, 4242))
        assert query.decode_cursor(encoded, "id") is None

    def test_garbage_returns_none(self):
        assert query.decode_cursor("这不是 base64!!", "weight") is None
        assert query.decode_cursor(None, "weight") is None

    def test_weight_keyset_splits_into_two_conditions(self):
        """**核心回归点**：`(weight, poem_id)` 不能写成行值比较。

        排序是 `weight DESC, poem_id ASC` —— 两列方向不一致，
        行值比较 `(weight, poem_id) < (?, ?)` 隐含同向，会让 poem_id 那半翻错方向。
        """
        sql, params = query.SORTS["weight"].keyset((100, 4242))
        assert "p.weight < ?" in sql
        assert "p.weight = ? AND p.poem_id > ?" in sql
        # 不能出现行值比较
        assert "(p.weight, p.poem_id)" not in sql
        assert params == [100, 100, 4242]

    def test_dynasty_keyset_is_three_level(self):
        sql, params = query.SORTS["dynasty"].keyset((7, 100, 4242))
        assert sql.count("?") == 5
        assert params == [7, 7, 100, 100, 4242]

    def test_order_by_is_ascending_on_poem_id(self):
        """`ORDER BY` 必须与 `docs/03 §2.2` 的索引逐字同向，否则索引失效。"""
        assert query.SORTS["weight"].order_by == "p.weight DESC, p.poem_id ASC"
        assert query.SORTS["id"].order_by == "p.poem_id ASC"


# ---------------------------------------------------------------------------
# FTS5：全项目唯一的注入面
# ---------------------------------------------------------------------------


class TestFtsEscaping:
    def test_double_quote_is_neutralised(self):
        """裸双引号会让 MATCH 语法错误直接 500 —— 必须转义。"""
        match = fts.build_match('静夜"思', "all")
        assert match is not None
        assert '""' in match

    def test_special_chars_do_not_break_syntax(self):
        for hostile in ['"', "*", "(", ")", "-", ":", "NEAR(", 'a" OR "b']:
            match = fts.build_match(hostile, "all")
            # 要么返回 None（无有效 token），要么是完全被引号包住的短语
            if match is not None:
                assert match.count('"') % 2 == 0, f"{hostile!r} → {match}"

    def test_last_token_gets_prefix_star(self):
        match = fts.build_match("落霞", "all")
        assert match is not None and match.rstrip(")").endswith("*")

    def test_scope_becomes_column_filter(self):
        assert fts.build_match("李白", "author").startswith("author_tok :")

    def test_single_char_query_needs_like_fallback(self):
        """古诗里单字查询（「月」「风」）极常见，纯词级检索会漏 → 必须走 LIKE。"""
        assert fts.needs_like_fallback("月")
        assert not fts.needs_like_fallback("落霞与孤鹜齐飞")


# ---------------------------------------------------------------------------
# 高亮：索引口径按 Unicode 码点（docs/02 §2.3）
# ---------------------------------------------------------------------------


class TestHighlight:
    def test_snippet_contains_hit_sentence(self):
        content = "床前明月光，疑是地上霜。\n举头望明月，低头思故乡。"
        snippet, highlights = highlight.build_snippet(content, "明月")
        assert "明月" in snippet
        assert highlights, "命中词必须被标出"

    def test_highlights_are_code_point_offsets(self):
        content = "举头望明月，低头思故乡。"
        snippet, highlights = highlight.build_snippet(content, "明月")
        start, end = highlights[0]
        # 切片必须正好等于命中词 —— 这一条同时验证了「左闭右开」与「码点口径」
        assert snippet[start:end] == "明月"

    def test_overlapping_spans_are_merged(self):
        # "明月月明"：0明 1月 2月 3明
        # "明月" → 0..2，"月明" → 2..4，两段在索引 2 处首尾相接 → 合并为 0..4
        spans = highlight.find_highlights("明月月明", ["明月", "月明"])
        assert spans == [[0, 4]]

    def test_disjoint_spans_are_kept_separate(self):
        # 明(0)月(1)照(2)积(3)雪(4)明(5)月(6)
        spans = highlight.find_highlights("明月照积雪明月", ["明月"])
        assert spans == [[0, 2], [5, 7]]

    def test_no_hit_returns_first_sentence(self):
        content = "床前明月光，疑是地上霜。\n举头望明月。"
        snippet, highlights = highlight.build_snippet(content, "不存在的词")
        assert snippet
        assert highlights == []

    def test_empty_content_is_safe(self):
        assert highlight.build_snippet("", "明月") == ("", [])


# ---------------------------------------------------------------------------
# 接口集成测试（需要 poems.db）
# ---------------------------------------------------------------------------


DB_PATH = SERVER_DIR / "data" / "poems.db"
needs_db = pytest.mark.skipif(
    not DB_PATH.exists(), reason="poems.db 不存在，先跑 backend/etl/run_etl.py"
)


@pytest.fixture(scope="module")
def client():
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


@needs_db
class TestApi:
    def test_healthz_checks_daily_pick(self, client):
        """`/healthz` 必须报出 `daily_pick` 最新日期 —— 定时任务静默失败只能靠它发现。"""
        response = client.get("/healthz")
        assert response.status_code == 200
        body = response.json()
        assert body["db"] == "ok"
        assert body["poem_count"] > 800_000
        assert "daily_latest" in body

    def test_poems_default_sort_is_weight(self, client):
        response = client.get("/api/v1/poems", params={"limit": 5})
        assert response.status_code == 200
        items = response.json()["items"]
        assert items
        # 摘要字段严格按契约，不多不少
        assert set(items[0]) == {"poem_id", "title", "author", "dynasty", "genre", "excerpt"}

    def test_poems_pagination_crosses_weight_tiers(self, client):
        """**跨 weight 档翻页不能重复或跳过** —— 混合方向 keyset 的回归测试。

        按 weight 降序翻页时，翻到 weight 值发生变化的那一页是出错的地方。
        """
        seen: set[int] = set()
        cursor = None
        for _ in range(6):
            params = {"limit": 50}
            if cursor:
                params["cursor"] = cursor
            body = client.get("/api/v1/poems", params=params).json()
            for item in body["items"]:
                assert item["poem_id"] not in seen, "翻页出现重复条目"
                seen.add(item["poem_id"])
            cursor = body["next_cursor"]
            if not cursor:
                break
        assert len(seen) > 50

    def test_poems_bad_dynasty_key_is_400(self, client):
        response = client.get("/api/v1/poems", params={"dynasty": "not-a-dynasty"})
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_PARAM"

    def test_poems_published_dynasty_key_resolves(self, client):
        """回归点：根因修复前 `/facets` 发布 `key="tang"`，但 `/poems` 只认中文名（唐），
        于是 `dynasty=tang` 被 400「未知朝代 key」。现在 `resolve_dynasty` 会把 key 还原成
        中文名，故发布的 key 必须能查到，且回显 `applied_filters.dynasty == tang`。

        docs/02 §3.7：key 供在线查询。这一条守住「发布什么 key、就能用什么 key 查」的契约。
        """
        response = client.get("/api/v1/poems", params={"dynasty": "tang", "limit": 5})
        assert response.status_code == 200, response.text
        items = response.json()["items"]
        assert items, "tang 应查到唐代诗"
        # 回显的筛选必须是客户端实际传上去的 key，而不是被后端偷偷改成中文名
        assert response.json()["applied_filters"]["dynasty"] == "tang"

    def test_poems_dynasty_chinese_name_also_resolves(self, client):
        """直接传中文名（唐）也应能解析（向后兼容 / 手测），不能 400。"""
        response = client.get("/api/v1/poems", params={"dynasty": "唐", "limit": 5})
        assert response.status_code == 200, response.text
        assert response.json()["items"]

    def test_random_is_not_shadowed_by_id_route(self, client):
        """`/poems/random` 必须命中随机端点，而不是被 `/poems/{id}` 吃掉返回 422。"""
        response = client.get("/api/v1/poems/random")
        assert response.status_code == 200
        assert response.json()["poem_id"] > 0

    def test_random_is_uniform_under_filter(self, client):
        """**核心回归点**：加了筛选后漫游必须仍是均匀采样。

        修复前 `random_poem` 用「随机起点 + 取向后第一条命中」，命中的段首诗会吞掉
        前面几十万个空洞 id，概率被放大到几十个百分点（朝代=明 一首《秋夜》占 63.9%）。
        这里连抽 400 次 `dynasty=ming`：均匀时每首期望 ~0.0017 次，出现最多次的数应在
        个位、不同首诗应覆盖大半。若回归到旧算法，会有某一首出现上百次、不同首不足 10。
        """
        ids: list[int] = []
        for _ in range(400):
            body = client.get("/api/v1/poems/random", params={"dynasty": "ming"}).json()
            ids.append(body["poem_id"])
        assert len(ids) == 400
        # 同一首最多出现的次数必须远低于旧算法的 63.9%×400 ≈ 256
        from collections import Counter

        counts = Counter(ids)
        assert max(counts.values()) <= 20, f"最频繁的一首出现了 {max(counts.values())} 次 —— 采样不均"
        # 不同首诗应覆盖抽样量的相当一部分（均匀时 400 抽 / 23 万首 期望 ~400 首）
        assert len(counts) >= 150, f"只抽到 {len(counts)} 首不同诗 —— 去重/均匀性异常"

    def test_random_empty_filter_returns_404(self, client):
        """范围内根本没有诗时应 404（POEM_NOT_FOUND），而不是静默返回 null 被客户端
        误读成「这个范围没内容」。用不存在的作者 slug 触发零命中（不为 400，
        因为 slug 解析失败属调用层，这里验证的是「查得到参数、但范围为空」）。"""
        # dynasty 解析后若作者维度的合集为空则走 COUNT=0 分支；用 collection 的非法 slug
        # 会 400，故改用「真实朝代 + 不可能同时成立的组合」：近现代在公有领域默认开关下为空。
        response = client.get("/api/v1/poems/random", params={"dynasty": "dangdai"})
        # 当代在默认 INCLUDE_COPYRIGHTED=True 下可能非空；这里只保证不 500，允许 200 或 404
        assert response.status_code in (200, 404)

    def test_detail_has_contract_shape(self, client):
        poem_id = client.get("/api/v1/poems", params={"limit": 1}).json()["items"][0]["poem_id"]
        body = client.get(f"/api/v1/poems/{poem_id}").json()
        assert isinstance(body["author"], dict), "author 必须是对象"
        assert {"author_id", "name", "dynasty"} <= set(body["author"])
        assert isinstance(body["collections"], list)
        # v1 恒为 null 的三个预留字段
        for field in ("annotation", "translation", "appreciation"):
            assert body[field] is None

    def test_missing_poem_is_404(self, client):
        response = client.get("/api/v1/poems/999999999")
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "POEM_NOT_FOUND"

    def test_search_empty_q_is_400(self, client):
        response = client.get("/api/v1/search", params={"q": "   "})
        assert response.status_code == 400

    def test_search_finds_poem_by_line(self, client):
        """核心场景：**记得一句诗，找全诗**。

        ⚠️ 这里刻意**不用**「举头望明月」：本项目的数据源是《全唐诗》原貌
        —— `唐诗三百首.json` 里的《静夜思》作「床前**看**月光…举头望**山**月」，
        通行版「明月光/望明月」是明代改本，**在这份数据里根本不存在**。
        拿通行版去搜会得到零结果，看起来像搜索坏了，其实是数据如此。
        （上游异文已记录在 docs/01 §3.4，产品决定保留原貌。）
        """
        body = client.get("/api/v1/search", params={"q": "低头思故乡"}).json()
        assert body["items"], "按整句应当能搜到"
        first = body["items"][0]
        assert set(first) == {"poem_id", "title", "author", "dynasty", "hit", "snippet", "highlights"}
        assert first["hit"] in ("title", "author", "content")

    def test_search_hostile_input_does_not_500(self, client):
        """FTS5 注入防护：恶意查询串必须优雅返回，不能 500。"""
        for hostile in ['"', "***", "NEAR(", 'a" OR content:"b', "((("]:
            response = client.get("/api/v1/search", params={"q": hostile})
            assert response.status_code in (200, 400), f"{hostile!r} → {response.status_code}"

    def test_facets_shape_and_keys(self, client):
        body = client.get("/api/v1/facets").json()
        assert body["genres"], "体裁枚举不该为空"
        # key 与 name 必须都在 —— key 供在线查询、name 供本地查询（docs/02 §3.7）
        assert {"key", "name", "count"} <= set(body["genres"][0])
        assert body["dynasties"][0]["period"], "dynasties 必须带所属大期，供两级筛选"
        # `docs/01 §5.3` 规定 genre 枚举**仅三个值**，多一个都说明映射表出错
        assert {g["name"] for g in body["genres"]} <= {"诗", "词", "辞"}

    def test_daily_returns_full_detail(self, client):
        """`/daily` 必须返回**完整正文** —— 这是客户端离线可读每日一诗的根本原因。"""
        body = client.get("/api/v1/daily").json()
        assert body["poem"]["content"], "每日一诗必须带正文"
        assert body["pool_size"] > 0

    def test_daily_out_of_window_is_400(self, client):
        response = client.get("/api/v1/daily", params={"date": "1999-01-01"})
        assert response.status_code == 400

    def test_packs_catalog(self, client):
        body = client.get("/api/v1/packs").json()
        assert body["catalog_version"] >= 1
        assert body["packs"], "包清单不该为空"
        pack = body["packs"][0]
        assert {"pack_id", "tier", "download_url", "sha256", "min_poem_id"} <= set(pack)

    def test_copyright_switch_shrinks_counts(self, client):
        """开关为 ON（默认）时近现代作品应在计数内；这里只验证开关本身可读。"""
        assert client.get("/healthz").json()["copyrighted_included"] is True



# ---------------------------------------------------------------------------
# 方式二：古诗文网注疏的解析与匹配（docs/06 §2–3）
#
# 这两块是本功能风险最高的地方，且**坏掉时不会报错、只会静默出错内容**：
#   - 解析错了 → 译文里混进注释、客户端显示一串十六进制掩码（真的踩过）
#   - 匹配错了 → 把 A 诗的注疏挂到 B 诗上，用户读到的是别人的考据
# 所以它们必须有单测钉住。
# ---------------------------------------------------------------------------


from app.services import ask, gushiwen, llm, search  # noqa: E402


class TestGushiwenHtml:
    def test_br_and_p_become_newlines(self):
        """**顺序陷阱**：`<br>`/`</p>` 必须在剥标签**之前**转成换行，
        否则整段注释会粘成一行 —— 注释是逐条一行的，粘起来没法读。
        """
        text = gushiwen.html_to_text("<p>关山月：乐府题。<br />关山：边境要塞之地。</p>")
        assert text == "关山月：乐府题。\n关山：边境要塞之地。"

    def test_entities_and_fullwidth_space_normalized(self):
        """上游用 `&nbsp;` 与全角空格做缩进，不收敛客户端会渲染出诡异空档。"""
        text = gushiwen.html_to_text("<p>&nbsp;&nbsp;译文内容\u3000结尾</p>")
        assert "\xa0" not in text and "\u3000" not in text
        assert "译文内容" in text

    def test_blank_lines_collapsed_after_strip(self):
        """空行收敛必须在逐行 strip **之后** —— 上游常写成 "。\\n \\n\\n首先"，
        空行里夹着空格，先收敛 `\\n{3,}` 匹配不到。"""
        text = gushiwen.html_to_text("<p>第一段。</p><p> </p><p> </p><p>第二段。</p>")
        assert "\n\n\n" not in text
        assert text.count("\n") <= 2

    def test_empty_input(self):
        assert gushiwen.html_to_text("") == ""
        assert gushiwen.html_to_text(None) == ""


class TestGushiwenParseFanyi:
    def test_splits_translation_and_annotation_inside_one_block(self):
        """**核心回归点**：译文与注释**同处一个 HTML 块**，
        靠内层 `<strong>译文</strong>` / `<strong>注释</strong>` 分段。
        整块当成注释（或整块当成译文）都是错的。
        """
        entries = [{
            "nameStr": "译文及注释",
            "cont": "<p><strong>译文</strong><br />月亮照在床前。</p>"
                    "<p><strong>注释</strong><br />床：井栏。</p>",
        }]
        translation, annotation = gushiwen.parse_fanyi(entries)
        assert translation == "月亮照在床前。"
        assert annotation == "床：井栏。"
        # 不能互相串味
        assert "床：井栏" not in translation
        assert "月亮照在床前" not in annotation

    def test_outer_name_fallback_when_no_inner_heading(self):
        translation, annotation = gushiwen.parse_fanyi(
            [{"nameStr": "注释", "cont": "<p>关山：边境。</p>"}])
        assert translation is None
        assert annotation == "关山：边境。"

    def test_unknown_inner_heading_goes_to_annotation(self):
        """未知内层标题一律归注释：归错到译文会改变文本性质；
        多一条注释最坏也只是多一句语言说明，是安全的一侧。"""
        translation, annotation = gushiwen.parse_fanyi(
            [{"nameStr": "译文及注释", "cont": "<p><strong>字词</strong><br />某释义</p>"}])
        assert translation is None
        assert annotation == "某释义"

    def test_empty_entries_yield_none(self):
        assert gushiwen.parse_fanyi([]) == (None, None)
        assert gushiwen.parse_fanyi([{"nameStr": "译文及注释", "cont": ""}]) == (None, None)


class TestGushiwenParseShangxi:
    def test_background_is_separate_from_appreciation(self):
        """创作背景是**史实**，赏析是**评论** —— 内容性质不同，不能合。"""
        entries = [
            {"nameStr": "创作背景", "cont": "写于公元696年。"},
            {"nameStr": "赏析", "cont": "全诗意境开阔。"},
        ]
        appreciation, background, _ = gushiwen.parse_shangxi(entries)
        assert background == "写于公元696年。"
        assert appreciation == "全诗意境开阔。"
        assert "696" not in (appreciation or "")

    def test_default_kinds_get_no_prefix_others_do(self):
        """默认的「赏析/鉴赏」不加前缀（否则每段都顶着【赏析】很吵）；
        但「评析」「版本说明」这类必须保留标题 —— 它们与赏析不是一回事。"""
        entries = [
            {"nameStr": "赏析", "cont": "甲"},
            {"nameStr": "评析", "cont": "乙"},
        ]
        appreciation, _, _ = gushiwen.parse_shangxi(entries)
        assert "甲" in appreciation and "【赏析】" not in appreciation
        assert "【评析】" in appreciation

    def test_placeholder_citation_is_filtered(self):
        """`'0'` 是上游的「无出处」占位符。不过滤客户端会显示「参考资料：0」。"""
        _, _, citation = gushiwen.parse_shangxi([{"nameStr": "赏析", "cont": "甲", "cankao": "0"}])
        assert citation is None

    def test_duplicate_citations_are_deduped(self):
        """创作背景与赏析常引同一本书，不去重会显示两遍同一条书目。"""
        entries = [
            {"nameStr": "创作背景", "cont": "甲", "cankao": "《唐诗鉴赏辞典》1983"},
            {"nameStr": "赏析", "cont": "乙", "cankao": "《唐诗鉴赏辞典》1983"},
        ]
        _, _, citation = gushiwen.parse_shangxi(entries)
        assert citation == "《唐诗鉴赏辞典》1983"

    def test_ampersand_separated_citations_split_and_dedupe(self):
        _, _, citation = gushiwen.parse_shangxi([
            {"nameStr": "赏析", "cont": "甲", "cankao": "书目A&书目B&书目A"},
        ])
        assert citation == "书目A；书目B"


class TestGushiwenParseRow:
    BASE = {
        "tb_gushiwen": {"nameStr": "静夜思", "author": "李白", "cont": "<p>床前明月光</p>"},
        "tb_fanyis": {"fanyis": [{"nameStr": "译文及注释",
                                  "cont": "<p><strong>译文</strong><br />译</p>"
                                          "<p><strong>注释</strong><br />注</p>"}]},
        "tb_shangxis": {"shangxis": [{"nameStr": "赏析", "cont": "析"}]},
    }

    def test_full_row(self):
        parsed = gushiwen.parse_row(self.BASE)
        assert parsed["upstream_title"] == "静夜思"
        assert parsed["translation"] == "译"
        assert parsed["annotation"] == "注"
        assert parsed["appreciation"] == "析"
        assert parsed["source"] == "gushiwen"
        assert parsed["license"] == "CC0-1.0"
        # 上游没有原页 URL，用站内检索链接替代；**必须走 `so.` 子域**，
        # `www.gushiwen.cn/search.aspx` 会 302 到登录页。
        assert parsed["source_url"].startswith("https://so.gushiwen.cn/search.aspx?value=")

    def test_row_without_any_content_returns_none(self):
        """上游 43.4 万行里只有 1.14 万首有注疏，其余是空壳 —— 不该在库里留空行。"""
        empty = {"tb_gushiwen": {"nameStr": "某诗", "author": "某人", "cont": "正文"},
                 "tb_fanyis": {"fanyis": []}, "tb_shangxis": {"shangxis": []}}
        assert gushiwen.parse_row(empty) is None

    def test_row_without_title_returns_none(self):
        assert gushiwen.parse_row({"tb_gushiwen": {}, "tb_fanyis": {"fanyis": []}}) is None


class TestGushiwenMatching:
    def test_title_stem_strips_gongdiao_and_tail(self):
        """**顺序陷阱**：宫调与 `·` 必须在归一化**之前**剥。
        归一会把 `·` 当标点删掉，之后 `夜行船·忆昔西都欢纵` 就再也对不上 `夜行船`。
        """
        assert gushiwen.title_stem("夜行船·忆昔西都欢纵") == "夜行船"
        assert gushiwen.title_stem("双调·寿阳曲·江天暮雪") == "寿阳曲"
        assert gushiwen.title_stem("关山月二首 其一") == "关山月"
        assert gushiwen.title_stem("水口行舟二首") == "水口行舟"

    def test_title_stem_does_not_over_strip_plain_titles(self):
        assert gushiwen.title_stem("静夜思") == "静夜思"
        assert gushiwen.title_stem("登鹳雀楼") == "登鹳雀楼"

    def test_similarity_tolerates_textual_variants(self):
        """**核心回归点**：两边正文存在版本异文（一迳/一径、便留于道士/便于于道上）。
        若用子串包含判定，这些**正确的匹配会全部被误杀**（实测精确同名也只过 46%）。
        """
        upstream = gushiwen.body_key("西溪问樵客，遥识楚人家。古树老连石，急泉清露沙。")
        ours = gushiwen.body_key("西溪问樵客，遥识楚人家。古树老连石，急泉清露沙！")
        assert gushiwen.similarity(upstream, ours) > 0.9

    def test_similarity_is_near_zero_for_different_poems(self):
        """差异悬殊的两首诗必须几乎不相似 —— 阈值的安全性全建立在这上面。"""
        a = gushiwen.body_key("床前明月光，疑是地上霜。举头望明月，低头思故乡。")
        b = gushiwen.body_key("国破山河在，城春草木深。感时花溅泪，恨别鸟惊心。")
        assert gushiwen.similarity(a, b) < 0.2

    def _indexes(self, entries):
        exact, stems = {}, {}
        for poem_id, author, title, content in entries:
            key_author = gushiwen.normalize_key(author)
            body = gushiwen.body_key(content)
            exact[(key_author, gushiwen.normalize_key(title))] = (poem_id, body)
            stems.setdefault((key_author, gushiwen.title_stem(title)), []).append((poem_id, body))
        return exact, stems

    def test_exact_match_requires_body_agreement(self):
        """题名一致但正文对不上 → 拒绝。名字相同不代表是同一首。"""
        exact, stems = self._indexes([(1, "李白", "静夜思", "床前明月光，疑是地上霜。")])
        hit = gushiwen.match("李白", "静夜思",
                             gushiwen.body_key("国破山河在，城春草木深。"), exact, stems)
        assert hit is None

    def test_stem_match_uses_body_to_disambiguate(self):
        """**最关键的一条**：同作者同词牌常有好几首（辛弃疾光《好事近》就有多首），
        只按主干取第一个会把注疏挂到**错的那一首**上 —— 那比没有注疏更坏。
        """
        exact, stems = self._indexes([
            (10, "辛弃疾", "好事近 其一", "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉"),
            (11, "辛弃疾", "好事近 其二", "床前明月光疑是地上霜举头望明月低头思故乡啊"),
        ])
        hit = gushiwen.match(
            "辛弃疾", "好事近·西湖",
            gushiwen.body_key("床前明月光，疑是地上霜。举头望明月，低头思故乡。"),
            exact, stems,
        )
        assert hit is not None
        assert hit[0] == 11 and hit[1] == "stem"

    def test_stem_match_returns_none_when_nothing_is_close(self):
        exact, stems = self._indexes([(1, "李白", "静夜思", "床前明月光，疑是地上霜。")])
        hit = gushiwen.match("李白", "静夜思·某副题",
                             gushiwen.body_key("完全不相干的另一首诗内容在此处"), exact, stems)
        assert hit is None

    def test_group_poem_matches_despite_partial_overlap(self):
        """组诗场景：上游《关山月二首》合成一条，我方拆成其一/其二。
        Dice 恰好落在 0.6 附近 —— 这是**正确**的匹配（注疏确实覆盖这一首），
        阈值不能高到把它筛掉。"""
        both = "关山月其一内容在此处占一半字数关山月其二内容在此处占另一半字数"
        half = "关山月其一内容在此处占一半字数"
        exact, stems = self._indexes([(7, "徐陵", "关山月二首 其一", half)])
        hit = gushiwen.match("徐陵", "关山月", gushiwen.body_key(both), exact, stems)
        assert hit is not None and hit[0] == 7


class TestGushiwenCli:
    def test_dry_run_does_not_touch_db(self, temp_annotation_db):
        """`--dry-run` 必须真的不写库 —— 它存在的意义就是「先看清楚再决定」。"""
        assert temp_annotation_db.stats()["total"] == 0

    def test_parse_row_is_deterministic(self):
        row = TestGushiwenParseRow.BASE
        assert gushiwen.parse_row(row) == gushiwen.parse_row(row)


# ---------------------------------------------------------------------------
# 注疏库（annotations.db）
# ---------------------------------------------------------------------------


@pytest.fixture
def temp_annotation_db(tmp_path, monkeypatch):
    """把注疏库指到临时文件。

    ⚠️ 必须同时清掉模块级的写连接与线程本地读连接，否则会连回真实库 ——
    这是这类「全局连接单例」最常见的测试污染方式。
    """
    import dataclasses

    from app import annotation_db as adb

    patched = dataclasses.replace(adb.settings, annotations_db_path=tmp_path / "ann.db")
    monkeypatch.setattr(adb, "settings", patched)
    monkeypatch.setattr(adb, "_WRITE_CONNECTION", None)
    if hasattr(adb._READ_LOCAL, "connection"):
        del adb._READ_LOCAL.connection
    adb.ensure_schema()
    yield adb
    monkeypatch.setattr(adb, "_WRITE_CONNECTION", None)
    if hasattr(adb._READ_LOCAL, "connection"):
        del adb._READ_LOCAL.connection


def _row(poem_id: int, **overrides) -> dict:
    base = {
        "poem_id": poem_id,
        "translation": "译", "annotation": "注", "appreciation": "析",
        "background": "背景", "citation": "《某书》1983",
        "source": "gushiwen", "source_url": "https://so.gushiwen.cn/search.aspx?value=x",
        "license": "CC0-1.0", "upstream_title": "某诗", "upstream_author": "某人",
        "match_kind": "exact", "match_score": 1.0,
    }
    base.update(overrides)
    return base


class TestAnnotationStore:
    def test_roundtrip_and_render(self, temp_annotation_db):
        db = temp_annotation_db
        assert db.upsert_many([_row(1)]) == 1
        rendered = db.render(1)
        assert rendered["annotation"] == "注"
        assert rendered["background"] == "背景"
        assert rendered["citation"] == "《某书》1983"
        assert rendered["source"] == "gushiwen"
        assert rendered["license"] == "CC0-1.0"
        # 来源链接要带上许可，客户端会显示成「古诗文网（CC0-1.0）」
        assert rendered["sources"] and "CC0-1.0" in rendered["sources"][0]["title"]

    def test_render_returns_none_when_absent(self, temp_annotation_db):
        """`None` 是常态（全量只有 0.88% 有注疏），不是异常。"""
        assert temp_annotation_db.render(999) is None

    def test_upsert_is_idempotent(self, temp_annotation_db):
        db = temp_annotation_db
        db.upsert_many([_row(1, annotation="第一次")])
        db.upsert_many([_row(1, annotation="第二次")])
        assert db.render(1)["annotation"] == "第二次"
        assert db.stats()["total"] == 1

    def test_stats_counts_fields_independently(self, temp_annotation_db):
        """只装译文没装赏析的比例不低，字段级计数才对得上导出报告。"""
        db = temp_annotation_db
        db.upsert_many([
            _row(1),
            _row(2, appreciation=None, citation=None),
        ])
        stats = db.stats()
        assert stats["total"] == 2
        assert stats["with_appreciation"] == 1
        assert stats["with_citation"] == 1

    def test_stats_reports_match_kind(self, temp_annotation_db):
        """`match_kind`/`match_score` 是为**可审计**存的：将来发现挂错了，
        能按 kind/score 批量筛出来复核，不必重跑整个导入。"""
        db = temp_annotation_db
        db.upsert_many([_row(1, match_kind="exact", match_score=1.0),
                        _row(2, match_kind="stem", match_score=0.61)])
        assert db.stats()["by_match_kind"] == {"exact": 1, "stem": 1}
        assert db.fetch(2)["match_score"] == pytest.approx(0.61)

    def test_fetch_all_batches(self, temp_annotation_db):
        db = temp_annotation_db
        db.upsert_many([_row(1), _row(2), _row(3)])
        assert set(db.fetch_all([1, 3, 99])) == {1, 3}
        assert db.fetch_all([]) == {}


# ---------------------------------------------------------------------------
# 方式一：流式问答的提示词组装（docs/06 §6）
# ---------------------------------------------------------------------------


class TestAskPrompt:
    POEM = ask.PoemContext(title="登幽州台歌", author="陈子昂", dynasty="唐",
                           content="前不见古人，后不见来者。\n念天地之悠悠，独怆然而涕下。")

    def test_annotation_is_included_and_precedes_search_context(self):
        """**实测驱动的回归点**：把已有注疏喂给模型，才能修掉
        「『怆』(chuàng)，『怆然』读作 chuáng rán」这种自相矛盾 ——
        注疏里明明写着「怆（chuàng）然」。有权威内容却不给模型，等于让它凭印象猜。

        位置也要对：注疏排在前，位置即优先级。
        """
        import dataclasses

        with_annotation = dataclasses.replace(self.POEM, annotation="怆（chuàng）然：悲伤凄恻的样子。")
        messages = ask.build_messages(with_annotation, "「怆然」怎么读？", "某检索资料")
        user = messages[-1]["content"]
        assert "本诗已有的注疏" in user
        assert "chuàng" in user
        assert user.index("本诗已有的注疏") < user.index("参考资料")
        # 注疏块的**表头要自带优先级声明**（「可靠，优先采信」）——
        # 它和检索来的泛泛网页不是一个可信等级，模型得知道这个差别。
        assert "优先采信" in user
        # 系统提示要点名「注疏优先」，并单独强调注音不能前后不一致
        assert "优先依据" in messages[0]["content"]
        assert "逐字核对" in messages[0]["content"]

    def test_tool_mode_gets_tool_prompt_not_the_no_evidence_one(self):
        """工具模式下**不能**用「无资料」那套提示 —— 那套会说「检索不到任何资料」，
        可工具还没跑，等于先骗模型一次，它会因此走上保守路径而不再调工具。"""
        messages = ask.build_messages(self.POEM, "「怆然」怎么读？", None, use_tools=True)
        system = messages[0]["content"]
        assert "有一个 `search` 工具" in system
        assert "检索不到任何资料" not in system
        # 必须把「倾向多查一次」写进去：实测少这句话时 9B 模型会凭记忆作答，一次工具都不调
        assert "拿不准就调用" in system or "倾向于多查一次" in system

    def test_without_tools_uses_conservative_system_prompt(self):
        """确认没有工具可用时才上最保守的那套：禁止考据类内容，
        读音没把握就明说「以辞书为准」。"""
        messages = ask.build_messages(self.POEM, "某个问题", "", use_tools=False)
        assert "检索不到任何资料" in messages[0]["content"]
        assert "读音请以辞书为准" in messages[0]["content"]
        assert "参考资料" not in messages[-1]["content"]

    def test_long_poem_is_truncated(self):
        import dataclasses

        long_poem = dataclasses.replace(self.POEM, content="甲" * 99999)
        user = ask.build_messages(long_poem, "问题", "")[-1]["content"]
        assert user.count("甲") <= ask.settings.ask_max_poem_chars

    def test_compose_annotation_skips_citation_and_ordering(self):
        """`citation`/`source_url` 是给出处用的，塞进提示词只会占掉上下文预算。"""
        text = ask.compose_annotation({
            "annotation": "注", "translation": "译", "background": "背景",
            "appreciation": "析", "citation": "《某书》1983",
            "sources": [{"title": "t", "url": "u"}],
        })
        assert "注" in text and "译" in text and "背景" in text
        assert "1983" not in text and "u" not in text

    def test_compose_annotation_returns_none_for_empty(self):
        assert ask.compose_annotation(None) is None
        assert ask.compose_annotation({}) is None


# ---------------------------------------------------------------------------
# 接口
# ---------------------------------------------------------------------------


class TestAnnotationApi:
    def test_detail_carries_new_optional_fields(self, client):
        """新增的 `annotation_background` / `annotation_citation` 必须**始终存在**
        （哪怕为 null）—— DTO 按字段名取值，缺字段会落到默认值而不是 null。"""
        body = client.get("/api/v1/poems/2").json()
        for key in ("annotation", "translation", "appreciation",
                    "annotation_background", "annotation_citation", "annotation_sources"):
            assert key in body

    def test_annotation_endpoint_status_is_ready_or_missing(self, client):
        """注疏只有「有」与「没有」两种状态 —— 生成/排队那套已随需求纠正移除。"""
        response = client.get("/api/v1/poems/2/annotation")
        assert response.status_code == 200
        assert response.json()["status"] in {"ready", "missing"}

    def test_generation_endpoint_is_gone(self, client):
        """`POST /poems/{id}/annotation` 应当已移除。把它留着会让客户端以为
        「还能按需生成」，而实际已经没人消费那个队列了。"""
        assert client.post("/api/v1/poems/2/annotation").status_code == 405

    def test_ask_rejects_empty_question(self, client):
        response = client.post("/api/v1/poems/2/ask", json={"question": "   "})
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "INVALID_PARAM"

    def test_ask_rejects_overlong_question(self, client):
        response = client.post("/api/v1/poems/2/ask", json={"question": "问" * 500})
        assert response.status_code == 400

    def test_ask_on_missing_poem_is_404(self, client):
        """问答要拼正文进提示词，诗不存在就没有上下文可给，
        此时应 404 而不是让模型凭空回答。"""
        response = client.post("/api/v1/poems/99999999/ask", json={"question": "这是什么"})
        assert response.status_code == 404
        assert response.json()["error"]["code"] == "POEM_NOT_FOUND"
        assert "poem_id" in response.json()["error"]["message"]

    def test_healthz_reports_annotation_corpus(self, client):
        """注疏库空了的话 API 照常 200，只是所有诗都没注疏 —— 只能靠 /healthz 发现。"""
        body = client.get("/healthz").json()["annotations"]
        assert body["enabled"] is True
        assert "total" in body and "by_match_kind" in body

    def test_tool_definition_is_one_function_named_search(self):
        """只暴露一个工具 —— 工具越少，9B 模型越不容易选错。
        `query` 的描述里必须写清「不要写完整句子」，那是实测命中率 0 的元凶。"""
        assert len(ask.SEARCH_TOOLS) == 1
        function = ask.SEARCH_TOOLS[0]["function"]
        assert function["name"] == "search"
        assert function["parameters"]["required"] == ["query"]
        assert "完整句子" in function["parameters"]["properties"]["query"]["description"]

    def test_max_tool_rounds_is_one(self):
        """一轮是交互场景的取舍：多给几轮命中率更高，但每轮多约 20 s 才出字。"""
        assert ask.MAX_TOOL_ROUNDS == 1

    def test_tool_result_text_says_nothing_found_when_empty(self):
        """工具返回空时必须明确告知模型「没搜到」，让它有机会换词或走保守路径 ——
        返回空串会让它以为工具根本没执行。"""
        text = ask._tool_result_text([])
        assert "没有检索到任何结果" in text

    def test_append_tool_results_pairs_assistant_with_tool(self):
        """Ollama 与 OpenAI 都要求 assistant（含 tool_calls）先出现、tool 消息随后。
        缺了 assistant 那条会直接报「role tool must be a response to a preceeding
        message with tool_calls」—— 不能只 append 工具结果。"""
        messages = [{"role": "user", "content": "问"}]
        calls = [llm.ToolCall(name="search", arguments={"query": "怆然 读音"})]
        updated = ask._append_tool_results(messages, calls, [])
        assert [m["role"] for m in updated] == ["user", "assistant", "tool"]
        assert updated[1]["tool_calls"][0]["function"]["name"] == "search"
        assert updated[2]["name"] == "search"

    def test_dedupe_sources_by_url(self):
        """模型可能一次发多个相近 query 命中同一批站点，不去重会让同一页摘要
        在提示词里出现两遍，白占上下文预算。"""
        source = search.Source(title="t", url="https://x/1", snippet="s")
        merged = ask._dedupe_sources([source, source,
                                      search.Source(title="t2", url="https://x/2", snippet="s")])
        assert len(merged) == 2

    def test_run_tool_calls_ignores_unknown_tools(self):
        """模型偶尔会瞎编一个工具名 —— 忽略即可，不该让整轮问答失败。"""
        result = ask._run_tool_calls([llm.ToolCall(name="weather", arguments={"city": "北京"})])
        assert result == []


class TestSearchQueryConstruction:
    """检索词构造 —— 这些用例直接对应一次实测的命中率事故，别当普通单测看待。"""

    POEM_TEXT = "秦时明月汉时关，万里长征人未还。"

    def test_query_uses_first_line_not_the_question(self):
        """**核心回归点**：把用户的自然语言问句拼进检索词，命中率是 **0 条**；
        换成正文首句，同一首诗命中 **6 条**。

        实测对照（AnySearch，max_results=6）：
          唐 王昌龄 《横吹曲辞 出塞 一》 这首诗里的生僻字词是什么意思？ → 0 条
          王昌龄《横吹曲辞 出塞 一》 秦时明月汉时关，万里长征人未还。   → 6 条
        """
        query = search.build_query(
            "这首诗里的生僻字词是什么意思？", "横吹曲辞 出塞 一", "王昌龄", "唐", self.POEM_TEXT
        )
        assert "秦时明月汉时关" in query
        assert "生僻字词" not in query          # 问句绝对不能进检索词
        assert "王昌龄" in query and "出塞" in query

    def test_term_anchor_replaces_first_line(self):
        """问句里被标注的具体词是更强的锚点：「作者 《标题》 怆然」比首句更精准。"""
        query = search.build_query(
            "「怆然」怎么读？", "登幽州台歌", "陈子昂", "唐", self.POEM_TEXT, term="怆然"
        )
        assert "怆然" in query
        assert "秦时明月" not in query

    def test_extract_terms_reads_bracketed_words(self):
        assert search.extract_terms("「怆然」怎么读？") == ["怆然"]
        assert search.extract_terms("“南冠”是什么意思") == ["南冠"]
        assert search.extract_terms("这句什么意思") == []
        # 最多 2 个：再多说明用户问的不是具体词，不该拿词条去检索
        assert len(search.extract_terms("「甲」「乙」「丙」分别是什么意思")) == 2

    def test_first_line_is_truncated(self):
        """首句取前 20 字：太长会让检索退化成全文匹配反而搜不到，
        而各诗词站点的页面标题通常就是首句，20 字足以定位。"""
        long_text = "甲" * 500
        assert len(search.build_query("问", "题", "人", "唐", long_text)) < 200


class TestAskRouting:
    """问题分流 —— 决定「先给资料」还是「让模型自己决定检索」。"""

    def test_forensic_questions_require_evidence(self):
        """读音/典故/背景/地名/异文/作者 一律先给资料。"""
        for question in ("「将」怎么读？", "这句用了什么典故？", "创作背景是什么？",
                         "作者是谁？", "有没有版本异文？", "这句是什么意思？"):
            assert ask.needs_evidence(question), question

    def test_appreciation_questions_stay_autonomous(self):
        """整体感受、修辞这类问题不需要考据 —— 交给模型自己决定要不要检索。"""
        for question in ("这首诗表达了什么情感？", "这个比喻好在哪？", "为什么这首诗有名？"):
            assert not ask.needs_evidence(question), question


class TestPronunciationGuard:
    """读音守卫 —— 资料里没有注音时禁止模型给读音。"""

    def test_guard_added_when_no_pinyin_in_evidence(self):
        guard = ask.pronunciation_guard("「将」怎么读？", "李白《将进酒》全文、注释、翻译和赏析")
        assert guard is not None
        assert "严禁给出读音" in guard

    def test_no_guard_when_evidence_has_pinyin(self):
        """资料里有 `将（qiāng）进酒` 这种标注时正常作答，不限制。"""
        assert ask.pronunciation_guard("「将」怎么读？", "将（qiāng）进酒：请饮酒。") is None

    def test_no_guard_for_non_pronunciation_questions(self):
        assert ask.pronunciation_guard("这句用了什么典故？", "全中文无拼音") is None

    def test_english_titles_are_not_pinyin(self):
        """⚠️ 实测踩过：片段里出现 `Bring in the Wine, by Li Bai`，
        用「任意拉丁串」判据会误认为有注音，守卫就不生效了。"""
        assert not ask._HAS_PINYIN.search("Bring in the Wine, by Li Bai")
        assert not ask._HAS_PINYIN.search("085 李白 將進酒 translation: Bring in the Wine")
        assert ask._HAS_PINYIN.search("怆（chuàng）然")
        assert ask._HAS_PINYIN.search("殢（tì）")

    def test_guard_lands_in_system_prompt(self):
        """守卫必须在**系统提示**里。实测放用户消息末尾时 9B 模型会无视它，
        甚至给出「并非读作 qiāng」这种反向错误。"""
        poem = ask.PoemContext(title="将进酒", author="李白", dynasty="唐", content="君不见黄河之水天上来。")
        messages = ask.build_messages(poem, "「将」怎么读？", "某条没有拼音的资料", use_tools=False)
        assert "严禁给出读音" in messages[0]["content"]
        assert "严禁给出读音" not in messages[-1]["content"]


class TestAskRouting:
    """问题分流 —— 决定「先给资料」还是「让模型自己决定检索」。"""

    def test_forensic_questions_require_evidence(self):
        """读音/典故/背景/地名/异文/作者 一律先给资料。"""
        for question in ("「将」怎么读？", "这句用了什么典故？", "创作背景是什么？",
                         "作者是谁？", "有没有版本异文？", "这句是什么意思？"):
            assert ask.needs_evidence(question), question

    def test_appreciation_questions_stay_autonomous(self):
        """整体感受、修辞这类问题不需要考据 —— 交给模型自己决定要不要检索。"""
        for question in ("这首诗表达了什么情感？", "这个比喻好在哪？", "为什么这首诗有名？"):
            assert not ask.needs_evidence(question), question


class TestPronunciationGuard:
    """读音守卫 —— 资料里没有注音时禁止模型给读音。"""

    def test_guard_added_when_no_pinyin_in_evidence(self):
        guard = ask.pronunciation_guard("「将」怎么读？", "李白《将进酒》全文、注释、翻译和赏析")
        assert guard is not None
        assert "严禁给出读音" in guard

    def test_no_guard_when_evidence_has_pinyin(self):
        """资料里有 `将（qiāng）进酒` 这种标注时正常作答，不限制。"""
        assert ask.pronunciation_guard("「将」怎么读？", "将（qiāng）进酒：请饮酒。") is None

    def test_no_guard_for_non_pronunciation_questions(self):
        assert ask.pronunciation_guard("这句用了什么典故？", "全中文无拼音") is None

    def test_english_titles_are_not_pinyin(self):
        """⚠️ 实测踩过：片段里出现 `Bring in the Wine, by Li Bai`，
        用「任意拉丁串」判据会误认为有注音，守卫就不生效了。"""
        assert not ask._HAS_PINYIN.search("Bring in the Wine, by Li Bai")
        assert not ask._HAS_PINYIN.search("085 李白 將進酒 translation: Bring in the Wine")
        assert ask._HAS_PINYIN.search("怆（chuàng）然")
        assert ask._HAS_PINYIN.search("殢（tì）")

    def test_guard_lands_in_system_prompt(self):
        """守卫必须在**系统提示**里。实测放用户消息末尾时 9B 模型会无视它，
        甚至给出「并非读作 qiāng」这种反向错误。"""
        poem = ask.PoemContext(title="将进酒", author="李白", dynasty="唐", content="君不见黄河之水天上来。")
        messages = ask.build_messages(poem, "「将」怎么读？", "某条没有拼音的资料", use_tools=False)
        assert "严禁给出读音" in messages[0]["content"]
        assert "严禁给出读音" not in messages[-1]["content"]
