from PIL import Image, ImageDraw, ImageFilter, ImageEnhance, ImageChops
import math


def _hex(value, fallback=(255, 255, 255)):
    try:
        s = str(value or '').strip().lstrip('#')
        if len(s) == 6:
            return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))
        if len(s) == 8:
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
    r = max(1, int(radius))
    if blur_type == 'solid':
        return im.filter(ImageFilter.BoxBlur(r))
    if blur_type == 'motion':
        base = im.filter(ImageFilter.GaussianBlur(r * 0.35))
        samples = [im.transform(im.size, Image.AFFINE, (1, 0, -r * i / 3.0, 0, 1, 0), resample=Image.Resampling.BILINEAR) for i in (-1, 0, 1)]
        motion = Image.blend(Image.blend(samples[0], samples[1], 0.5), samples[2], 0.5)
        return Image.blend(base, motion, 0.55)
    if blur_type == 'glass':
        return im.filter(ImageFilter.GaussianBlur(r * 0.7)).filter(ImageFilter.SMOOTH_MORE)
    if blur_type == 'radial':
        blurred = im.filter(ImageFilter.GaussianBlur(r * 1.6))
        mask = Image.new('L', im.size, 0)
        md = ImageDraw.Draw(mask)
        cx, cy = im.width / 2, im.height / 2
        maxd = math.hypot(cx, cy)
        for y in range(0, im.height, max(1, im.height // 160)):
            for x in range(0, im.width, max(1, im.width // 160)):
                d = math.hypot(x - cx, y - cy) / maxd
                md.point((x, y), fill=int(max(0, min(255, d * 255))))
        mask = mask.resize(im.size, Image.Resampling.BILINEAR).filter(ImageFilter.GaussianBlur(r))
        return Image.composite(blurred, im, mask)
    return im.filter(ImageFilter.GaussianBlur(r))


def _dominant_color(im):
    return im.resize((1, 1), Image.Resampling.BILINEAR).convert('RGB').getpixel((0, 0))


def _kaleidoscope(im):
    size = min(im.size)
    square = _fit_cover(im, (size, size))
    half = size // 2
    quad = square.crop((0, 0, half, half))
    canvas = Image.new('RGB', (size, size))
    canvas.paste(quad, (0, 0))
    canvas.paste(quad.transpose(Image.Transpose.FLIP_LEFT_RIGHT), (half, 0))
    canvas.paste(quad.transpose(Image.Transpose.FLIP_TOP_BOTTOM), (0, half))
    canvas.paste(quad.transpose(Image.Transpose.ROTATE_180), (half, half))
    return canvas.resize(im.size, Image.Resampling.LANCZOS)


def _add_vignette(im, strength=0.42):
    w, h = im.size
    mask = Image.new('L', (w, h), 0)
    px = mask.load()
    cx, cy = w / 2.0, h / 2.0
    maxd = math.hypot(cx, cy)
    for y in range(h):
        for x in range(w):
            d = min(1.0, math.hypot(x - cx, y - cy) / maxd)
            px[x, y] = int(max(0, min(255, d * d * 255 * strength)))
    black = Image.new('RGB', im.size, (0, 0, 0))
    return Image.composite(black, im, mask)


def generate_wallpaper(source_path, output_path, width, height, blur_radius=24, darkness=0.2, art_scale=0.82, corner_radius=42, add_shadow=True, effect='cover', blur_type='gaussian', cover_height=46, cover_offset=0, transition_height=28, background_mode='art', background_color='#101010', background_color2='#303030', accent_color='#FFFFFF', show_lyrics=False, lyrics='', photo_source='album', custom_photo_path=''):
    width, height = max(160, int(width)), max(240, int(height))
    art = Image.open(source_path).convert('RGB')
    bg_mode = str(background_mode or 'art').lower()
    effect = str(effect or 'cover').lower()
    bg1, bg2, accent = _hex(background_color, (16, 16, 16)), _hex(background_color2, (48, 48, 48)), _hex(accent_color, (255, 255, 255))

    if bg_mode == 'color':
        canvas = Image.new('RGB', (width, height), bg1)
    elif bg_mode == 'gradient':
        strip = Image.new('RGB', (1, 2)); strip.putpixel((0, 0), bg1); strip.putpixel((0, 1), bg2)
        canvas = strip.resize((width, height), Image.Resampling.BILINEAR)
    elif bg_mode == 'auto':
        c = _dominant_color(art)
        canvas = Image.new('RGB', (width, height), tuple(int(x * 0.45) for x in c))
    else:
        canvas = _fit_cover(art, (width, height))

    if effect in ('blur', 'cover', 'cover_color', 'cd', 'square', 'kaleidoscope', 'pulse', 'float'):
        canvas = _apply_blur(canvas, blur_radius, blur_type)
    if effect == 'kaleidoscope':
        canvas = _kaleidoscope(canvas)
    if effect == 'pulse':
        # A neutral center glow gives the live service room to animate the pulse.
        glow = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        radius = int(min(width, height) * 0.48)
        gd.ellipse((width//2-radius, height//2-radius, width//2+radius, height//2+radius), fill=(*accent, 35))
        glow = glow.filter(ImageFilter.GaussianBlur(max(12, radius // 8)))
        canvas = Image.alpha_composite(canvas.convert('RGBA'), glow).convert('RGB')
    if effect == 'float':
        canvas = _add_vignette(canvas, 0.30)
    if darkness > 0:
        canvas = ImageEnhance.Brightness(canvas).enhance(max(0.0, 1.0 - min(1.0, float(darkness))))

    if effect != 'blur':
        cover_frac = max(0.10, min(0.90, float(cover_height) / 100.0))
        max_side = int(min(width, height) * cover_frac * max(0.35, min(1.25, float(art_scale))))
        max_side = max(90, min(max_side, int(min(width, height) * 0.88)))
        cover = _fit_contain(art, (max_side, max_side)).convert('RGB')
        x = (width - cover.width) // 2
        y = int((height - cover.height) / 2 + (float(cover_offset) / 100.0) * height * 0.22)
        y = max(20, min(height - cover.height - 20, y))
        if effect == 'float':
            y -= int(math.sin(0.8) * height * 0.015)
        if transition_height > 0:
            gh = max(1, int(height * min(1, transition_height / 100.0)))
            glow = Image.new('RGBA', (width, gh), (0, 0, 0, 0))
            gd = ImageDraw.Draw(glow); gd.rectangle((0, 0, width, gh), fill=(*accent, 80))
            glow = glow.filter(ImageFilter.GaussianBlur(max(2, gh // 8)))
            canvas.paste(glow, (0, max(0, y - gh // 2)), glow)
        if effect == 'cd':
            diameter = min(cover.width, cover.height)
            disc = Image.new('RGBA', (diameter, diameter), (0, 0, 0, 0))
            dd = ImageDraw.Draw(disc)
            dd.ellipse((0, 0, diameter - 1, diameter - 1), fill=(12, 12, 14, 245), outline=(*accent, 180), width=max(1, diameter // 90))
            art_square = _fit_cover(art, (diameter, diameter)).convert('RGB')
            m = Image.new('L', (diameter, diameter), 0)
            ImageDraw.Draw(m).ellipse((8, 8, diameter - 9, diameter - 9), fill=255)
            disc.paste(art_square, (0, 0), m)
            hole = diameter // 12
            dd = ImageDraw.Draw(disc); dd.ellipse((diameter//2-hole, diameter//2-hole, diameter//2+hole, diameter//2+hole), fill=(20, 20, 20, 255))
            canvas.paste(disc, (x, y), disc)
        else:
            mask = _rounded_mask(cover.size, 0 if effect == 'square' else int(corner_radius))
            if add_shadow:
                shadow = Image.new('RGBA', cover.size, (0, 0, 0, 170)).filter(ImageFilter.GaussianBlur(max(4, int(corner_radius * 0.45))))
                canvas.paste(shadow, (x + 8, y + 10), shadow)
            if effect == 'cover_color':
                border = Image.new('RGB', cover.size, accent); canvas.paste(border, (x, y))
                inset = 4; canvas.paste(cover, (x + inset, y + inset), _rounded_mask(cover.size, max(0, int(corner_radius) - inset)))
            else:
                canvas.paste(cover, (x, y), mask)

    # Static lyric rendering remains available for exported/downloaded wallpapers.
    if show_lyrics and lyrics:
        try:
            from PIL import ImageFont
            font = ImageFont.load_default(size=max(14, min(34, width // 30)))
        except Exception:
            font = None
        raw = [line.strip() for line in str(lyrics).replace('\r', '').split('\n') if line.strip()]
        raw = raw[:4]
        if raw:
            d = ImageDraw.Draw(canvas)
            line_h = max(26, int(height * 0.035))
            start_y = int(height * 0.76)
            for i, line in enumerate(raw):
                line = line if len(line) <= 80 else line[:77] + '…'
                box = d.textbbox((0, 0), line, font=font)
                x = (width - (box[2] - box[0])) / 2
                d.text((x + 2, start_y + i * line_h + 2), line, fill=(0, 0, 0), font=font)
                d.text((x, start_y + i * line_h), line, fill=accent, font=font)

    canvas.save(output_path, 'JPEG', quality=90, optimize=True)
    return output_path
