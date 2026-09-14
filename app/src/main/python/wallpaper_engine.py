from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
import colorsys


def _hex(value, fallback=(255, 255, 255)):
    try:
        s = str(value or '').strip().lstrip('#')
        if len(s) == 6:
            return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))
    except Exception:
        pass
    return fallback


def _fit_cover(im, size):
    w, h = size
    scale = max(w / im.width, h / im.height)
    nw, nh = max(1, int(im.width * scale)), max(1, int(im.height * scale))
    im = im.resize((nw, nh), Image.Resampling.LANCZOS)
    left, top = (nw - w) // 2, (nh - h) // 2
    return im.crop((left, top, left + w, top + h))


def _fit_contain(im, size):
    w, h = size
    scale = min(w / im.width, h / im.height)
    return im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.Resampling.LANCZOS)


def _rounded_mask(size, radius):
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=max(0, radius), fill=255)
    return mask


def _apply_blur(im, radius, blur_type):
    radius = max(0.0, float(radius))
    if radius <= 0:
        return im
    if blur_type == 'solid':
        return im.filter(ImageFilter.BoxBlur(max(1, int(radius))))
    if blur_type == 'motion':
        r = max(1, int(radius))
        a = im.filter(ImageFilter.GaussianBlur(r * 0.55))
        b = im.transform(im.size, Image.AFFINE, (1, 0, -r * 0.35, 0, 1, 0), resample=Image.Resampling.BILINEAR)
        return Image.blend(a, b, 0.35)
    if blur_type == 'glass':
        return im.filter(ImageFilter.GaussianBlur(radius * 0.7)).filter(ImageFilter.SMOOTH_MORE)
    return im.filter(ImageFilter.GaussianBlur(radius))


def _dominant_color(im):
    small = im.resize((1, 1), Image.Resampling.BILINEAR).convert('RGB')
    return small.getpixel((0, 0))


def _draw_text_center(draw, box, text, fill, size):
    if not text:
        return
    # Default PIL font is used for maximum Android portability.
    try:
        from PIL import ImageFont
        font = ImageFont.load_default(size=max(10, int(size))) if hasattr(ImageFont, 'load_default') else None
    except Exception:
        font = None
    bbox = draw.textbbox((0, 0), text, font=font)
    x = (box[0] + box[2] - (bbox[2] - bbox[0])) / 2
    y = (box[1] + box[3] - (bbox[3] - bbox[1])) / 2
    draw.text((x, y), text, fill=fill, font=font)


def generate_wallpaper(source_path, output_path, width, height, blur_radius=24, darkness=0.2,
                       art_scale=0.82, corner_radius=42, add_shadow=True,
                       effect='cover', blur_type='gaussian', cover_height=46,
                       cover_offset=0, transition_height=28, background_mode='art',
                       background_color='#101010', background_color2='#303030',
                       accent_color='#FFFFFF', show_lyrics=False, lyrics='',
                       photo_source='album', custom_photo_path=''):
    width, height = max(160, int(width)), max(240, int(height))
    art = Image.open(source_path).convert('RGB')
    bg_mode = str(background_mode or 'art').lower()
    bg1 = _hex(background_color, (16, 16, 16))
    bg2 = _hex(background_color2, (48, 48, 48))
    accent = _hex(accent_color, (255, 255, 255))

    if bg_mode == 'color':
        canvas = Image.new('RGB', (width, height), bg1)
    elif bg_mode == 'gradient':
        strip = Image.new('RGB', (1, 2))
        strip.putpixel((0, 0), bg1); strip.putpixel((0, 1), bg2)
        canvas = strip.resize((width, height), Image.Resampling.BILINEAR)
    elif bg_mode == 'auto':
        c = _dominant_color(art)
        canvas = Image.new('RGB', (width, height), tuple(int(x * 0.45) for x in c))
    else:
        canvas = _fit_cover(art, (width, height))

    # Main background blur/effect.
    if effect in ('blur', 'cover', 'cover_color', 'cd', 'square'):
        if bg_mode == 'art':
            canvas = _apply_blur(canvas, blur_radius, blur_type)
        elif effect == 'blur':
            canvas = _apply_blur(canvas, blur_radius, blur_type)

    if darkness > 0:
        canvas = ImageEnhance.Brightness(canvas).enhance(max(0.0, 1.0 - min(1.0, float(darkness))))

    if effect == 'blur':
        # Pure blurred artwork; no foreground cover.
        pass
    else:
        # Cover size is controlled by BOTH cover height and art scale.
        cover_frac = max(0.10, min(0.90, float(cover_height) / 100.0))
        max_side = int(min(width, height) * cover_frac * max(0.35, min(1.25, float(art_scale))))
        max_side = max(90, min(max_side, int(min(width, height) * 0.88)))
        cover = _fit_contain(art, (max_side, max_side)).convert('RGB')
        x = (width - cover.width) // 2
        y = int((height - cover.height) / 2 + (float(cover_offset) / 100.0) * height * 0.22)
        y = max(20, min(height - cover.height - 20, y))

        if transition_height > 0:
            glow = Image.new('RGBA', (width, max(1, int(height * min(1, transition_height / 100.0)))), (0, 0, 0, 0))
            gd = ImageDraw.Draw(glow)
            gd.rectangle((0, 0, width, glow.height), fill=(*accent, 80))
            glow = glow.filter(ImageFilter.GaussianBlur(max(2, glow.height // 8)))
            canvas.paste(glow, (0, max(0, y - glow.height // 2)), glow)

        if effect == 'cd':
            diameter = min(cover.width, cover.height)
            disc = Image.new('RGBA', (diameter, diameter), (0, 0, 0, 0))
            dd = ImageDraw.Draw(disc)
            dd.ellipse((0, 0, diameter - 1, diameter - 1), fill=(12, 12, 14, 245), outline=(*accent, 180), width=max(1, diameter // 90))
            # album art circle
            art_square = _fit_cover(art, (diameter, diameter)).convert('RGB')
            m = Image.new('L', (diameter, diameter), 0); ImageDraw.Draw(m).ellipse((8, 8, diameter - 9, diameter - 9), fill=255)
            disc.paste(art_square, (0, 0), m)
            dd = ImageDraw.Draw(disc)
            hole = diameter // 12
            dd.ellipse((diameter//2-hole, diameter//2-hole, diameter//2+hole, diameter//2+hole), fill=(20,20,20,255))
            canvas.paste(disc, (x, y), disc)
        else:
            if effect == 'square':
                mask = _rounded_mask(cover.size, 0)
            else:
                mask = _rounded_mask(cover.size, int(corner_radius))
            if add_shadow:
                shadow = Image.new('RGBA', cover.size, (0, 0, 0, 170)).filter(ImageFilter.GaussianBlur(max(4, int(corner_radius * 0.45))))
                canvas.paste(shadow, (x + 8, y + 10), shadow)
            if effect == 'cover_color':
                border = Image.new('RGB', cover.size, accent)
                canvas.paste(border, (x, y))
                inset = 4
                canvas.paste(cover, (x + inset, y + inset), _rounded_mask(cover.size, max(0, int(corner_radius) - inset)))
            else:
                canvas.paste(cover, (x, y), mask)

        if show_lyrics and lyrics:
            d = ImageDraw.Draw(canvas)
            # Lyrics are intentionally optional and limited to the metadata supplied by the player.
            text = str(lyrics).strip().replace('\n', ' • ')
            if len(text) > 180:
                text = text[:177] + '...'
            box = (int(width * 0.08), min(height - 170, y + cover.height + 28), int(width * 0.92), min(height - 35, y + cover.height + 150))
            _draw_text_center(d, box, text, (255, 255, 255), max(14, width // 32))

    canvas.save(output_path, 'JPEG', quality=86, optimize=True)
    return output_path
