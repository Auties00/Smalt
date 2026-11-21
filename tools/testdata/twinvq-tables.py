#!/usr/bin/env python3
"""Writes twinvq-tables.txt, the code books of the TwinVQ decoder, from the reference software and checks them.

The tables come from tables/tf_vq_tbls of the audio reference software of ISO/IEC 14496-5 (audio.zip, see
tools/notices/licenses/iso14496-5-twinvq-notice.txt). With the text of ISO/IEC 14496-3:2009 (pdftotext -layout), which is
copyrighted and never committed, the script also checks that every value of each table appears in that table of Annex
4.A.4 as often as the reference software lists it; the layout of the text interleaves its columns, so the order of the
values is not checked (the ISO/IEC 14496-26 conformance streams check that).

    python -I tools/testdata/twinvq-tables.py <tf_vq_tbls directory> <output file> [<ISO-IEC-14496-3-2009.txt>]
"""
import collections
import os
import re
import sys

# The file of each table, its name, its number of values, and its lines in the text of the standard.
TABLES = [('20b19s48bs', 'LSP_CORE', 20 * 82, 53143, 53429),
          ('20b19s48sc', 'LSP_SCALABLE', 20 * 82, 53429, 53720),
          ('cdmdct_0', 'SHAPE_CORE_LONG_0', 32 * 20, 53720, 53862),
          ('cdmdct_1', 'SHAPE_CORE_LONG_1', 32 * 20, 53862, 54001),
          ('cdmdct_2', 'SHAPE_CORE_SHORT_0', 32 * 17, 54001, 54118),
          ('cdmdct_3', 'SHAPE_CORE_SHORT_1', 32 * 17, 54118, 54257),
          ('scmdct_0', 'SHAPE_SCALABLE_LONG_0', 32 * 28, 54257, 54460),
          ('scmdct_1', 'SHAPE_SCALABLE_LONG_1', 32 * 28, 54460, 54654),
          ('scmdct_2', 'SHAPE_SCALABLE_SHORT_0', 32 * 24, 54654, 54772),
          ('scmdct_3', 'SHAPE_SCALABLE_SHORT_1', 32 * 24, 54772, 54902),
          ('fcdl', 'ENVELOPE', 64 * 6, 54902, 54996),
          ('pcdl', 'PERIODIC', 128 * 10, 54996, 55233)]

COMMENT = re.compile(r'/\*.*?\*/', re.S)
PAGE = re.compile(r'LICENSED.*|FOR INTERNAL.*|SUPPLIED.*|ISO/IEC.*|IISSOO.*')
NUMBER = re.compile(r'[-+]?[0-9]*[.]?[0-9]+')


def main():
    directory, output = sys.argv[1], sys.argv[2]
    spec = None
    if len(sys.argv) > 3:
        with open(sys.argv[3], encoding='latin-1') as file:
            spec = file.read().split('\n')
    with open(output, 'w', newline='\n') as out:
        for name, table, count, first, last in TABLES:
            with open(os.path.join(directory, name), encoding='latin-1') as file:
                values = COMMENT.sub('', file.read()).split()
            if len(values) != count:
                sys.exit(f'{name}: {len(values)} values, not {count}')
            if spec is not None:
                printed = []
                for line in spec[first:last]:
                    printed += [float(token) for token in PAGE.sub('', line).split() if NUMBER.fullmatch(token)]
                missing = collections.Counter(float(value) for value in values) - collections.Counter(printed)
                if missing:
                    sys.exit(f'{name}: not in the standard: {list(missing.items())[:8]}')
            out.write(f'# {table} {count}\n')
            for i in range(0, count, 16):
                out.write(' '.join(values[i:i + 16]) + '\n')


if __name__ == '__main__':
    main()
