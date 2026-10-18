"""Generates VectorVp9Dsp.java from VectorVp9Dsp.template.

Usage: python gen_vp9_dsp.py [root]

root is the repository root the sources are read from and VectorVp9Dsp.java is written to (default: this
repository); the codegen check passes a temporary copy.

The motion compensation (mcgen.py), loop filter (lfgen.py) and inverse transform (txgen.py) kernels
are spliced into the template at the //@MC, //@LF, //@TX and //@TXCONST markers; the transforms are
derived from the scalar 1-D transforms of ScalarVp9Dsp.java. Long statements are wrapped before
method calls at 120 columns.
"""
import os
import re
import sys

sys.dont_write_bytecode = True

import lfgen
import mcgen
import txgen

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..', '..')
INTERNAL = os.path.join('video', 'src', 'main', 'java', 'com', 'github', 'auties00', 'smalt', 'video', 'vp9',
                        'internal')
BREAKS = ('.lanewise(', '.convertShape(', '.blend(', '.compare(', '.intoArray(', '.reinterpretShape(', '.withLane(',
          '.and(', '.lane(')
NL = chr(10)


def wrap(line, limit=120):
    """Wraps a long statement before method calls, continuing with 8 extra spaces."""
    if len(line) <= limit or line.lstrip().startswith(('*', '/**', '//')):
        return [line]
    lead = len(line) - len(line.lstrip())
    pieces = []
    start = 0
    for i in range(len(line)):
        if i > start and any(line.startswith(b, i) for b in BREAKS):
            pieces.append(line[start:i])
            start = i
    pieces.append(line[start:])
    out = []
    current = pieces[0]
    for piece in pieces[1:]:
        if len(current) + len(piece) > limit:
            out.append(current)
            current = ' ' * (lead + 8) + piece
        else:
            current += piece
    out.append(current)
    return out


def wrapped(lines):
    out = []
    for line in lines:
        out += wrap(line)
    return NL.join(out).rstrip(NL) + NL


def constants(scalar_source, used):
    names = {}
    for m in re.finditer(r'static final int ((?:COSPI|SINPI)_\w+) = (-?\d+);', scalar_source):
        names.setdefault(int(m.group(2)), m.group(1))
    lines = []
    for name, value in used:
        species = name[:4]
        lanes = 'Four' if species == 'I128' else 'Eight'
        if value == 8192:
            what = 'the rounding term of a 14-bit shift'
        else:
            source = names[abs(value)].lower()
            what = ('{@code ' + source + '}') if value > 0 else ('minus {@code ' + source + '}')
        lines += ['    /**',
                  f'     * {lanes} ints equal to {{@code {value}}}, {what}.',
                  '     */',
                  f'    private static final IntVector {name} = IntVector.broadcast({species}, {value});',
                  '']
    return NL.join(lines) + NL


def read_lf(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read().replace('\r' + NL, NL)


def depad(text):
    """Removes the column padding of doc comment block tags (CLAUDE.md Documentation, section 5)."""
    tag = re.compile(r'^(\s*\*\s)@(param|return|throws|implNote|implSpec|apiNote|see)\b(.*)$')
    continuation = re.compile(r'^(\s*\*) {2,}(\S.*)$')
    out = []
    in_doc = in_tags = False
    for line in text.split(NL):
        stripped = line.strip()
        if stripped.startswith('/**'):
            in_doc, in_tags = True, False
        if in_doc:
            m = tag.match(line)
            if m:
                in_tags = True
                prefix, name, rest = m.groups()
                rest = re.sub(r'^\s+(\S+)\s+', r' \1 ', rest) if name in ('param', 'throws') \
                    else re.sub(r'^\s+', ' ', rest)
                line = f'{prefix}@{name}{rest}'.rstrip()
            elif in_tags:
                c = continuation.match(line)
                if c and not stripped.startswith('*/'):
                    line = f'{c.group(1)} {c.group(2)}'
        if in_doc and stripped.endswith('*/'):
            in_doc = in_tags = False
        out.append(line)
    return NL.join(out)


def main(root):
    internal = os.path.join(root, INTERNAL)
    scalar_source = read_lf(os.path.join(internal, 'ScalarVp9Dsp.java'))
    transforms, used = txgen.fragment(scalar_source)
    template = read_lf(os.path.join(HERE, 'VectorVp9Dsp.template'))
    template = template.replace('//@TXCONST' + NL, constants(scalar_source, used))
    template = template.replace('//@MC' + NL, wrapped(mcgen.fragment()) + NL)
    template = template.replace('//@LF' + NL, wrapped(lfgen.fragment()) + NL)
    template = template.replace('//@TX' + NL, wrapped(transforms) + NL)
    template = depad(template)
    target = os.path.join(internal, 'VectorVp9Dsp.java')
    crlf = False
    if os.path.exists(target):
        with open(target, 'rb') as f:
            crlf = b'\r\n' in f.read()
    with open(target, 'w', encoding='utf-8', newline='') as f:
        f.write(template.replace(NL, '\r' + NL) if crlf else template)
    print('wrote', os.path.normpath(target), file=sys.stderr)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else ROOT)
