"""Pins the MAC3 step tables entry by entry against QuickTime by bisection: solve3.py out.json

Black box: i += m[code] - (i>>5) (floor 0), row=(i>>4)&127, S=clamp(S-(S>>3)+T[kind][row][code]), out=S>>8.
A probe is a walk over entries already known that lands in a row with an unknown entry and a chosen predictor value,
so that the unknown entry's bisection point falls on an 8-bit output boundary."""
import sys, os, json, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from qt import run

M3 = [-13, 8, 76, 222, 222, 76, 8, -13]
M2 = [-18, 140, 140, -18]
out_path = sys.argv[1]
r = random.Random(12345)
T = {}
U = {}
if os.path.exists(out_path):
    T = {tuple(int(x) for x in k.split(',')): v for k, v in json.load(open(out_path)).items()}
else:
    T[(0, 0, 0)] = 37; T[(0, 0, 7)] = -38; T[(1, 0, 0)] = 64; T[(1, 0, 3)] = -65
IV = {}
if os.path.exists(out_path + '.iv'):
    IV = {tuple(int(x) for x in k.split(',')): v for k, v in json.load(open(out_path + '.iv')).items()}

def clamp(v):
    return -32768 if v < -32768 else 32767 if v > 32767 else v

def size(kind):
    return 4 if kind else 8

def unknown_at(kind, row):
    return any((kind, row, c) not in T for c in range(size(kind)))

def gather(count, pool):
    for _ in range(count):
        i = 0; S = 0; p = 0; codes = []
        w3 = [r.random() ** r.choice((1, 3, 8)) for _ in range(4)]
        w2 = [r.random() ** r.choice((1, 3, 8)) for _ in range(2)]
        drive = r.choice((0, 0, 1, -1))
        climb_at = r.choice((0, 3, 6, 12, 30, 60, 10**6))
        for step in range(r.choice((30, 80, 200))):
            kind = 1 if p % 3 == 1 else 0
            row = (i >> 4) & 127
            P = S - (S >> 3)
            if unknown_at(kind, row):
                bucket = pool.setdefault((kind, row), {})
                key = P & 255
                cur = bucket.get(key)
                if cur is None or len(cur[1]) > len(codes):
                    bucket[key] = (P, list(codes))
                ext = 'lo' if P < 0 else 'hi'
                cur = bucket.get(ext)
                if cur is None or abs(cur[0]) < abs(P):
                    bucket[ext] = (P, list(codes))
            tab = M2 if kind else M3
            n = size(kind)
            opts = [c for c in range(n // 2) if (kind, row, c) in T and (kind, row, n - 1 - c) in T]
            if not opts:
                break
            weights = [(w2 if kind else w3)[c] for c in opts]
            cls = r.choices(opts, weights)[0]
            if step >= climb_at and r.random() < .85:
                cls = max(opts)
            pair = (cls, n - 1 - cls)
            if drive:
                c = max(pair, key=lambda z: T[(kind, row, z)] * drive)
                if r.random() < .1:
                    c = pair[0] if c == pair[1] else pair[1]
            else:
                c = r.choice(pair)
            v = S - (S >> 3) + T[(kind, row, c)]
            if v != clamp(v):
                break
            S = v
            i = max(0, i + tab[c] - (i >> 5))
            codes.append(c)
            p += 1

def encode(codes, frames):
    data = bytearray(frames * 2)
    for p, c in enumerate(codes):
        s = p % 3
        data[p // 3] |= c << (0 if s == 0 else 3 if s == 1 else 5)
    return data

def bisect(pool):
    live = [(kind, row, c) for (kind, row) in pool for c in range(size(kind)) if (kind, row, c) not in T]
    for e in live:
        IV.setdefault(e, [-70000, 70000])
    last = None
    for rnd in range(30):
        todo = [e for e in live if IV[e][0] < IV[e][1]]
        if not todo:
            break
        state = sum(IV[e][1] - IV[e][0] for e in todo)
        if state == last:
            break
        last = state
        trials = []
        for e in todo:
            lo, hi = IV[e]
            mid = (lo + hi + 1) // 2
            best = None
            for P, codes in pool[(e[0], e[1])].values():
                v = P + mid
                dist = min(v & 255, 256 - (v & 255))
                clip = max(0, -32000 - v) + max(0, v - 32000)
                score = clip * 4 + dist * 3 + len(codes) * .001
                if best is None or score < best[0]:
                    best = (score, P, codes)
            trials.append((e, best[1], best[2]))
        frames = max(len(t[2]) for t in trials) // 6 + 1
        data = bytearray()
        for e, P, codes in trials:
            data += encode(codes + [e[2]], frames)
        d = run(data, mode='B', trial=frames)
        if len(d) != len(trials) * frames * 6:
            raise SystemExit('size')
        for k, (e, P, codes) in enumerate(trials):
            y = d[k * frames * 6 + len(codes)] - 128
            lo, hi = IV[e]
            if y >= 127:
                lo = max(lo, 32512 - P)
            elif y <= -128:
                hi = min(hi, -32768 + 255 - P)
            else:
                lo = max(lo, y * 256 - P)
                hi = min(hi, y * 256 + 255 - P)
            if lo > hi:
                raise SystemExit(f'inconsistent {e} {IV[e]} P={P} y={y} codes={codes}')
            IV[e] = [lo, hi]
    return [e for e in live if IV[e][0] == IV[e][1]]

def save():
    json.dump({','.join(map(str, k)): v for k, v in T.items()}, open(out_path, 'w'))
    json.dump({','.join(map(str, k)): v for k, v in IV.items() if k not in T or v[0] != v[1]}, open(out_path + '.iv', 'w'))

stall = 0
while True:
    pool = {}
    gather(30000, pool)
    if not pool:
        break
    done = bisect(pool)
    for e in done:
        T[e] = IV[e][0]
    save()
    missing = 128 * 12 - len(T)
    print('solved', len(done), 'missing', missing, 'rows in pool', len(pool), flush=True)
    if not done:
        stall += 1
        if stall >= 4:
            break
    else:
        stall = 0
print('still open', {e: IV.get(e) for e in ((k, row, c) for k in (0, 1) for row in range(128) for c in range(size(k))) if e not in T})
