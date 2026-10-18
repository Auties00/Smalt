"""Regenerates the generated AV1 vector kernels and wraps their long lines.

Usage: python regen.py [root]

root is the repository root the scalar sources are read from and the kernels written to (default: this
repository); tools/codegen/check.py passes a temporary copy.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
INTERNAL = os.path.join('video', 'src', 'main', 'java', 'com', 'github', 'auties00', 'smalt', 'video', 'av1',
                        'internal')


def main(root):
    d = os.path.join(root, INTERNAL)

    def run(script, *args):
        subprocess.run([sys.executable, os.path.join(HERE, script)] + list(args), check=True,
                       env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))

    run('gen_vitx.py', os.path.join(d, 'ScalarItxDsp.java'), os.path.join(HERE, 'VectorItxDsp.java.head'),
        os.path.join(d, 'VectorItxDsp.java'))
    run('gen_vlf.py', os.path.join(d, 'VectorLoopFilterDsp.java'), os.path.join(HERE, 'VectorLoopFilterDsp.java.head'))
    run('gen_vcdef.py', os.path.join(d, 'VectorCdefDsp.java'))
    run('gen_vmc_vertical.py', os.path.join(d, 'VectorMcDsp.java'))
    run('fmt_java.py', os.path.join(d, 'VectorItxDsp.java'), os.path.join(d, 'VectorCdefDsp.java'),
        os.path.join(d, 'VectorLoopFilterDsp.java'))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', '..'))
