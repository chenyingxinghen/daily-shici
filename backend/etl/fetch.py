"""ETL `fetch` 阶段 —— 从上游仓库拉取原始数据。

按 `docs/01 §7.1` 的要求做到**幂等**与**可续传**：
  - 已存在且大小与预期一致的文件直接跳过；
  - 未完成的文件保留 `.part` 并用 HTTP `Range` 续传，字节偏移落在 `fetch_state.json`；
  - 单个文件失败重试并退避，不中断其余文件；
  - 全部拉完后按 `--loop` 反复补齐剩余文件（当前网络很慢，一次跑不完是常态）。

⚠️ **本机实测吞吐仅约 176 B/s**（2026-09-23），全量 283 MB 需约 20 天。
这不是脚本的问题，是当前出口带宽的问题 —— 所以脚本的价值在于**可累积、可续传**：
换到快网络（或把 `data_raw/` 直接拷过来）即可接着用。

用法：
    python fetch.py                 # 补齐所有缺失文件
    python fetch.py --only poet.tang.json
    python fetch.py --loop          # 反复重试直到全部完成（长时间任务）
    python fetch.py --verify        # 只校验已下载文件
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ETL_DIR = Path(__file__).resolve().parent
DATA_RAW = ETL_DIR / "data_raw"
STATE_FILE = ETL_DIR / "fetch_state.json"

RAW_BASE = "https://raw.githubusercontent.com/CanvaChen/llm-dataset-chinese-poetry/main"

#: (上游相对路径, 本地文件名, 预期字节数)
#:
#: 本地一律用 ASCII 名 —— 上游的中文路径在 Windows 命令行、日志、git diff 里
#: 反复出现编码问题，落盘时换掉。预期字节数取自 `docs/01 §2.1/§2.2` 的实测清单，
#: 用于判断「已下载完成」而不是「下了一半」。
SOURCES: list[tuple[str, str, int]] = [
    # ---- data/ （14 个文件，116.2 MB）----
    ("data/诗经/shijing.json", "shijing.json", 146_200),
    ("data/楚辞/chuci.json", "chuci.json", 103_100),
    ("data/曹操诗集/caocao.json", "caocao.json", 13_000),
    ("data/五代诗词/huajianji.json", "huajianji.json", 139_400),
    ("data/五代诗词/nantang.json", "nantang.json", 12_300),
    ("data/水墨唐诗/shuimotangshi.json", "shuimotangshi.json", 40_000),
    ("data/全唐诗/唐诗三百首.json", "tangshi-sanbai.json", 128_398),
    ("data/全唐诗/唐诗补录.json", "tangshi-bulu.json", 165),
    ("data/全唐诗/poet.tang.json", "poet.tang.json", 18_090_006),
    ("data/全唐诗/poet.song.json", "poet.song.json", 83_019_677),
    ("data/御定全唐诗/御定全唐诗.json", "yuding-quantangshi.json", 12_319_000),
    ("data/宋词/ci.song.json", "ci.song.json", 7_107_000),
    ("data/宋词/宋词三百首.json", "songci-sanbaishou.json", 121_100),
    ("data/纳兰性德/纳兰性德诗集.json", "nalanxingde.json", 77_000),  # docs/01 误记为「诗词」，实为「诗集」
    # ---- data2/ （22 个文件，166.9 MB）----
    ("data2/秦.json", "d2_qin.json", 400),
    ("data2/汉.json", "d2_han.json", 127_600),
    ("data2/魏晋.json", "d2_weijin.json", 1_048_576),
    ("data2/南北朝.json", "d2_nanbeichao.json", 1_468_006),
    ("data2/隋.json", "d2_sui.json", 337_920),
    ("data2/辽.json", "d2_liao.json", 4_915),
    ("data2/金.json", "d2_jin.json", 879_514),
    ("data2/元.json", "d2_yuan.json", 12_478_464),
    ("data2/元末明初.json", "d2_yuanmo_mingchu.json", 5_662_310),
    ("data2/明_1.json", "d2_ming_1.json", 18_874_368),
    ("data2/明_2.json", "d2_ming_2.json", 18_035_507),
    ("data2/明_3.json", "d2_ming_3.json", 18_350_464),
    ("data2/明_4.json", "d2_ming_4.json", 24_641_536),
    ("data2/明末清初.json", "d2_mingmo_qingchu.json", 6_396_314),
    ("data2/清_1.json", "d2_qing_1.json", 16_147_661),
    ("data2/清_2.json", "d2_qing_2.json", 17_721_344),
    ("data2/清末民国初.json", "d2_qingmo_minguochu.json", 6_081_741),
    ("data2/清末近现代初.json", "d2_qingmo_jinxiandaichu.json", 4_928_307),
    ("data2/民国末当代初.json", "d2_minguo_mo_dangdaichu.json", 714_000),
    ("data2/近现代.json", "d2_jinxiandai.json", 10_171_187),
    ("data2/近现代末当代初.json", "d2_jinxiandai_mo_dangdaichu.json", 1_404_928),
    ("data2/当代.json", "d2_dangdai.json", 9_752_576),
]

#: docs/01 §3.2 决策**不入库**的 12 个文件，因此根本不下载，省 329.5 KB。
EXCLUDED = (
    "data/论语/lunyu.json",
    "data/四书五经/daxue.json",
    "data/四书五经/zhongyong.json",
    "data/四书五经/mengzi.json",
    "data/蒙学/baijiaxing.json",
    "data/蒙学/qianziwen.json",
    "data/蒙学/sanzijing-new.json",
    "data/蒙学/sanzijing-traditional.json",
    "data/蒙学/dizigui.json",
    "data/蒙学/zhuzijiaxun.json",
    "data/蒙学/zengguangxianwen.json",
    "data/幽梦影/youmengying.json",
)

#: 大小容差：docs 的体积是「KB」量级的换算值，允许 2% 偏差才算「下完」。
SIZE_TOLERANCE = 0.02


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def is_complete(path: Path, expected: int) -> bool:
    if not path.exists() or expected <= 0:
        return path.exists() and path.stat().st_size > 0
    actual = path.stat().st_size
    return actual >= expected * (1 - SIZE_TOLERANCE)


def fetch_one(url: str, target: Path, expected: int, state: dict, name: str) -> bool:
    """拉单个文件，支持断点续传。返回是否完成。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(target.suffix + ".part")

    if is_complete(target, expected):
        return True

    # 续传起点：优先用 state，其次看 .part 的实际大小
    offset = int(state.get(name, {}).get("bytes", 0))
    if part.exists():
        offset = max(offset, part.stat().st_size)
    else:
        offset = 0

    headers = {"User-Agent": "daily-shici-etl/1.0"}
    if offset > 0:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(url, headers=headers)

    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            # 206 = 服务端接受续传；200 = 服务端忽略 Range，必须从头写
            appending = response.status == 206 and offset > 0
            if not appending:
                offset = 0
                part.unlink(missing_ok=True)

            written = offset
            last_report = time.time()
            last_saved = offset
            with part.open("ab" if appending else "wb") as fh:
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    fh.write(chunk)
                    written += len(chunk)
                    now = time.time()
                    if now - last_report >= 30:
                        pct = f"{written / expected:.1%}" if expected else "?"
                        rate = (written - last_saved) / (now - last_report)
                        print(
                            f"    {name}: {written:,} B ({pct})  {rate:.0f} B/s",
                            flush=True,
                        )
                        state[name] = {"bytes": written, "expected": expected}
                        save_state(state)
                        last_report = now
                        last_saved = written

            state[name] = {"bytes": written, "expected": expected}
            save_state(state)

        if is_complete(part, expected):
            part.replace(target)
            state[name] = {"bytes": target.stat().st_size, "expected": expected, "done": True}
            save_state(state)
            return True

        print(f"    {name}: 下到 {part.stat().st_size:,} B，未达预期 {expected:,} B，保留 .part 待续", flush=True)
        return False

    except urllib.error.HTTPError as exc:
        # 416 = Range 起点已超过文件长度，即**我们持有的部分就是全部**。
        # 触发原因是 docs/01 §2 的体积是 KB 级估算值，可能小于真实字节数，
        # 于是 is_complete() 一直判为未完成、续传又越界。
        if exc.code == 416 and part.exists():
            part.replace(target)
            state[name] = {"bytes": target.stat().st_size, "expected": expected, "done": True}
            save_state(state)
            print(f"    {name}: 服务端返回 416，判定已完整（{target.stat().st_size:,} B）", flush=True)
            return True
        print(f"    {name}: HTTP {exc.code} —— 已保存偏移", flush=True)
        return False

    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"    {name}: 中断（{exc}）—— 已保存偏移，可续传", flush=True)
        if part.exists():
            state[name] = {"bytes": part.stat().st_size, "expected": expected}
            save_state(state)
        return False


def run_once(only: str | None, verify_only: bool) -> tuple[int, int]:
    state = load_state()
    done = missing = 0

    for upstream, local, expected in SOURCES:
        if only and only not in local and only not in upstream:
            continue
        target = DATA_RAW / local
        if verify_only:
            ok = is_complete(target, expected)
            print(f"  {'OK  ' if ok else 'MISS'} {local:<38} {target.stat().st_size if target.exists() else 0:,} / {expected:,}")
            done += ok
            missing += not ok
            continue

        if is_complete(target, expected):
            done += 1
            continue

        print(f"  → {local}  ({upstream})", flush=True)
        url = RAW_BASE + "/" + urllib.parse.quote(upstream)
        if fetch_one(url, target, expected, state, local):
            done += 1
            print(f"    {local}: 完成 ({target.stat().st_size:,} B)", flush=True)
        else:
            missing += 1

    return done, missing


def main() -> int:
    parser = argparse.ArgumentParser(description="拉取上游原始数据（可续传）")
    parser.add_argument("--only", help="只处理文件名匹配此子串的条目")
    parser.add_argument("--verify", action="store_true", help="只校验，不下载")
    parser.add_argument("--loop", action="store_true", help="反复重试直到全部完成")
    parser.add_argument("--pause", type=int, default=60, help="--loop 每轮之间的间隔秒数")
    args = parser.parse_args()

    print(f"上游：{RAW_BASE}")
    print(f"目标：{DATA_RAW}")
    print(f"清单：{len(SOURCES)} 个文件；按 docs/01 §3.2 排除 {len(EXCLUDED)} 个不下载\n")

    round_no = 0
    while True:
        round_no += 1
        print(f"=== 第 {round_no} 轮 ===", flush=True)
        done, missing = run_once(args.only, args.verify)
        print(f"完成 {done} / 缺失 {missing}\n", flush=True)

        if args.verify or missing == 0 or not args.loop:
            return 0 if missing == 0 else 1
        time.sleep(args.pause)


if __name__ == "__main__":
    sys.exit(main())
