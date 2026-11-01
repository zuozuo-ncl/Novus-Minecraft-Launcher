import io
import os

from PIL import Image, ImageFilter

SRC = r'C:/Users/admin/Desktop/123213/data/NCL_image/NCL_Icon.png'
OUT = r'C:/Users/admin/Desktop/123213/data/MC launcher/bootstrapper/src/app.ico'

SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]
SMALL_MAX = 24

def text_box(im):
    rgb = im.convert('RGB')
    w, h = rgb.size
    px = rgb.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if b > 150 and r < 140 and b - r > 60:
                if x < minx: minx = x
                if y < miny: miny = y
                if x > maxx: maxx = x
                if y > maxy: maxy = y
    if maxx <= minx or maxy <= miny:
        return None
    return (minx, miny, maxx + 1, maxy + 1)

def small_icon(src, box, size):
    bg = src.getpixel((4, 4))
    crop = src.crop(box)
    crop = crop.filter(ImageFilter.UnsharpMask(radius=1.0, percent=60, threshold=0))

    h = max(1, round(size * crop.height / crop.width))
    scaled = crop.resize((size, h), Image.LANCZOS)
    if h <= size:
        canvas = Image.new('RGB', (size, size), bg)
        canvas.paste(scaled, (0, (size - h) // 2))
        return canvas
    top = (h - size) // 2
    return scaled.crop((0, top, size, top + size))

def main():
    src = Image.open(SRC).convert('RGB')
    box = text_box(src)
    print('源图 %s，文字框 %s' % (src.size, box))

    images = []
    for s in SIZES:
        if s <= SMALL_MAX and box:
            images.append(small_icon(src, box, s))
        else:
            images.append(src.resize((s, s), Image.LANCZOS))

    blobs = []
    for img in images:
        buf = io.BytesIO()
        img.save(buf, format='PNG')
        blobs.append(buf.getvalue())

    header = bytearray()
    header += (0).to_bytes(2, 'little')
    header += (1).to_bytes(2, 'little')
    header += len(images).to_bytes(2, 'little')

    offset = 6 + 16 * len(images)
    entries = bytearray()
    for img, blob in zip(images, blobs):
        s = img.width
        entries += bytes([0 if s >= 256 else s, 0 if s >= 256 else s, 0, 0, 1, 0, 32, 0])
        entries += len(blob).to_bytes(4, 'little')
        entries += offset.to_bytes(4, 'little')
        offset += len(blob)

    with open(OUT, 'wb') as f:
        f.write(bytes(header) + bytes(entries) + b''.join(blobs))
    print('已生成 %s（%d 字节，尺寸 %s）' % (OUT, os.path.getsize(OUT), SIZES))

if __name__ == '__main__':
    main()