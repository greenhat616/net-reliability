"""Generates the Workshop preview and feature cards for Net Reliability.

Same look as the other mods in this repo (Team Map Sync, Coop Pause): scenes are drawn at low resolution and
upscaled with nearest-neighbour so they read as pixel art; text is drawn afterwards at full resolution. The game only
shows the Workshop upload button for a mod whose jar contains resources/preview.png.

The bar chart uses the measured numbers from docs/RESULTS.md (round 2 means); update RESULTS below with them.

Usage: python tools/make_workshop_images.py .
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(sys.argv[1])
FONTS = Path("C:/Windows/Fonts")

BG = (18, 24, 38)
BG_SPECK = (28, 36, 54)
CREAM = (246, 232, 196)
OUTLINE = (34, 24, 20)
ACCENT = (255, 196, 84)
LOST = (214, 70, 60)
RESENT = (120, 220, 120)
WIRE = (70, 84, 110)
TEXT_DIM = (196, 210, 222)
VANILLA_C = (150, 150, 160)
NACK_C = ACCENT
KCP_C = (110, 190, 255)

# docs/RESULTS.md, round 2 mean load time per teleport (ms)
RESULTS = [
    ("0%", 1095, 1097, 1121),
    ("10%", 6433, 2599, 1144),
    ("20%", 9990, 3730, 1161),
]


def font(name, size):
    return ImageFont.truetype(str(FONTS / name), size)


def background(w, h, seed):
    rng = random.Random(seed)
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    for _ in range(w * h // 40):
        d.point((rng.randrange(w), rng.randrange(h)), fill=BG_SPECK)
    return img


def player(d, x, y, shirt, s=1):
    """A tiny Necesse-ish settler; (x, y) is the feet."""
    def px(a, b, c, e, f):
        d.rectangle([x + a * s, y + b * s, x + c * s - 1, y + e * s - 1], fill=f)
    px(-4, -9, 5, -3, OUTLINE)
    px(-4, -16, 4, -8, OUTLINE)
    px(-3, -14, 3, -8, (232, 186, 150))
    px(-3, -15, 3, -13, (92, 60, 40))
    px(-1, -11, 0, -10, OUTLINE)
    px(1, -11, 2, -10, OUTLINE)
    px(-4, -8, 4, -3, shirt)
    px(-3, -3, -1, 0, (60, 60, 90))
    px(1, -3, 3, 0, (60, 60, 90))


def server_box(d, x, y):
    """A small server rack; (x, y) is its top-left corner."""
    d.rectangle([x - 1, y - 1, x + 22, y + 30], fill=OUTLINE)
    d.rectangle([x, y, x + 21, y + 29], fill=(92, 100, 118))
    for i in range(3):
        yy = y + 3 + i * 9
        d.rectangle([x + 3, yy, x + 18, yy + 5], fill=(54, 60, 74))
        d.rectangle([x + 14, yy + 2, x + 15, yy + 3], fill=(120, 220, 120))


def packet(d, x, y, fill, size=6):
    d.rectangle([x - 1, y - 1, x + size, y + size], fill=OUTLINE)
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=fill)


def lost_packet(d, x, y, size=6):
    """A dropped datagram: a dashed outline with a red cross."""
    for i in range(0, size + 1, 2):
        d.point((x + i, y - 1), fill=LOST)
        d.point((x + i, y + size), fill=LOST)
        d.point((x - 1, y + i), fill=LOST)
        d.point((x + size, y + i), fill=LOST)
    d.line([(x + 1, y + 1), (x + size - 2, y + size - 2)], fill=LOST)
    d.line([(x + size - 2, y + 1), (x + 1, y + size - 2)], fill=LOST)


def back_arrow(d, x0, x1, y, color):
    """An outlined arrow from x1 back to x0 (right to left): the NACK."""
    d.rectangle([x0 + 5, y - 2, x1, y + 2], fill=OUTLINE)
    d.polygon([(x0 - 1, y), (x0 + 6, y - 6), (x0 + 6, y + 6)], fill=OUTLINE)
    d.rectangle([x0 + 6, y - 1, x1 - 1, y + 1], fill=color)
    d.polygon([(x0 + 1, y), (x0 + 6, y - 4), (x0 + 6, y + 4)], fill=color)


def link_scene(d, sx, px, y, lost_at, resent=True):
    """Server at sx, player at px, a wire between them carrying datagrams; one is lost and (optionally) resent."""
    server_box(d, sx - 11, y - 30)
    player(d, px, y, (64, 120, 196))
    wire_y = y - 14
    d.line([(sx + 13, wire_y), (px - 7, wire_y)], fill=WIRE, width=1)
    step = 12
    for i, x in enumerate(range(sx + 18, px - 14, step)):
        if i == lost_at:
            lost_packet(d, x, wire_y - 3)
        else:
            packet(d, x, wire_y - 3, CREAM)
    if resent:
        lost_x = sx + 18 + lost_at * step
        back_arrow(d, sx + 14, px - 10, wire_y - 16, LOST)
        packet(d, lost_x, wire_y + 9, RESENT)


def preview(path):
    img = background(160, 160, seed=7)
    d = ImageDraw.Draw(img)
    link_scene(d, 28, 132, 118, lost_at=4)
    img = img.resize((640, 640), Image.NEAREST)
    d = ImageDraw.Draw(img)
    d.text((320, 96), "NET", font=font("ariblk.ttf", 76), fill=CREAM, anchor="mm", stroke_width=6, stroke_fill=OUTLINE)
    d.text((320, 176), "RELIABILITY", font=font("ariblk.ttf", 60), fill=CREAM, anchor="mm", stroke_width=6, stroke_fill=OUTLINE)
    d.text((320, 590), "lost packets, resent", font=font("segoeuib.ttf", 30), fill=ACCENT, anchor="mm", stroke_width=3,
           stroke_fill=OUTLINE)
    img.save(path, optimize=True)


def card(path, title_zh, title_en, lines, art, seed):
    """1280x720 feature card: text panel on the left, art on the right (same layout as Team Map Sync)."""
    w, h = 1280, 720
    low = background(w // 4, h // 4, seed)
    if "low" in art:
        art["low"](ImageDraw.Draw(low))
    img = low.resize((w, h), Image.NEAREST).convert("RGBA")
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.rounded_rectangle([60, 70, 700, 640], radius=18, fill=(10, 16, 28, 215), outline=(246, 232, 196, 90), width=2)
    img = Image.alpha_composite(img, overlay)
    d = ImageDraw.Draw(img)
    d.text((100, 110), title_zh, font=font("msyhbd.ttc", 52), fill=CREAM)
    d.text((102, 182), title_en, font=font("segoeuib.ttf", 26), fill=ACCENT)
    y = 250
    for zh, en in lines:
        d.rectangle([102, y + 14, 114, y + 26], fill=ACCENT)
        d.text((134, y - 4), zh, font=font("msyh.ttc", 28), fill=CREAM)
        d.text((134, y + 36), en, font=font("segoeuib.ttf", 19), fill=TEXT_DIM)
        y += 92
    if "full" in art:
        art["full"](d)
    d.text((w - 40, h - 30), "Net Reliability · Necesse", font=font("segoeuib.ttf", 20), fill=TEXT_DIM, anchor="rm")
    img.convert("RGB").save(path, optimize=True)


def chart(d):
    """Bar chart of RESULTS, drawn at full resolution."""
    x0, y0, x1, y1 = 760, 140, 1220, 560
    d.text(((x0 + x1) // 2, y0 - 30), "加载耗时（秒）/ Load time (s)", font=font("msyhbd.ttc", 24), fill=CREAM, anchor="mm")
    top = 10.0
    d.line([(x0, y1), (x1, y1)], fill=TEXT_DIM, width=2)
    for s in (0, 2, 4, 6, 8, 10):
        yy = y1 - (y1 - y0) * s / top
        d.line([(x0, yy), (x1, yy)], fill=(60, 70, 90), width=1)
        d.text((x0 - 10, yy), str(s), font=font("segoeuib.ttf", 16), fill=TEXT_DIM, anchor="rm")
    group = (x1 - x0) / len(RESULTS)
    bar = group / 4.5
    for gi, (label, *values) in enumerate(RESULTS):
        gx = x0 + gi * group + group / 2 - bar * 1.5
        for bi, (ms, color) in enumerate(zip(values, (VANILLA_C, NACK_C, KCP_C))):
            bx = gx + bi * bar
            top_y = y1 - (y1 - y0) * min(ms / 1000.0, top) / top
            d.rectangle([bx, top_y, bx + bar - 4, y1], fill=color, outline=OUTLINE, width=2)
            d.text((bx + (bar - 4) / 2, top_y - 4), "%.1f" % (ms / 1000.0), font=font("segoeuib.ttf", 15), fill=CREAM,
                   anchor="mb")
        d.text((x0 + gi * group + group / 2, y1 + 22), "丢包 " + label, font=font("msyh.ttc", 20), fill=CREAM, anchor="mm")
    lx = x0 - 20
    for name, color, width in (("原版 Vanilla", VANILLA_C, 150), ("NACK（默认）", NACK_C, 150), ("KCP（实验性）", KCP_C, 0)):
        d.rectangle([lx, y1 + 52, lx + 16, y1 + 68], fill=color, outline=OUTLINE)
        d.text((lx + 24, y1 + 60), name, font=font("msyhbd.ttc", 17), fill=TEXT_DIM, anchor="lm")
        lx += width


ART_NACK = {"low": lambda d: link_scene(d, 214, 296, 120, lost_at=2)}
ART_CHART = {"full": chart}
ART_KCP = {"low": lambda d: (link_scene(d, 214, 296, 90, lost_at=1), link_scene(d, 214, 296, 150, lost_at=4))}


def main():
    res = ROOT / "src/main/resources"
    out = ROOT / "docs/workshop/images"
    res.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    preview(res / "preview.png")
    card(out / "01-nack.png", "丢了的分片，马上补发", "Lost fragments are resent at once", [
        ("原版：丢一片就整包作废，2 秒后重新请求", "Vanilla: one lost fragment drops the whole packet"),
        ("本 Mod：发现缺片立即请求，只补缺的那几片", "This mod asks for just the missing fragments"),
        ("顺带修复原版第 0 片晚到就永远拼不齐的 bug", "Also fixes a vanilla bug with late first fragments"),
        ("没有丢包时不产生任何额外流量", "No extra traffic when nothing is lost"),
    ], ART_NACK, seed=11)
    card(out / "02-results.png", "丢包时地图加载更快", "Faster loading on a lossy link", [
        ("实测：传送后区域加载完成的时间", "Measured: time to load the area after a teleport"),
        ("10% 丢包：6.4 秒 → 2.6 秒（KCP 1.1 秒）", "10% loss: 6.4 s -> 2.6 s (KCP 1.1 s)"),
        ("20% 丢包：10.0 秒 → 3.7 秒（KCP 1.2 秒）", "20% loss: 10.0 s -> 3.7 s (KCP 1.2 s)"),
        ("无丢包时与原版完全相同", "Same as vanilla when nothing is lost"),
    ], ART_CHART, seed=23)
    card(out / "03-kcp.png", "实验性：KCP 可靠传输", "Experimental: KCP transport", [
        ("通过 IP 加入时，整个连接跑在 KCP 上", "Joining by IP can run the whole link over KCP"),
        ("任何丢失的数据报都会重传，单包也不例外", "Every lost datagram is resent, not just fragments"),
        ("默认关闭，在 Mod 设置页里打开", "Off by default: turn it on in the mod settings"),
        ("Steam 好友联机不受影响", "Steam friend joins are not affected"),
    ], ART_KCP, seed=31)
    for p in [res / "preview.png", *sorted(out.glob("*.png"))]:
        print(p, Image.open(p).size, p.stat().st_size // 1024, "KB")


if __name__ == "__main__":
    main()
