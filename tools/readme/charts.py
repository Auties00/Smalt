#!/usr/bin/env python3
"""Draws the performance charts of the root README from performance.tsv.

    python tools/readme/charts.py          # rewrite the charts and the README block
    python tools/readme/charts.py --check  # exit 1 when a committed file is stale

For every chart of CHARTS it writes .github/assets/perf-<chart>-light.svg and -dark.svg, and it rewrites the
block between the "perf:begin" and "perf:end" markers of README.md: the <picture> elements that pick a
variant by color scheme, and the same numbers as a text list. Standard library only.

A bar starts at parity (1.0x) on a logarithmic axis and runs left when Smalt is faster than the reference,
right when it is slower. A measured range is drawn as a whisker over a bar that ends at its geometric mean.
"""
import math
import os
import sys
from xml.sax.saxutils import escape

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DATA = os.path.join(HERE, 'performance.tsv')
ASSETS = os.path.join(ROOT, '.github', 'assets')
README = os.path.join(ROOT, 'README.md')
BEGIN, END = '<!-- perf:begin -->', '<!-- perf:end -->'

# chart id -> (heading, one-line caption)
CHARTS = {
    'video-decode': ('Video decoding', 'One thread unless the row says otherwise.'),
    'video-encode': ('Video encoding', 'BD-rate against the reference encoder is in the text list.'),
    'audio': ('Audio', 'Codecs, voice processing and loudness measurement.'),
    'images': ('Still images', 'Decoding and encoding of whole images.'),
    'processing': ('Processing and demuxing', 'Color transforms, picture operations and container reading.'),
}

THEMES = {
    'light': dict(ink='#1f2328', muted='#59636e', grid='#d8dee4', axis='#8c959f', fast='#2a78d6', slow='#eb6834'),
    'dark': dict(ink='#f0f6fc', muted='#9198a1', grid='#3d444d', axis='#7d8590', fast='#3987e5', slow='#d95926'),
}

WIDTH = 960
LABEL_X = 8          # left edge of the row labels
PLOT_L, PLOT_R = 520, 860
TOP = 64             # first row starts here
ROW = 38
BAR = 14
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif"
TICKS = [0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8, 16]
DECADES = [0.001, 0.01, 0.1, 1, 10, 100]


def read_rows():
    rows = []
    with open(DATA, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            p = line.split('\t')
            if len(p) < 6:
                raise SystemExit(f'{DATA}:{n}: expected at least 6 tab-separated columns')
            chart, workload, ref, low, high, shown = p[:6]
            note = p[6] if len(p) > 6 else ''
            if chart not in CHARTS:
                raise SystemExit(f'{DATA}:{n}: unknown chart "{chart}"')
            low, high = float(low), float(high)
            if not 0 < low <= high:
                raise SystemExit(f'{DATA}:{n}: need 0 < low <= high')
            rows.append(dict(chart=chart, workload=workload, ref=ref, low=low, high=high, shown=shown, note=note))
    return rows


def fmt(v):
    return f'{v:g}x'


def domain(rows):
    lo = min(min(r['low'] for r in rows), 0.5)
    hi = max(max(r['high'] for r in rows), 2)
    ticks = TICKS if hi / lo < 120 else DECADES
    dmin = max(t for t in ticks if t <= lo)
    dmax = min(t for t in ticks if t >= hi)
    return dmin, dmax, [t for t in ticks if dmin <= t <= dmax]


def bar_path(x0, x1, y, h, r=4):
    """A bar from the parity line x0 to its data end x1, rounded at the data end only."""
    if abs(x1 - x0) < 2 * r:
        x = min(x0, x1)
        return f'M{x:.1f},{y}h{max(abs(x1 - x0), 2):.1f}v{h}h{-max(abs(x1 - x0), 2):.1f}z'
    s = 1 if x1 > x0 else -1
    sweep = 1 if s > 0 else 0
    return (f'M{x0:.1f},{y}H{x1 - s * r:.1f}a{r},{r} 0 0 {sweep} {s * r},{r}v{h - 2 * r}'
            f'a{r},{r} 0 0 {sweep} {-s * r},{r}H{x0:.1f}z')


def draw(chart, rows, theme):
    c = THEMES[theme]
    dmin, dmax, ticks = domain(rows)
    span = math.log(dmax / dmin)

    def x(v):
        return PLOT_L + (PLOT_R - PLOT_L) * math.log(v / dmin) / span

    height = TOP + ROW * len(rows) + 14
    heading = CHARTS[chart][0]
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {WIDTH} {height}" width="{WIDTH}" height="{height}" '
         f'font-family="{FONT}" role="img" aria-label="{escape(heading)}: time relative to the native reference">',
         f'<title>{escape(heading)}: Smalt\'s time relative to the native reference (lower is better)</title>']
    # legend
    o.append(f'<rect x="{LABEL_X}" y="10" width="12" height="12" rx="3" fill="{c["fast"]}"/>')
    o.append(f'<text x="{LABEL_X + 18}" y="20.5" font-size="12" fill="{c["muted"]}">faster than the reference</text>')
    o.append(f'<rect x="{LABEL_X + 180}" y="10" width="12" height="12" rx="3" fill="{c["slow"]}"/>')
    o.append(f'<text x="{LABEL_X + 198}" y="20.5" font-size="12" fill="{c["muted"]}">slower than the reference</text>')
    wx = LABEL_X + 362
    o.append(f'<path d="M{wx},12v8M{wx},16h22M{wx + 22},12v8" stroke="{c["ink"]}" stroke-width="1.5" fill="none"/>')
    o.append(f'<text x="{wx + 30}" y="20.5" font-size="12" fill="{c["muted"]}">measured range</text>')
    # axis
    bottom = TOP + ROW * len(rows)
    for t in ticks:
        tx = x(t)
        parity = t == 1
        o.append(f'<line x1="{tx:.1f}" y1="{TOP - 8}" x2="{tx:.1f}" y2="{bottom}" '
                 f'stroke="{c["axis"] if parity else c["grid"]}" stroke-width="{1.5 if parity else 1}"/>')
        o.append(f'<text x="{tx:.1f}" y="{TOP - 14}" font-size="11" text-anchor="middle" '
                 f'fill="{c["ink"] if parity else c["muted"]}"'
                 f'{" font-weight=\"600\"" if parity else ""}>{fmt(t)}</text>')
    o.append(f'<text x="{x(1):.1f}" y="{TOP - 28}" font-size="11" text-anchor="middle" fill="{c["muted"]}">parity</text>')
    # rows
    x1 = x(1)
    for i, r in enumerate(rows):
        y = TOP + ROW * i
        mid = y + ROW / 2
        o.append(f'<text x="{LABEL_X}" y="{mid - 2:.1f}" font-size="13" fill="{c["ink"]}">{escape(r["workload"])}</text>')
        o.append(f'<text x="{LABEL_X}" y="{mid + 12:.1f}" font-size="11" fill="{c["muted"]}">vs {escape(r["ref"])}</text>')
        if i:
            o.append(f'<line x1="{LABEL_X}" y1="{y}" x2="{PLOT_R + 92}" y2="{y}" stroke="{c["grid"]}" stroke-width="1" '
                     f'stroke-dasharray="1 5"/>')
        gm = math.sqrt(r['low'] * r['high'])
        xe = x(gm)
        color = c['fast'] if gm < 1 else c['slow']
        by = mid - BAR / 2
        if abs(xe - x1) < 1.5:
            o.append(f'<circle cx="{x1:.1f}" cy="{mid:.1f}" r="4.5" fill="{c["axis"]}"/>')
        else:
            o.append(f'<path d="{bar_path(x1, xe, by, BAR)}" fill="{color}"/>')
        lo_x, hi_x = x(r['low']), x(r['high'])
        if r['high'] > r['low']:
            o.append(f'<path d="M{lo_x:.1f},{mid - 5}v10M{lo_x:.1f},{mid}H{hi_x:.1f}M{hi_x:.1f},{mid - 5}v10" '
                     f'stroke="{c["ink"]}" stroke-width="1.5" fill="none"/>')
        if gm < 1:
            o.append(f'<text x="{min(lo_x, xe) - 8:.1f}" y="{mid + 4.5:.1f}" font-size="12" font-weight="600" '
                     f'text-anchor="end" fill="{c["ink"]}">{escape(r["shown"])}</text>')
        else:
            o.append(f'<text x="{max(hi_x, xe) + 8:.1f}" y="{mid + 4.5:.1f}" font-size="12" font-weight="600" '
                     f'fill="{c["ink"]}">{escape(r["shown"])}</text>')
    o.append('</svg>')
    return '\n'.join(o) + '\n'


def readme_block(rows):
    o = [BEGIN, '<!-- Generated by tools/readme/charts.py from tools/readme/performance.tsv; do not edit by hand. -->', '']
    for chart, (heading, caption) in CHARTS.items():
        mine = [r for r in rows if r['chart'] == chart]
        if not mine:
            continue
        base = f'.github/assets/perf-{chart}'
        o += [f'### {heading}', '', caption, '',
              '<picture>',
              f'  <source media="(prefers-color-scheme: dark)" srcset="{base}-dark.svg">',
              f'  <img src="{base}-light.svg" width="100%" alt="{heading}: bar chart of Smalt\'s time relative to the '
              f'native reference; the numbers follow as text.">',
              '</picture>', '',
              '<details>',
              f'<summary>{heading}: the numbers as text</summary>', '']
        for r in mine:
            note = f' ({r["note"]})' if r['note'] else ''
            o.append(f'- {r["workload"]}, against {r["ref"]}: **{r["shown"]}**{note}')
        o += ['', '</details>', '']
    o.append(END)
    return '\n'.join(o)


def main():
    check = '--check' in sys.argv
    rows = read_rows()
    files = {}
    for chart in CHARTS:
        mine = [r for r in rows if r['chart'] == chart]
        if not mine:
            continue
        for theme in THEMES:
            files[os.path.join(ASSETS, f'perf-{chart}-{theme}.svg')] = draw(chart, mine, theme)
    with open(README, encoding='utf-8') as f:
        readme = f.read()
    a, b = readme.find(BEGIN), readme.find(END)
    if a < 0 or b < a:
        raise SystemExit(f'{README}: no "{BEGIN}" ... "{END}" block')
    files[README] = readme[:a] + readme_block(rows) + readme[b + len(END):]
    stale = []
    for path, text in files.items():
        old = None
        if os.path.exists(path):
            with open(path, encoding='utf-8') as f:
                old = f.read()
        if old != text:
            stale.append(os.path.relpath(path, ROOT))
            if not check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, 'w', encoding='utf-8', newline='\n') as f:
                    f.write(text)
    if check and stale:
        print('stale: ' + ', '.join(stale))
        raise SystemExit(1)
    print(('up to date' if not stale else ('stale: ' if check else 'wrote: ') + ', '.join(stale)))


if __name__ == '__main__':
    main()
