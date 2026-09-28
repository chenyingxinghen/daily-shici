"""生成 App 图标 —— 复用设计稿里的「朱红印章」母题。

产出：
  drawable-<density>/ic_launcher_foreground.png   自适应图标前景（纸色「詩」+ 透明底）
  mipmap-<density>/ic_launcher.png                传统图标（朱红圆角方印，API < 26 用）
  mipmap-<density>/ic_launcher_round.png          传统圆形图标
  drawable-<density>/ic_notification_poem.png     通知栏小图标（纯白「詩」+ 透明底）
  tools/icon_preview.png                          512px 预览图，供人眼确认

**为什么用栅格化而不是 AI 生图**：印章的核心是那个「詩」字，而图像模型渲染汉字
极易走形（多笔、少笔、变成花纹）。本机装了 `NotoSerifSC-VF.ttf` —— 正是设计稿
指定的字体 —— 用它精确取字形，既准又可复现。

用法：
    python tools/make_app_icon.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---- 设计令牌（与 ui/theme/Color.kt 一致） --------------------------------
VERMILION = (0xC5, 0x3A, 0x2A, 0xFF)
PAPER = (0xFA, 0xF5, 0xEB, 0xFF)
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)

GLYPH = "詩"  # 设计稿印章里的字，繁体，保留

FONT_CANDIDATES = (
    r"C:\Windows\Fonts\NotoSerifSC-VF.ttf",
    r"C:\Windows\Fonts\STZHONGS.TTF",
    r"C:\Windows\Fonts\simsun.ttc",
)

FRONTEND = Path(__file__).resolve().parent.parent
RES = FRONTEND / "app" / "src" / "main" / "res"

#: 密度名 → 倍率（mdpi = 1x）
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

FOREGROUND_DP = 108   # 自适应图标画布
LEGACY_DP = 48        # 传统图标边长
NOTIFICATION_DP = 24

#: 自适应图标安全区：108 画布里只有中间 66–72 可见，
#: 故字形按 108 的 52% 画（再留出视觉余量），避免被圆形/方形遮罩切掉。
FOREGROUND_GLYPH_RATIO = 0.52
LEGACY_GLYPH_RATIO = 0.62  # 传统图标无遮罩裁切，可以画大一些


def load_font(px: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if not Path(path).exists():
            continue
        font = ImageFont.truetype(path, px)
        # 变体字体：尽量取 Bold，与设计稿印章字重一致；取不到就用默认实例
        for name in ("Bold", "SemiBold", "Regular"):
            try:
                font.set_variation_by_name(name)
                break
            except Exception:
                continue
        return font
    raise SystemExit("找不到可用的中文衬线字体")


def draw_glyph(size: int, ratio: float, color: tuple) -> Image.Image:
    """在透明画布上居中画一个字。"""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    font = load_font(max(1, int(size * ratio)))
    draw = ImageDraw.Draw(canvas)

    # 用 anchor="mm" 以「字形视觉中心」对齐，而不是以 em 框对齐 ——
    # 汉字在 em 框里偏下，直接居中会显得上重下轻。
    draw.text((size / 2, size / 2), GLYPH, font=font, fill=color, anchor="mm")
    return canvas


def rounded_mask(size: int, radius_ratio: float) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * radius_ratio), fill=255
    )
    return mask


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def write(image: Image.Image, folder: str, name: str) -> Path:
    target_dir = RES / folder
    target_dir.mkdir(parents=True, exist_ok=True)
    path = target_dir / name
    image.save(path, format="PNG", optimize=True)
    return path


def main() -> int:
    written: list[Path] = []

    for density, scale in DENSITIES.items():
        # ---- 自适应图标前景：透明底 + 纸色「詩」 ----
        fg_px = int(FOREGROUND_DP * scale)
        foreground = draw_glyph(fg_px, FOREGROUND_GLYPH_RATIO, PAPER)
        written.append(write(foreground, f"drawable-{density}", "ic_launcher_foreground.png"))

        # ---- 传统图标：朱红底（圆角方 / 圆）+ 纸色「詩」 ----
        legacy_px = int(LEGACY_DP * scale)
        glyph_layer = draw_glyph(legacy_px, LEGACY_GLYPH_RATIO, PAPER)

        base = Image.new("RGBA", (legacy_px, legacy_px), VERMILION)
        base.putalpha(rounded_mask(legacy_px, 0.22))
        base.alpha_composite(glyph_layer)
        written.append(write(base, f"mipmap-{density}", "ic_launcher.png"))

        round_base = Image.new("RGBA", (legacy_px, legacy_px), VERMILION)
        round_base.putalpha(circle_mask(legacy_px))
        round_base.alpha_composite(glyph_layer)
        written.append(write(round_base, f"mipmap-{density}", "ic_launcher_round.png"))

        # ---- 通知栏小图标：纯白「詩」。系统会给它着色，必须单色 ----
        notif_px = int(NOTIFICATION_DP * scale)
        notification = draw_glyph(notif_px, 0.92, WHITE)
        written.append(write(notification, f"drawable-{density}", "ic_notification_poem.png"))

    # ---- 人眼预览图（放进工具目录，不参与打包） ----
    preview_px = 512
    preview = Image.new("RGBA", (preview_px, preview_px), PAPER)
    seal_px = 320
    seal = Image.new("RGBA", (seal_px, seal_px), VERMILION)
    seal.putalpha(rounded_mask(seal_px, 0.22))
    seal.alpha_composite(draw_glyph(seal_px, LEGACY_GLYPH_RATIO, PAPER))
    preview.alpha_composite(seal, ((preview_px - seal_px) // 2, (preview_px - seal_px) // 2))
    preview_path = FRONTEND / "tools" / "icon_preview.png"
    preview_path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(preview_path, format="PNG")
    written.append(preview_path)

    # 旧模板留下的 webp 会与新 PNG 同名冲突（同一资源目录不允许两份同名资源）
    removed: list[str] = []
    for density in DENSITIES:
        for stale in ("ic_launcher.webp", "ic_launcher_round.webp"):
            path = RES / f"mipmap-{density}" / stale
            if path.exists():
                path.unlink()
                removed.append(str(path.relative_to(RES)))

    print(f"生成 {len(written)} 个文件：")
    for path in written:
        print(f"  {path.relative_to(FRONTEND)}  ({path.stat().st_size} 字节)")
    if removed:
        print(f"删除旧模板图标 {len(removed)} 个：{removed[:4]} …")
    print(f"\n预览图：{preview_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
