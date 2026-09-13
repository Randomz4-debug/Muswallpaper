"""Fast MusWall image renderer. Kept deliberately small for low-end phones."""
import io
from PIL import Image, ImageFilter, ImageEnhance, ImageDraw, ImageOps


def _fit(im, size):
    return ImageOps.fit(im, size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))


def _round(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius=radius, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def process_wallpaper(artwork_bytes: bytes, target_width=720, target_height=1600,
                      blur_radius=80.0, darkness=0.0, art_scale=0.72,
                      corner_radius=42, add_shadow=True, effect="BLUR",
                      blur_type="GAUSSIAN", cover_height=44, cover_offset=50,
                      transition_height=20, output_format="JPEG", quality=88):
    if not artwork_bytes:
        raise ValueError("empty artwork")

    src = Image.open(io.BytesIO(artwork_bytes)).convert("RGBA")
    # Work at a bounded resolution: this is a major lag/memory reduction on budget phones.
    w = max(480, min(int(target_width), 1080))
    h = max(960, min(int(target_height), 2400))
    bg = _fit(src, (w, h))

    if effect in ("BLUR", "COVER", "COVER_COLOR"):
        # Downsample before blur; visually similar but much cheaper.
        sw, sh = max(96, w // 5), max(160, h // 5)
        small = bg.resize((sw, sh), Image.Resampling.BILINEAR)
        radius = max(0.0, min(float(blur_radius), 100.0)) / 5.0
        if blur_type == "SOLID":
            bg = small.resize((w, h), Image.Resampling.BILINEAR)
        elif blur_type == "MOTION":
            b = small.filter(ImageFilter.GaussianBlur(radius=max(1, radius)))
            bg = b.resize((w, h), Image.Resampling.BILINEAR)
        elif blur_type == "GLASS":
            b = small.filter(ImageFilter.GaussianBlur(radius=max(2, radius * 1.4)))
            bg = b.resize((w, h), Image.Resampling.BILINEAR)
            bg = ImageEnhance.Brightness(bg).enhance(1.06)
        else:
            b = small.filter(ImageFilter.GaussianBlur(radius=max(1, radius)))
            bg = b.resize((w, h), Image.Resampling.BILINEAR)
    elif effect == "CD":
        bg = _fit(src, (w, h)).filter(ImageFilter.GaussianBlur(8))
    elif effect == "SQUARE":
        bg = _fit(src, (w, h))

    d = max(0.0, min(float(darkness) / 100.0, 1.0))
    if d:
        bg = ImageEnhance.Brightness(bg).enhance(1.0 - d * 0.65)
        overlay = Image.new("RGBA", (w, h), (0, 0, 0, int(d * 150)))
        bg = Image.alpha_composite(bg, overlay)

    # Centered album art / cover card.
    size = int(min(w, h) * max(0.2, min(float(art_scale), 1.0)))
    art = _fit(src, (size, size))
    if effect == "CD":
        # Simple disc look without expensive transforms.
        mask = Image.new("L", art.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        disc = Image.new("RGBA", art.size, (0, 0, 0, 0))
        disc.paste(art, (0, 0), mask)
        art = disc
    elif effect == "SQUARE":
        art = _round(art, 18)
    else:
        art = _round(art, int(corner_radius))

    y_ratio = max(0.05, min(0.95, 0.46 + (cover_offset - 50) / 250.0))
    x = (w - size) // 2
    y = int((h - size) * y_ratio)

    if add_shadow:
        shadow = Image.new("RGBA", (size + 50, size + 50), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((25, 25, size + 24, size + 24), radius=30, fill=(0, 0, 0, 145))
        shadow = shadow.filter(ImageFilter.GaussianBlur(14))
        bg.alpha_composite(shadow, (x - 25, y - 25))
    bg.alpha_composite(art, (x, y))

    if effect == "COVER_COLOR":
        # Subtle color wash from the cover's average color.
        avg = src.resize((1, 1), Image.Resampling.BILINEAR).getpixel((0, 0))
        wash = Image.new("RGBA", (w, h), (avg[0], avg[1], avg[2], 28))
        bg = Image.alpha_composite(bg, wash)

    out = io.BytesIO()
    bg.convert("RGB").save(out, format=output_format, quality=quality, optimize=True)
    return out.getvalue()
