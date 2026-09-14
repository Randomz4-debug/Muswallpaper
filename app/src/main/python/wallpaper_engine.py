"""Fast, deterministic MusWall renderer for low-end Android phones."""
import io
import re
from PIL import Image, ImageFilter, ImageEnhance, ImageDraw, ImageOps


def _fit(im, size):
    return ImageOps.fit(im, size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))


def _round(im, radius):
    radius = max(0, min(int(radius), min(im.size) // 2))
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius=radius, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def _hex(value, fallback):
    text = str(value or "").strip().lstrip("#")
    if not re.fullmatch(r"[0-9a-fA-F]{6}", text):
        text = fallback.lstrip("#")
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))


def _gradient(size, c1, c2):
    w, h = size
    out = Image.new("RGBA", size, c1 + (255,))
    px = out.load()
    den = max(1, h - 1)
    for y in range(h):
        t = y / den
        row = tuple(int(c1[i] * (1.0 - t) + c2[i] * t) for i in range(3)) + (255,)
        for x in range(w):
            px[x, y] = row
    return out


def process_wallpaper(artwork_bytes: bytes, target_width=720, target_height=1600,
                      blur_radius=80.0, darkness=0.0, art_scale=0.72,
                      corner_radius=42, add_shadow=True, effect="BLUR",
                      blur_type="GAUSSIAN", cover_height=44, cover_offset=50,
                      transition_height=20, background_mode="ART",
                      background_color="#111111", background_color2="#5E2CA5",
                      accent_color="#7C00FF", output_format="JPEG", quality=84):
    if not artwork_bytes:
        raise ValueError("empty artwork")

    src = Image.open(io.BytesIO(artwork_bytes)).convert("RGBA")
    w = max(480, min(int(target_width), 900))
    h = max(960, min(int(target_height), 1800))

    avg = src.resize((1, 1), Image.Resampling.BILINEAR).getpixel((0, 0))[:3]
    c1 = _hex(background_color, "#111111")
    c2 = _hex(background_color2, "#5E2CA5")
    accent = _hex(accent_color, "#7C00FF")

    # Build the background independently from the album-card layer.
    if background_mode == "COLOR":
        bg = Image.new("RGBA", (w, h), c1 + (255,))
    elif background_mode == "GRADIENT":
        bg = _gradient((w, h), c1, c2)
    elif background_mode == "AUTO":
        auto = tuple(int(v * 0.72) for v in avg)
        bg = Image.new("RGBA", (w, h), auto + (255,))
    else:
        bg = _fit(src, (w, h))
        sw, sh = max(96, w // 5), max(160, h // 5)
        small = bg.resize((sw, sh), Image.Resampling.BILINEAR)
        radius = max(0.0, min(float(blur_radius), 100.0)) / 5.0
        if blur_type == "SOLID":
            bg = small.resize((w, h), Image.Resampling.BILINEAR)
        elif blur_type == "GLASS":
            bg = ImageEnhance.Brightness(
                small.filter(ImageFilter.GaussianBlur(max(2, radius * 1.4))).resize((w, h), Image.Resampling.BILINEAR)
            ).enhance(1.06)
        else:
            bg = small.filter(ImageFilter.GaussianBlur(max(1, radius))).resize((w, h), Image.Resampling.BILINEAR)

    d = max(0.0, min(float(darkness) / 100.0, 1.0))
    if d:
        bg = ImageEnhance.Brightness(bg).enhance(1.0 - d * 0.65)
        bg = Image.alpha_composite(bg, Image.new("RGBA", (w, h), (0, 0, 0, int(d * 150))))

    # Cover Scale + Cover Height both affect the actual cover size.
    scale = max(0.2, min(float(art_scale), 1.0))
    height_factor = 0.70 + (max(0, min(int(cover_height), 100)) / 100.0) * 0.60
    size = int(min(w, h) * scale * height_factor)
    size = max(int(min(w, h) * 0.20), min(size, int(min(w, h) * 0.86)))
    art = _fit(src, (size, size))

    if effect == "CD":
        mask = Image.new("L", art.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        disc = Image.new("RGBA", art.size, (0, 0, 0, 0))
        disc.paste(art, (0, 0), mask)
        # Center hole + subtle ring make the CD effect actually visible.
        dd = ImageDraw.Draw(disc)
        hole = max(4, size // 18)
        dd.ellipse((size // 2 - hole, size // 2 - hole, size // 2 + hole, size // 2 + hole), fill=(235, 235, 235, 210))
        dd.ellipse((size // 2 - 2, size // 2 - 2, size // 2 + 2, size // 2 + 2), fill=(30, 30, 30, 230))
        art = disc
    elif effect == "SQUARE":
        art = _round(art, 18)
    else:
        art = _round(art, int(corner_radius))

    # Cover Offset is a stable vertical position: 0 = upper, 50 = center, 100 = lower.
    offset = max(0, min(int(cover_offset), 100)) / 100.0
    y = int((h - size) * offset)
    x = (w - size) // 2

    # Transition height is a soft color fade behind the cover, not a no-op slider.
    transition = max(0, min(int(transition_height), 100))
    if transition:
        band = max(30, int(h * (0.10 + transition * 0.003)))
        glow = Image.new("RGBA", (size + band * 2, size + band * 2), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        gd.rounded_rectangle((band, band, band + size, band + size), radius=max(24, int(corner_radius)), fill=accent + (42,))
        glow = glow.filter(ImageFilter.GaussianBlur(max(8, band // 3)))
        bg.alpha_composite(glow, (x - band, y - band))

    if add_shadow:
        shadow = Image.new("RGBA", (size + 50, size + 50), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((25, 25, size + 24, size + 24), radius=30, fill=(0, 0, 0, 145))
        shadow = shadow.filter(ImageFilter.GaussianBlur(14))
        bg.alpha_composite(shadow, (x - 25, y - 25))

    bg.alpha_composite(art, (x, y))

    if effect == "COVER_COLOR" or background_mode == "AUTO":
        bg = Image.alpha_composite(bg, Image.new("RGBA", (w, h), avg + (34,)))

    # A tiny accent border makes the chosen color visible without covering artwork.
    border = Image.new("RGBA", (size + 4, size + 4), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle((1, 1, size + 2, size + 2), radius=max(2, int(corner_radius)), outline=accent + (105,), width=2)
    bg.alpha_composite(border, (x - 2, y - 2))

    out = io.BytesIO()
    bg.convert("RGB").save(out, format=output_format, quality=max(70, min(int(quality), 90)), optimize=True)
    return out.getvalue()
