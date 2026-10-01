#!/usr/bin/env python3
"""Regenerate the README header art (hero-dark.svg, hero-light.svg). Stdlib only."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))

# GitHub-like neutrals; the accent is the app's clay (ui/theme/Color.kt), bright on dark, deep on light.
THEMES = {
    "dark": dict(bg="#0d1117", panel="#161b22", line="#30363d", text="#e6edf3", dim="#8b949e",
                 accent="#e08a61"),
    "light": dict(bg="#ffffff", panel="#f6f8fa", line="#d0d7de", text="#1f2328", dim="#656d76",
                  accent="#9e4a2a"),
}
FONT = "ui-sans-serif, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"
MONO = "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace"


def box(x, y, w, h, label, sub, c, stroke):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{c["panel"]}" stroke="{stroke}" stroke-width="1.5"/>'
            f'<text x="{x + w / 2}" y="{y + 30}" text-anchor="middle" font-family="{FONT}" font-size="17" font-weight="600" fill="{c["text"]}">{label}</text>'
            f'<text x="{x + w / 2}" y="{y + 52}" text-anchor="middle" font-family="{MONO}" font-size="12" fill="{c["dim"]}">{sub}</text>')


def arrow(x1, y1, x2, y2, color):
    return (f'<line x1="{x1}" y1="{y1}" x2="{x2 - 7}" y2="{y2}" stroke="{color}" stroke-width="1.8"/>'
            f'<path d="M{x2 - 8},{y2 - 5} L{x2},{y2} L{x2 - 8},{y2 + 5} Z" fill="{color}"/>')


def hero(c):
    W, H = 1200, 330
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" '
         'aria-label="Kouros: a saved ComfyUI workflow is compiled, shown as a form, queued on your server, and the result lands in the gallery">',
         f'<rect width="{W}" height="{H}" rx="16" fill="{c["bg"]}" stroke="{c["line"]}"/>',
         f'<text x="60" y="78" font-family="{MONO}" font-size="38" font-weight="700" fill="{c["text"]}">kouros</text>',
         f'<text x="60" y="112" font-family="{FONT}" font-size="18" fill="{c["dim"]}">'
         'Run the ComfyUI workflows already on your server from an Android phone, and follow them live.</text>']
    y, w, h, gap = 170, 180, 70, 45
    nodes = [
        ("saved workflow", "/userdata", c["line"]),
        ("compile", "UI graph → prompt", c["line"]),
        ("form", "/object_info", c["accent"]),
        ("your ComfyUI", "/prompt · /ws", c["line"]),
        ("gallery", "/view · notification", c["line"]),
    ]
    for i, (label, sub, stroke) in enumerate(nodes):
        x = 60 + i * (w + gap)
        s.append(box(x, y, w, h, label, sub, c, stroke))
        if i:
            s.append(arrow(x - gap, y + h / 2, x, y + h / 2, c["dim"]))
    s.append(f'<text x="60" y="284" font-family="{FONT}" font-size="13" fill="{c["dim"]}">'
             'Nodes, models and custom packs are read from your server, not built into the app · '
             'optional Kouros Bridge extension for deletes, memory and model downloads</text>')
    s.append("</svg>")
    return "".join(s)


for theme, colors in THEMES.items():
    with open(os.path.join(HERE, f"hero-{theme}.svg"), "w", encoding="utf-8") as f:
        f.write(hero(colors))
print("ok")
