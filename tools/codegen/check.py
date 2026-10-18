"""Checks that every generated Java source matches what its generator produces.

Usage: python tools/codegen/check.py

Copies the generators' inputs and outputs into a temporary repository root, runs every generator against that
copy, and compares each output with the committed file, ignoring line endings. Prints a unified diff and exits
with status 1 when a generated file differs (a hand edit that belongs in the generator or its template), 0 when
all match. Needs only Python; the Vorbis setup data (tools/codegen/vorbis) is not checked, since regenerating it
downloads and compiles libvorbis.
"""
import difflib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
AV1 = 'video/src/main/java/com/github/auties00/smalt/video/av1/internal'
VP9 = 'video/src/main/java/com/github/auties00/smalt/video/vp9/internal'
TRANSPOSE = 'core/src/main/java/com/github/auties00/smalt/image/ops/VectorTransformKernel.java'

INPUTS = [AV1 + '/ScalarItxDsp.java', VP9 + '/ScalarVp9Dsp.java']
OUTPUTS = [AV1 + '/VectorItxDsp.java', AV1 + '/VectorLoopFilterDsp.java', AV1 + '/VectorCdefDsp.java',
           AV1 + '/VectorMcDsp.java', VP9 + '/VectorVp9Dsp.java', TRANSPOSE]


def read_lf(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


def main():
    failed = False
    with tempfile.TemporaryDirectory(prefix='smalt-codegen-') as tmp:
        for rel in INPUTS + OUTPUTS:
            os.makedirs(os.path.dirname(os.path.join(tmp, rel)), exist_ok=True)
            shutil.copyfile(os.path.join(ROOT, rel), os.path.join(tmp, rel))
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1')
        commands = [
            [sys.executable, os.path.join(HERE, 'av1', 'regen.py'), tmp],
            [sys.executable, os.path.join(HERE, 'vp9', 'gen_vp9_dsp.py'), tmp],
            [sys.executable, os.path.join(HERE, 'transpose', 'gen_transpose.py'),
             os.path.join(HERE, 'transpose', 'VectorTransformKernel.template'), os.path.join(tmp, TRANSPOSE)],
        ]
        for command in commands:
            subprocess.run(command, check=True, env=env, stdout=subprocess.DEVNULL)
        for rel in OUTPUTS:
            committed = read_lf(os.path.join(ROOT, rel))
            generated = read_lf(os.path.join(tmp, rel))
            if committed != generated:
                failed = True
                sys.stdout.writelines(difflib.unified_diff(committed.splitlines(True), generated.splitlines(True),
                                                           'a/' + rel, 'b/' + rel))
    if failed:
        print('Generated sources differ from their generators: edit the generator or its template, then '
              'regenerate (see tools/codegen/*/README.md).', file=sys.stderr)
        return 1
    print('All %d generated sources match their generators.' % len(OUTPUTS))
    return 0


if __name__ == '__main__':
    sys.exit(main())
