"""
MusWall Wallpaper Engine (Python + Pillow)
===========================================
This module handles all image-processing logic for MusWall using the Pillow library.
It is invoked from Kotlin via Chaquopy.
"""

import io
from typing import Tuple
from PIL import Image, ImageFilter, ImageEnhance, ImageDraw, ImageOps

DEFAULT_WIDTH = 1080
DEFAULT_HEIGHT = 2400

def create_rounded_mask(size: Tuple[int, int], radius: int) -> Image.Image:
    w, h = size
    scale = 2
    mask = Image.new("L", (w * scale, h * scale), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        [(0, 0), (w * scale, h * scale)],
        radius=radius * scale,
        fill=255
    )
    return mask.resize((w, h), Image.Resampling.LANCZOS)

def create_drop_shadow(
    size: Tuple[int, int],
    radius: int,
    offset: Tuple[int, int] = (0, 15),
    blur: int = 25,
    shadow_color: Tuple[int, int, int, int] = (0, 0, 0, 160)
) -> Image.Image:
    w, h = size
    padding = blur * 3
    shadow_w = w + padding * 2
    shadow_h = h + padding * 2
    
    shadow_img = Image.new("RGBA", (shadow_w, shadow_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(shadow_img)
    left = padding + offset[0]
    top = padding + offset[1]
    draw.rounded_rectangle(
        [(left, top), (left + w, top + h)],
        radius=radius,
        fill=shadow_color
    )
    return shadow_img.filter(ImageFilter.GaussianBlur(blur))

def process_wallpaper(
    artwork_bytes: bytes,
    target_width: int = DEFAULT_WIDTH,
    target_height: int = DEFAULT_HEIGHT,
    blur_radius: float = 35.0,
    darkness: float = 0.45,
    art_scale: float = 0.72,
    corner_radius: int = 40,
    add_shadow: bool = True,
    output_format: str = "JPEG",
    quality: int = 92
) -> bytes:
    if not artwork_bytes:
        raise ValueError("artwork_bytes cannot be empty")

    src_image = Image.open(io.BytesIO(artwork_bytes))
    src_image = ImageOps.exif_transpose(src_image)
    if src_image.mode != "RGBA":
        src_image = src_image.convert("RGBA")

    # Aspect fill background crop & blur
    bg_image = ImageOps.fit(
        src_image,
        (target_width, target_height),
        method=Image.Resampling.LANCZOS,
        centering=(0.5, 0.5)
    )

    if blur_radius > 0:
        small_w = max(64, target_width // 4)
        small_h = max(64, target_height // 4)
        bg_small = bg_image.resize((small_w, small_h), Image.Resampling.BILINEAR)
        bg_blurred_small = bg_small.filter(ImageFilter.GaussianBlur(radius=blur_radius / 4.0))
        bg_image = bg_blurred_small.resize((target_width, target_height), Image.Resampling.BICUBIC)

    if darkness > 0.0:
        clamped_darkness = min(max(darkness, 0.0), 0.95)
        enhancer = ImageEnhance.Brightness(bg_image)
        bg_image = enhancer.enhance(1.0 - (clamped_darkness * 0.75))
        alpha = int(clamped_darkness * 255 * 0.55)
        overlay = Image.new("RGBA", (target_width, target_height), (0, 0, 0, alpha))
        bg_image = Image.alpha_composite(bg_image, overlay)

    art_target_w = int(target_width * min(max(art_scale, 0.3), 0.95))
    art_target_h = art_target_w
    resized_art = src_image.resize((art_target_w, art_target_h), Image.Resampling.LANCZOS)

    if corner_radius > 0:
        mask = create_rounded_mask((art_target_w, art_target_h), corner_radius)
        rounded_art = Image.new("RGBA", (art_target_w, art_target_h), (0, 0, 0, 0))
        rounded_art.paste(resized_art, (0, 0), mask=mask)
        resized_art = rounded_art

    center_x = (target_width - art_target_w) // 2
    center_y = int((target_height - art_target_h) * 0.46)

    if add_shadow and corner_radius > 0:
        shadow_blur = 30
        shadow = create_drop_shadow(
            size=(art_target_w, art_target_h),
            radius=corner_radius,
            offset=(0, 18),
            blur=shadow_blur,
            shadow_color=(0, 0, 0, 180)
        )
        shadow_padding = shadow_blur * 3
        bg_image.paste(shadow, (center_x - shadow_padding, center_y - shadow_padding), mask=shadow)

    bg_image.paste(resized_art, (center_x, center_y), mask=resized_art)

    output_buffer = io.BytesIO()
    if output_format.upper() == "PNG":
        bg_image.save(output_buffer, format="PNG", optimize=True)
    else:
        rgb_image = bg_image.convert("RGB")
        rgb_image.save(output_buffer, format="JPEG", quality=quality, optimize=True)

    return output_buffer.getvalue()