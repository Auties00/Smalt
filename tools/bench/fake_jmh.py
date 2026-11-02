"""Stands in for "java ... org.openjdk.jmh.Main" in test_bench.py: writes a JMH JSON result file.

It reads the command line bench.py builds (-cp, -p, -rff, -prof) and is driven by environment variables:
- FAKE_JMH_LOG: a file each invocation appends a tab-separated line to (the side, the parameters, the profiler);
  the side is the name of the tree directory the class path starts in;
- FAKE_JMH_FACTOR: the head's time over the base's;
- FAKE_JMH_MODE: avgt or thrpt;
- FAKE_JMH_ALLOC: JSON mapping "side:value", a parameter value, "side:*" or "*" (the first that matches) to the
  bytes per operation of each successive attempt of that side;
- FAKE_JMH_FAIL: a parameter value whose invocations exit with status 1 without results, on the side
  FAKE_JMH_FAIL_SIDE names, or on both;
- FAKE_JMH_HANG: a parameter value whose invocations never finish; each starts a child process that writes the file
  FAKE_JMH_MARK after 3 s unless it is killed first.
"""
import json
import os
import subprocess
import sys
import time

TAB = chr(9)
NEWLINE = chr(10)
# JMH 1.37 names the allocation metric with a leading middle dot.
ALLOC_METRIC = chr(0xb7) + 'gc.alloc.rate.norm'


def main(argv):
    if argv == ['-version']:
        print('fake JMH 1.0', file=sys.stderr)
        return 0
    cp = argv[argv.index('-cp') + 1]
    result_file = argv[argv.index('-rff') + 1]
    params = {}
    for index, value in enumerate(argv):
        if value == '-p':
            name, _, setting = argv[index + 1].partition('=')
            params[name] = setting
    profile = 'gc' if '-prof' in argv and argv[argv.index('-prof') + 1] == 'gc' else ''
    first = cp.split(os.pathsep)[0]
    side = os.path.basename(os.path.dirname(os.path.dirname(os.path.dirname(first))))
    label = ' '.join('%s=%s' % pair for pair in sorted(params.items()))
    log = os.environ.get('FAKE_JMH_LOG')
    count = 0
    if log:
        if os.path.exists(log):
            with open(log, encoding='utf-8') as handle:
                count = sum(1 for _ in handle)
        with open(log, 'a', encoding='utf-8') as handle:
            handle.write(TAB.join([side, label, profile]) + NEWLINE)
    if os.environ.get('FAKE_JMH_FAIL') in params.values() and os.environ.get('FAKE_JMH_FAIL_SIDE', side) == side:
        return 1
    if os.environ.get('FAKE_JMH_HANG') in params.values():
        # Like JMH's forked JVM: a child that outlives a host killed alone, and leaves a mark when it does.
        child = 'import sys, time; time.sleep(3); open(sys.argv[1], "w").write("alive")'
        subprocess.Popen([sys.executable, '-I', '-c', child, os.environ['FAKE_JMH_MARK']])
        time.sleep(600)
        return 0
    factor = float(os.environ.get('FAKE_JMH_FACTOR', '1')) if side == 'head' else 1.0
    # Deterministic noise of +-2% that changes from one invocation to the next.
    noise = 1 + 0.01 * ((count * 7) % 5 - 2)
    millis = 10.0 * factor * noise
    mode = os.environ.get('FAKE_JMH_MODE', 'avgt')
    if mode == 'thrpt':
        primary = {'score': 1000.0 / millis, 'scoreUnit': 'ops/s', 'rawData': [[1000.0 / millis]]}
    else:
        primary = {'score': millis, 'scoreUnit': 'ms/op', 'rawData': [[millis]]}
    entry = {'benchmark': 'com.example.FakeBenchmark.op', 'mode': mode, 'params': params, 'primaryMetric': primary,
             'secondaryMetrics': {}}
    if profile:
        table = json.loads(os.environ.get('FAKE_JMH_ALLOC', '{"*": [1000]}'))
        candidates = [side + ':' + value for value in params.values()] + list(params.values()) + [side + ':*', '*']
        values = next((table[candidate] for candidate in candidates if candidate in table), [1000])
        attempts = 1
        if log:
            with open(log, encoding='utf-8') as handle:
                attempts = sum(1 for line in handle if line.rstrip(NEWLINE).split(TAB) == [side, label, 'gc'])
        allocated = values[min(attempts, len(values)) - 1]
        # The first measured iteration carries a spike that is not the steady state.
        entry['secondaryMetrics'][ALLOC_METRIC] = {
            'score': allocated + 20, 'scoreUnit': 'B/op', 'rawData': [[allocated + 60, allocated, allocated + 1]]}
    with open(result_file, 'w', encoding='utf-8') as handle:
        json.dump([entry], handle)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
