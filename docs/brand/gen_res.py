"""Generates FJAir Android vector resources from the same shapes as docs/brand/*.svg."""
import os, sys

RES = sys.argv[1]
BG, ORANGE, INK, WHITE = "#FF111111", "#FFFF5A36", "#FF1C0D08", "#FFF2F2F0"
A = 'xmlns:android="http://schemas.android.com/apk/res/android"'

def rect(x, y, w, h): return f"M{x},{y} h{w} v{h} h{-w} Z"
def circle(cx, cy, r): return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z"
def fill(d, c, alpha=None):
    a = f' android:fillAlpha="{alpha}"' if alpha else ""
    return f'<path android:fillColor="{c}"{a} android:pathData="{d}"/>'
def stroke(d, c, w):
    return (f'<path android:strokeColor="{c}" android:strokeWidth="{w}" android:strokeLineCap="round" '
            f'android:strokeLineJoin="round" android:pathData="{d}"/>')

TICKET = "\n".join([
    fill("M29,34 H79 A7,7 0 0 1 86,41 V67 A7,7 0 0 1 79,74 H29 A7,7 0 0 1 22,67 V41 A7,7 0 0 1 29,34 Z", ORANGE),
    fill(circle(70, 34, 4.5) + " " + circle(70, 74, 4.5), BG),
    fill(" ".join(rect(69.4, y, 1.2, 2.5) for y in (41, 46, 51, 56, 61, 66)), INK, "0.35"),
    fill(" ".join(rect(x, 42, w, 24) for x, w in ((74, 1.8), (77.2, 1), (79.6, 1.8), (82.4, 0.9))), INK),
    stroke("M32,44 V64 M32,44 H43 M32,53.5 H40.5 M60,44 V58.5 A5.5,5.5 0 0 1 49,58.5", INK, 4.2),
])
WORD = "\n".join([
    stroke("M0,0 V40 M0,0 H22 M0,19 H17 M48,0 V28 A10,10 0 0 1 28,28", WHITE, 7),
    stroke("M62,40 L76,0 L90,40 M66.9,26 H85.1 M104,16 V40 M116,16 V40 M116,27 C116,20 121,16 129,16", ORANGE, 7),
    fill(circle(104, 3, 4.4), ORANGE),
])

def ticket_group(tx=0, ty=0, scale=1):
    return (f'<group android:pivotX="54" android:pivotY="54" android:rotation="-8" android:scaleX="{scale}" '
            f'android:scaleY="{scale}" android:translateX="{tx}" android:translateY="{ty}">\n{TICKET}\n</group>')

def vector(w, h, vw, vh, body):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- generated from docs/brand/*.svg -->\n'
            f'<vector {A} android:width="{w}dp" android:height="{h}dp" '
            f'android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n{body}\n</vector>\n')

def write(rel, text):
    p = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(text)

# adaptive icon (API 26+): flat background colour + ticket foreground
write("drawable/ic_launcher_foreground.xml", vector(108, 108, 108, 108, ticket_group()))
write("values/colors.xml", f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="fjair_bg">{BG}</color>\n</resources>\n')
write("mipmap-anydpi-v26/ic_launcher.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon {A}>\n'
      '    <background android:drawable="@color/fjair_bg"/>\n'
      '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n</adaptive-icon>\n')
# legacy icon (API 21-25): rounded square + ticket
write("mipmap/ic_launcher.xml", vector(48, 48, 108, 108,
      fill("M30,6 H78 A24,24 0 0 1 102,30 V78 A24,24 0 0 1 78,102 H30 A24,24 0 0 1 6,78 V30 A24,24 0 0 1 30,6 Z", BG)
      + "\n" + ticket_group()))
# Android TV launcher banner, 320x180
write("drawable/banner.xml", vector(320, 180, 320, 180,
      fill(rect(0, 0, 320, 180), BG) + "\n" + ticket_group(42.5, 36, 1.3) + "\n"
      + f'<group android:pivotX="0" android:pivotY="20" android:scaleX="0.78" android:scaleY="0.78" '
        f'android:translateX="161.5" android:translateY="70">\n{WORD}\n</group>'))
# wordmark for the home screen (viewport shifted so the round caps are not clipped)
write("drawable/fjair_wordmark.xml", vector(146, 60, 146, 60,
      f'<group android:translateX="6" android:translateY="10">\n{WORD}\n</group>'))
print("ok")
