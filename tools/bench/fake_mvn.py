"""Stands in for "mvn" in test_bench.py: records its command line and writes the class path files bench.py reads.

It appends its arguments, as one JSON list per line, to the file FAKE_MVN_LOG, and writes
<module>/target/bench-classpath.txt for every module of -pl in the working directory, unless FAKE_MVN_STATUS names
another exit status.
"""
import json
import os
import sys


def main(argv):
    with open(os.environ['FAKE_MVN_LOG'], 'a', encoding='utf-8') as handle:
        handle.write(json.dumps([os.getcwd()] + argv) + chr(10))
    status = int(os.environ.get('FAKE_MVN_STATUS', '0'))
    if status:
        return status
    for module in argv[argv.index('-pl') + 1].split(','):
        target = os.path.join(module, 'target')
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, 'bench-classpath.txt'), 'w', encoding='utf-8') as handle:
            handle.write(os.path.join(os.getcwd(), module + '-dependency.jar'))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
