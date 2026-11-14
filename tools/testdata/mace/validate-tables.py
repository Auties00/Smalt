"""Random streams over known entries, compared with QuickTime sample by sample: val3.py T.json trials length seed maxrow"""
import sys, os, json, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from qt import run
M3 = [-13, 8, 76, 222, 222, 76, 8, -13]
M2 = [-18, 140, 140, -18]
T = {tuple(int(x) for x in k.split(',')): v for k, v in json.load(open(sys.argv[1])).items()}
trials, length, seed, maxrow = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
r = random.Random(seed)
frames = length // 6
streams = []
for t in range(trials):
    i = 0; S = 0; codes = []; pred = []
    w3 = [r.random() ** r.choice((1, 3, 8)) for _ in range(4)]
    w2 = [r.random() ** r.choice((1, 3, 8)) for _ in range(2)]
    for p in range(length):
        kind = 1 if p % 3 == 1 else 0
        row = (i >> 4) & 127
        n = 4 if kind else 8
        tab = M2 if kind else M3
        opts = [c for c in range(n // 2) if (kind, row, c) in T and (kind, row, n - 1 - c) in T and row <= maxrow]
        nxt = [c for c in opts if ((max(0, i + tab[c] - (i >> 5)) >> 4) & 127) <= maxrow]
        if not nxt:
            nxt = [0] if (kind, row, 0) in T else opts
        cls = r.choices(nxt, [(w2 if kind else w3)[c] + 1e-9 for c in nxt])[0]
        c = r.choice((cls, n - 1 - cls))
        P = S - (S >> 3)
        S = max(-32767, min(32767, P + T[(kind, row, c)]))
        i = max(0, i + tab[c] - (i >> 5))
        codes.append(c); pred.append(S >> 8)
    streams.append((codes, pred))
data = bytearray()
for codes, pred in streams:
    b = bytearray(frames * 2)
    for p, c in enumerate(codes):
        s = p % 3
        b[p // 3] |= c << (0 if s == 0 else 3 if s == 1 else 5)
    data += b
d = run(data, mode='B', trial=frames)
bad = 0; tot = 0; firsts = []
for t, (codes, pred) in enumerate(streams):
    off = t * length
    for p, v in enumerate(pred):
        tot += 1
        if d[off + p] - 128 != v:
            bad += 1
            firsts.append((t, p, v, d[off + p] - 128))
            break
print('trials', trials, 'failed trials', len(firsts), firsts[:10])
if firsts:
    t, p, _, _ = firsts[0]
    print(streams[t][0][:p + 1])
