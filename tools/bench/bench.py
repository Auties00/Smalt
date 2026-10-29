"""Runs Smalt's CI benchmarks: the same-job A/B timing comparison and the allocation-per-operation gate.

Usage:
  python tools/bench/bench.py base [--ref REF] [--default-branch BRANCH] [--pull-request]
      print the base commit the head is compared with (GitHub output lines)
  python tools/bench/bench.py plan --set SET [--rows IDS]
      print the modules the selected rows need and whether they need the oracle tools (GitHub output lines)
  python tools/bench/bench.py classpath --tree TREE --modules M1,M2 [--existing] [--offline]
      build TREE's modules (and what they depend on) without tests and write each module's test class path
  python tools/bench/bench.py ab --base TREE --head TREE [--set ab] [--rows IDS] [--forks 5] ...
      time every selected benchmark in both trees, forks interleaved, and report the relative change
  python tools/bench/bench.py alloc --base TREE [--tree TREE] [--set alloc] [--accept] ...
      measure the bytes allocated per operation (JMH -prof gc) in both trees and gate the head's excess
  python tools/bench/bench.py report --out DIR [--threshold 0.2] ...
      redo the A/B comparison of a finished ab run from its result files

The benchmarks are JMH classes in each module's test sources. tools/bench/benchmarks.tsv selects the curated rows:
a module, the sets the row belongs to (ab, alloc), the tools its setup needs, the least number of warm-up
iterations its cases need, a JMH include regex and the parameter values to run (each combination is a case of its
own).

ab runs every case in both trees one JMH fork at a time, alternating which tree goes first (A B, then B A), so that
drift of the runner (another tenant, thermal state, a different boost clock) hits both; it then compares the
median time per operation of each side, with a 95% bootstrap confidence interval over the forks. A case is a
regression when the head is slower by at least the threshold (20% by default; hosted runners only support 15 to
25%) and the interval excludes no change, and an improvement in the mirror case. Absolute numbers from a shared
runner are informational: only the ratio measured in one job means anything.

alloc runs every case with JMH's gc profiler, base then head, and takes gc.alloc.rate.norm (the bytes allocated
per operation by every thread of the benchmark JVM) as the least of its measured iterations. Allocation does not
depend on the runner's load. It can depend on the machine (the vector width selects other buffer sizes and
kernels), so both trees are measured on the same runner instead of against recorded numbers, and on the JIT's
escape analysis, whose scalar replacement differs from one JVM to the next, so the gate turns escape analysis off
and counts the allocations the code performs. A case fails when the head allocates more than base * (1 + rel) + abs
bytes per operation in two fresh pairs of JVMs (the head's least measurement against the base's largest).
--accept reports such an excess without failing, for a change that allocates more on purpose.

Exit status: 0 when nothing failed, 1 when a gate failed (a regression with --fail-on-regression, an allocation
over budget), 2 for a usage or infrastructure error (a build, or a benchmark that did not run on the head).
"""
import argparse
import itertools
import json
import math
import os
import platform
import random
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
MANIFEST = os.path.join(HERE, 'benchmarks.tsv')

# The dependency plugin is named with its version so that a base tree whose poms predate this tool builds too.
DEPENDENCY_PLUGIN = 'org.apache.maven.plugins:maven-dependency-plugin:3.9.0'
CLASSPATH_FILE = os.path.join('target', 'bench-classpath.txt')

# The host JVM's options; JMH passes them on to every fork, after which a benchmark's @Fork(jvmArgsAppend) applies.
DEFAULT_JVM_ARGS = ['--add-modules', 'jdk.incubator.vector,jdk.management', '--enable-native-access=ALL-UNNAMED',
                    '-Xmx2g']
# The allocation gate pins the pool size, since a parallel stage may allocate per band, and turns escape analysis
# off: whether C2 scalar-replaces an allocation varies from one JVM to the next (one workload measured 22 and 45 KB
# per round in two JVMs of one runner), while the allocations the code performs do not.
ALLOC_JVM_ARGS = ['-Dsmalt.threads=2', '-XX:-DoEscapeAnalysis']

SETS = ('ab', 'alloc')
NEEDS = ('oracles',)

TIME_FACTORS = {'ns': 1.0, 'us': 1e3, chr(0xb5) + 's': 1e3, 'ms': 1e6, 's': 1e9, 'min': 60e9, 'hr': 3600e9}

BOOTSTRAP_ROUNDS = 2000
CONFIDENCE = 0.95

DEFAULT_THRESHOLD = 0.20
DEFAULT_REL = 0.01
DEFAULT_ABS = 256.0


class BenchError(Exception):
    """Signals a usage or infrastructure error (exit status 2)."""


def log(message):
    print(time.strftime('%H:%M:%S ') + message, flush=True)


def annotate(level, title, message):
    """Prints a GitHub Actions annotation when running there."""
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print('::%s title=%s::%s' % (level, escape_command(title, True), escape_command(message)))


def escape_command(text, property_value=False):
    """Escapes text for a GitHub Actions workflow command, as its message or (property_value) a property."""
    text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    return text.replace(':', '%3A').replace(',', '%2C') if property_value else text


class Row:
    """One row of benchmarks.tsv."""

    def __init__(self, row_id, module, sets, needs, warmup, include, params):
        self.id = row_id
        self.module = module
        self.sets = sets
        self.needs = needs
        self.warmup = warmup
        self.include = include
        self.params = params

    def cases(self):
        """Returns the parameter combinations of the row, each a list of (name, value), in manifest order."""
        if not self.params:
            return [[]]
        names = [name for name, _ in self.params]
        return [list(zip(names, values)) for values in itertools.product(*[values for _, values in self.params])]


def parse_manifest(text, source='benchmarks.tsv'):
    rows = []
    seen = set()
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        fields = line.split('\t')
        if len(fields) != 7:
            raise BenchError('%s:%d: expected 7 tab-separated fields, found %d' % (source, number, len(fields)))
        row_id, module, sets, needs, warmup, include, params = (field.strip() for field in fields)
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', row_id) or row_id in seen:
            raise BenchError('%s:%d: bad or duplicate row id %r' % (source, number, row_id))
        seen.add(row_id)
        if not re.fullmatch(r'[a-z][a-z0-9-]*', module):
            raise BenchError('%s:%d: bad module %r' % (source, number, module))
        set_list = [entry for entry in sets.split(',') if entry]
        if not set_list or any(entry not in SETS for entry in set_list):
            raise BenchError('%s:%d: sets must be a comma-separated subset of %s' % (source, number, ','.join(SETS)))
        need_list = [] if needs == '-' else needs.split(',')
        if any(entry not in NEEDS for entry in need_list):
            raise BenchError('%s:%d: needs must be - or a subset of %s' % (source, number, ','.join(NEEDS)))
        if warmup != '-' and not re.fullmatch(r'[1-9][0-9]*', warmup):
            raise BenchError('%s:%d: warmup must be - or a positive number of iterations' % (source, number))
        try:
            re.compile(include)
        except re.error as error:
            raise BenchError('%s:%d: bad include regex: %s' % (source, number, error))
        rows.append(Row(row_id, module, set_list, need_list, 0 if warmup == '-' else int(warmup), include,
                        parse_params(params, source, number)))
    return rows


def parse_params(text, source, number):
    if text == '-':
        return []
    params = []
    for part in text.split(';'):
        name, equals, values = part.partition('=')
        if not equals or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name) or not values:
            raise BenchError('%s:%d: bad parameter %r (expected name=v1,v2)' % (source, number, part))
        params.append((name, values.split(',')))
    return params


def load_manifest(path=MANIFEST):
    with open(path, encoding='utf-8') as handle:
        return parse_manifest(handle.read(), os.path.basename(path))


def select_rows(rows, set_name, ids=None):
    selected = [row for row in rows if set_name in row.sets]
    if ids:
        wanted = [entry for entry in ids.split(',') if entry]
        unknown = [entry for entry in wanted if entry not in {row.id for row in selected}]
        if unknown:
            raise BenchError('no %s row named %s' % (set_name, ', '.join(unknown)))
        selected = [row for row in selected if row.id in wanted]
    if not selected:
        raise BenchError('no row selected')
    return selected


def result_key(benchmark, params):
    """Returns the key of a result: the benchmark's full name, then its parameters sorted by name."""
    if not params:
        return benchmark
    return benchmark + ' ' + ' '.join('%s=%s' % (name, params[name]) for name in sorted(params))


def nanos_per_op(score, unit):
    """Converts a JMH score to nanoseconds per operation: time units directly, throughput by its inverse."""
    if unit.endswith('/op'):
        prefix = unit[:-3]
        if prefix in TIME_FACTORS:
            return score * TIME_FACTORS[prefix]
    elif unit.startswith('ops/'):
        suffix = unit[4:]
        if suffix in TIME_FACTORS:
            if score <= 0:
                return math.inf
            return TIME_FACTORS[suffix] / score
    raise BenchError('unsupported JMH score unit %r' % unit)


def secondary(metrics, name):
    """Returns the secondary metric whose name ends with name (JMH prefixes some with a separator character)."""
    for key, value in (metrics or {}).items():
        if key == name or (key.endswith(name) and not key[len(key) - len(name) - 1].isalnum()):
            return value
    return None


def parse_results(text):
    """Parses a JMH JSON result file into a dict of key -> entry (mode, ns/op, raw allocation iterations)."""
    entries = {}
    for item in json.loads(text) if text.strip() else []:
        primary = item['primaryMetric']
        key = result_key(item['benchmark'], item.get('params') or {})
        alloc = secondary(item.get('secondaryMetrics'), 'gc.alloc.rate.norm')
        iterations = None
        if alloc is not None:
            raw = [value for fork in alloc.get('rawData') or [] for value in fork]
            iterations = raw or [alloc['score']]
        entries[key] = {
            'mode': item.get('mode', ''),
            'nanos': nanos_per_op(float(primary['score']), primary['scoreUnit']),
            'alloc': iterations,
        }
    return entries


def median(values):
    ordered = sorted(values)
    count = len(ordered)
    if count == 0:
        raise ValueError('median of nothing')
    middle = count // 2
    return ordered[middle] if count % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def bootstrap_ratio(base, head, seed, rounds=BOOTSTRAP_ROUNDS, confidence=CONFIDENCE):
    """Returns the (low, high) percentile interval of median(head) / median(base) over resampled forks.

    The generator is seeded, so the same results always give the same interval.
    """
    generator = random.Random(seed)
    ratios = []
    for _ in range(rounds):
        resampled_base = median([generator.choice(base) for _ in base])
        resampled_head = median([generator.choice(head) for _ in head])
        ratios.append(resampled_head / resampled_base if resampled_base > 0 else math.inf)
    ratios.sort()
    tail = (1 - confidence) / 2
    low = ratios[int(math.floor(tail * rounds))]
    high = ratios[min(rounds - 1, int(math.ceil((1 - tail) * rounds)) - 1)]
    return low, high


def compare(base, head, threshold, seed=0):
    """Compares the per-fork times of one case; returns the comparison as a dict.

    verdict is one of regression, improvement (beyond the threshold and outside the interval), slower, faster
    (outside the interval only), same (inside it), n/a (fewer than two forks on a side), new (no base), removed (no
    head) and failed (neither side measured it).
    """
    result = {'base': median(base) if base else None, 'head': median(head) if head else None,
              'base_forks': len(base), 'head_forks': len(head), 'ratio': None, 'low': None, 'high': None}
    if not head and not base:
        result['verdict'] = 'failed'
        return result
    if not base:
        result['verdict'] = 'new'
        return result
    if not head:
        result['verdict'] = 'removed'
        return result
    ratio = result['head'] / result['base'] if result['base'] > 0 else math.inf
    result['ratio'] = ratio
    if len(base) < 2 or len(head) < 2:
        result['verdict'] = 'n/a'
        return result
    low, high = bootstrap_ratio(base, head, seed)
    result['low'], result['high'] = low, high
    if low > 1:
        result['verdict'] = 'regression' if ratio - 1 >= threshold else 'slower'
    elif high < 1:
        result['verdict'] = 'improvement' if 1 / ratio - 1 >= threshold else 'faster'
    else:
        result['verdict'] = 'same'
    return result


def seed_of(key):
    return zlib.crc32(key.encode('utf-8'))


def java_command(explicit=None):
    """Returns the command that starts a JVM: explicit split like a shell would, else java in JAVA_HOME or on PATH."""
    if explicit:
        return command_words(explicit)
    home = os.environ.get('JAVA_HOME')
    if home:
        candidate = os.path.join(home, 'bin', 'java.exe' if os.name == 'nt' else 'java')
        if os.path.exists(candidate):
            return [candidate]
    return ['java']


def command_words(explicit):
    """Returns the words of an explicit command: a path to an executable as is (a Windows path keeps its
    backslashes), anything else split like a shell would."""
    if os.path.isfile(explicit):
        return [explicit]
    return shlex.split(explicit)


def maven_command(explicit=None):
    """Returns the command that starts Maven: explicit, else mvn on PATH."""
    if explicit:
        return command_words(explicit)
    for name in ('mvn', 'mvn.cmd'):
        found = shutil.which(name)
        if found:
            return [found]
    raise BenchError('mvn is not on PATH')


def build_classpaths(tree, modules, maven=None, offline=False, extra=()):
    """Builds the modules of tree without running tests and writes each one's test class path file."""
    command = maven_command(maven) + ['-B', '-ntp', '-q', '-DskipTests', '-pl', ','.join(modules), '-am']
    if offline:
        command.append('-o')
    command += list(extra) + ['package', DEPENDENCY_PLUGIN + ':build-classpath',
                              '-Dmdep.includeScope=test', '-Dmdep.outputFile=' + CLASSPATH_FILE]
    log('building %s in %s' % (','.join(modules), tree))
    status = subprocess.call(command, cwd=tree)
    if status != 0:
        raise BenchError('the build of %s failed with exit status %d' % (tree, status))
    for module in modules:
        classpath(tree, module)


def classpath(tree, module):
    """Returns the class path of module's benchmarks in tree: its test classes, its classes, its dependencies."""
    directory = os.path.join(tree, module)
    listing = os.path.join(directory, CLASSPATH_FILE)
    if not os.path.isfile(listing):
        raise BenchError('%s has no %s: run the classpath command first' % (directory, CLASSPATH_FILE))
    with open(listing, encoding='utf-8') as handle:
        dependencies = handle.read().strip()
    entries = [os.path.join(directory, 'target', 'test-classes'), os.path.join(directory, 'target', 'classes')]
    return os.pathsep.join(entries + ([dependencies] if dependencies else []))


def jmh_command(java, jvm_args, cp, row, case, settings, result_file, profile_gc=False):
    command = list(java) + list(jvm_args) + ['-cp', cp, 'org.openjdk.jmh.Main', row.include]
    for name, value in case:
        command += ['-p', '%s=%s' % (name, value)]
    command += ['-f', '1', '-wi', str(max(settings.warmup, row.warmup)), '-w', '%ss' % settings.time,
                '-i', str(settings.iterations), '-r', '%ss' % settings.time,
                '-foe', 'true', '-rf', 'json', '-rff', result_file]
    if profile_gc:
        command += ['-prof', 'gc']
    return command


def case_label(row, case):
    return row.id + ('' if not case else ' ' + ' '.join('%s=%s' % pair for pair in case))


def run_jmh(command, work, log_file, timeout):
    """Runs one JMH invocation; returns True when it exited normally.

    The invocation runs in a process group of its own, so that a timeout ends the JVM JMH forked as well as the
    host JVM; a forked JVM left running would load the runner for every later measurement.
    """
    os.makedirs(work, exist_ok=True)
    with open(log_file, 'w', encoding='utf-8', errors='replace') as output:
        output.write(' '.join(shlex.quote(part) for part in command) + '\n')
        output.flush()
        if os.name == 'nt':
            group = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP}
        else:
            group = {'start_new_session': True}
        process = subprocess.Popen(command, cwd=work, stdout=output, stderr=subprocess.STDOUT, **group)
        try:
            status = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            kill_group(process)
            output.write('\ntimed out after %d s\n' % timeout)
            return False
    return status == 0


def kill_group(process):
    """Kills process and every process it started, then waits for it."""
    if os.name == 'nt':
        subprocess.call(['taskkill', '/F', '/T', '/PID', str(process.pid)], stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.kill()
    process.wait()


def read_results(path):
    if not os.path.isfile(path):
        return {}
    with open(path, encoding='utf-8') as handle:
        return parse_results(handle.read())


def environment(java):
    """Describes the runner and the JVM, for the reports."""
    cpu = ''
    try:
        with open('/proc/cpuinfo', encoding='utf-8') as handle:
            for line in handle:
                if line.startswith('model name'):
                    cpu = line.split(':', 1)[1].strip()
                    break
    except OSError:
        pass
    if not cpu:
        cpu = platform.processor() or platform.machine()
    try:
        version = subprocess.run(list(java) + ['-version'], capture_output=True, text=True, timeout=60).stderr
        jdk = version.strip().splitlines()[-1] if version.strip() else 'unknown'
    except (OSError, subprocess.SubprocessError):
        jdk = 'unknown'
    return {'cpu': cpu, 'cores': os.cpu_count(), 'os': platform.platform(), 'jdk': jdk}


def ab_cases(rows):
    return [(row, index, case) for row in rows for index, case in enumerate(row.cases())]


def run_ab(args):
    rows = select_rows(load_manifest(args.manifest), args.set, args.rows)
    java = java_command(args.java)
    out = os.path.abspath(args.out)
    work = os.path.join(out, 'work')
    os.makedirs(out, exist_ok=True)
    trees = {'base': os.path.abspath(args.base), 'head': os.path.abspath(args.head)}
    extra = {'base': shlex.split(args.base_args or ''), 'head': shlex.split(args.head_args or '')}
    classpaths = {'head': {row.module: classpath(trees['head'], row.module) for row in rows}, 'base': {}}
    notes = []
    for module in sorted({row.module for row in rows}):
        try:
            classpaths['base'][module] = classpath(trees['base'], module)
        except BenchError:
            # A module the base does not have yet: its cases are new.
            notes.append('the base has no build of %s' % module)
    for side in ('base', 'head'):
        shutil.rmtree(os.path.join(out, side), ignore_errors=True)
    jvm_args = DEFAULT_JVM_ARGS + shlex.split(args.jvm_args or '')
    cases = ab_cases(rows)
    log('A/B of %d cases, %d forks per side' % (len(cases), args.forks))
    failures = []
    for row, index, case in cases:
        for fork in range(args.forks):
            order = ('base', 'head') if fork % 2 == 0 else ('head', 'base')
            for side in order:
                if row.module not in classpaths[side]:
                    continue
                stem = os.path.join(out, side, row.id, '%03d-f%02d' % (index, fork))
                os.makedirs(os.path.dirname(stem), exist_ok=True)
                command = jmh_command(java, jvm_args + extra[side], classpaths[side][row.module], row, case, args,
                                      stem + '.json')
                started = time.time()
                ok = run_jmh(command, work, stem + '.log', args.timeout)
                log('%s fork %d %s: %s in %.0f s' % (case_label(row, case), fork + 1, side,
                                                      'ok' if ok else 'FAILED', time.time() - started))
                if not ok:
                    failures.append({'side': side, 'row': row.id, 'index': index, 'label': case_label(row, case),
                                     'log': os.path.relpath(stem + '.log', out)})
    meta = {'base': args.base_label or trees['base'], 'head': args.head_label or trees['head'],
            'forks': args.forks, 'warmup': args.warmup, 'iterations': args.iterations, 'time': args.time,
            'jvm_args': ' '.join(jvm_args), 'base_args': args.base_args or '', 'head_args': args.head_args or '',
            'environment': environment(java), 'failures': failures, 'notes': notes,
            'cases': [[row.id, index] for row, index, _ in cases]}
    with open(os.path.join(out, 'meta.json'), 'w', encoding='utf-8') as handle:
        json.dump(meta, handle, indent=1)
    return finish_ab(out, args)


def collect_ab(out):
    """Reads the per-fork results of an ab run.

    Returns key -> side -> list of ns/op, and key -> the (row id, case index) pairs whose files reported it.
    """
    samples = {}
    origins = {}
    for side in ('base', 'head'):
        side_directory = os.path.join(out, side)
        if not os.path.isdir(side_directory):
            continue
        for row_id in sorted(os.listdir(side_directory)):
            row_directory = os.path.join(side_directory, row_id)
            for name in sorted(os.listdir(row_directory)):
                if not name.endswith('.json'):
                    continue
                index = int(name.split('-', 1)[0])
                for key, entry in read_results(os.path.join(row_directory, name)).items():
                    samples.setdefault(key, {'base': [], 'head': []})[side].append(entry['nanos'])
                    origins.setdefault(key, set()).add((row_id, index))
    return samples, origins


def finish_ab(out, args):
    """Compares the per-fork results of the ab run in out, writes its reports; returns the exit status."""
    path = os.path.join(out, 'meta.json')
    if not os.path.isfile(path):
        raise BenchError('%s holds no ab run (no meta.json)' % out)
    with open(path, encoding='utf-8') as handle:
        meta = json.load(handle)
    samples, origins = collect_ab(out)
    failed = {(failure['side'], failure['row'], failure['index']) for failure in meta['failures']}
    comparisons = []
    for key in sorted(samples):
        comparison = compare(samples[key]['base'], samples[key]['head'], args.threshold, seed_of(key))
        comparison['key'] = key
        # A side without results whose runs failed did not measure the case, rather than lack it.
        for side, verdict in (('head', 'removed'), ('base', 'new')):
            if comparison['verdict'] == verdict and any((side,) + origin in failed for origin in origins[key]):
                comparison['verdict'] = 'failed'
                comparison['failed_side'] = side
        comparisons.append(comparison)
    with open(os.path.join(out, 'ab-report.json'), 'w', encoding='utf-8') as handle:
        json.dump({'meta': meta, 'threshold': args.threshold, 'results': comparisons}, handle, indent=1)
    markdown = ab_markdown(meta, comparisons, args.threshold)
    with open(os.path.join(out, 'ab-report.md'), 'w', encoding='utf-8') as handle:
        handle.write(markdown)
    if args.summary:
        with open(args.summary, 'a', encoding='utf-8') as handle:
            handle.write(markdown + '\n')
    print(markdown)
    if args.store:
        with open(args.store, 'w', encoding='utf-8') as handle:
            json.dump(store_entries(meta, comparisons), handle, indent=1)
    regressions = [entry for entry in comparisons if entry['verdict'] == 'regression']
    for entry in regressions:
        annotate('error' if args.fail_on_regression else 'warning', 'Benchmark regression',
                 '%s is %s slower (95%% CI %s to %s)' % (entry['key'], percent(entry['ratio'] - 1),
                                                         percent(entry['low'] - 1), percent(entry['high'] - 1)))
    head_failures = [failure for failure in meta['failures'] if failure['side'] == 'head']
    for (side, label), logs in grouped_failures(meta['failures']).items():
        annotate('error' if side == 'head' else 'warning', 'Benchmark failed',
                 '%s did not run on the %s tree in %d forks (%s)' % (label, side, len(logs), logs[0]))
    if head_failures:
        return 2
    if regressions and args.fail_on_regression:
        return 1
    return 0


def grouped_failures(failures):
    """Returns (side, case label) -> the logs of its failed forks, in the order they ran."""
    grouped = {}
    for failure in failures:
        grouped.setdefault((failure['side'], failure['label']), []).append(failure['log'])
    return grouped


def percent(value):
    return '%+.1f%%' % (value * 100)


def format_time(nanos):
    if nanos is None:
        return '-'
    for unit, factor in (('s', 1e9), ('ms', 1e6), ('us', 1e3)):
        if nanos >= factor:
            return '%.3g %s' % (nanos / factor, unit)
    return '%.3g ns' % nanos


VERDICT_ORDER = {'regression': 0, 'failed': 1, 'slower': 2, 'removed': 3, 'new': 4, 'n/a': 5, 'same': 6,
                 'faster': 7, 'improvement': 8}


def ab_markdown(meta, comparisons, threshold):
    env = meta['environment']
    lines = ['## Benchmarks: A/B', '',
             '- base: `%s`%s' % (meta['base'], (' with `%s`' % meta['base_args']) if meta['base_args'] else ''),
             '- head: `%s`%s' % (meta['head'], (' with `%s`' % meta['head_args']) if meta['head_args'] else ''),
             '- runner: %s, %s cores, %s; %s' % (env['cpu'], env['cores'], env['os'], env['jdk']),
             '- %d forks per side, interleaved; %d warm-up and %d measured iterations of %s s; JVM `%s`'
             % (meta['forks'], meta['warmup'], meta['iterations'], meta['time'], meta['jvm_args']),
             '- alert threshold %s; the change is median(head) / median(base) - 1 of the time per operation, with '
             'a 95%% bootstrap interval over the forks' % percent(threshold).lstrip('+'), '']
    counts = {}
    for entry in comparisons:
        counts[entry['verdict']] = counts.get(entry['verdict'], 0) + 1
    lines.append('**%s**' % ', '.join('%d %s' % (counts[verdict], verdict)
                                       for verdict in sorted(counts, key=VERDICT_ORDER.get)) if counts
                 else '**no results**')
    lines.append('')
    if meta['failures']:
        grouped = grouped_failures(meta['failures'])
        lines.append('Did not run: ' + '; '.join('%s (%s, %d of %d forks, %s)' % (label, side, len(logs),
                                                                               meta['forks'], logs[0])
                                                  for (side, label), logs in grouped.items()))
        lines.append('')
    if meta.get('notes'):
        lines.append('Note: ' + '; '.join(meta['notes']) + '.')
        lines.append('')
    lines += ['| Benchmark | Base | Head | Change | 95% interval | Verdict |', '|---|---|---|---|---|---|']
    ordered = sorted(comparisons, key=lambda entry: (VERDICT_ORDER[entry['verdict']],
                                                     -(entry['ratio'] or 0), entry['key']))
    for entry in ordered:
        change = percent(entry['ratio'] - 1) if entry['ratio'] is not None else '-'
        interval = ('%s to %s' % (percent(entry['low'] - 1), percent(entry['high'] - 1))
                    if entry['low'] is not None else '-')
        verdict = entry['verdict'].upper() if entry['verdict'] == 'regression' else entry['verdict']
        if 'failed_side' in entry:
            verdict += ' (%s)' % entry['failed_side']
        lines.append('| `%s` | %s | %s | %s | %s | %s |' % (entry['key'].replace('|', '\\|'),
                                                            format_time(entry['base']), format_time(entry['head']),
                                                            change, interval, verdict))
    return '\n'.join(lines) + '\n'


def store_entries(meta, comparisons):
    """Returns the head's results in github-action-benchmark's customSmallerIsBetter format (ns per operation)."""
    entries = []
    for entry in comparisons:
        if entry['head'] is None:
            continue
        extra = ['runner: %s' % meta['environment']['cpu'], 'forks: %d' % entry['head_forks']]
        if entry['ratio'] is not None:
            extra.append('change against %s: %s' % (meta['base'], percent(entry['ratio'] - 1)))
            if entry['low'] is not None:
                extra.append('95%% interval: %s to %s' % (percent(entry['low'] - 1), percent(entry['high'] - 1)))
        entries.append({'name': entry['key'], 'unit': 'ns/op', 'value': round(entry['head'], 3),
                        'extra': '\n'.join(extra)})
    return entries


def budget(base, rel, absolute):
    """Returns the most bytes per operation the head may allocate where the base allocates base."""
    return base * (1 + rel) + absolute


def judge_alloc(base, head, rel, absolute):
    """Returns the verdict of one case: base and head are its measurements in bytes per operation, or None.

    The verdict is over (the head above the budget), under (the head below the base by more than the tolerance),
    ok, new (the base did not report it) or removed (the head did not).
    """
    if base is None and head is None:
        raise ValueError('nothing measured')
    if base is None:
        return 'new'
    if head is None:
        return 'removed'
    if head > budget(base, rel, absolute):
        return 'over'
    if head < base * (1 - rel) - absolute:
        return 'under'
    return 'ok'


def measure_alloc(side, row, case, args, java, jvm_args, cp, out, attempt):
    """Runs one case with the gc profiler; returns whether it ran and key -> least bytes per operation."""
    stem = os.path.join(out, 'alloc', side, row.id, '%s-a%d' % (case_index(row, case), attempt))
    os.makedirs(os.path.dirname(stem), exist_ok=True)
    command = jmh_command(java, jvm_args, cp, row, case, args, stem + '.json', profile_gc=True)
    started = time.time()
    ok = run_jmh(command, os.path.join(out, 'work'), stem + '.log', args.timeout)
    results = read_results(stem + '.json') if ok else {}
    log('%s %s attempt %d: %s in %.0f s' % (case_label(row, case), side, attempt, 'ok' if ok else 'FAILED',
                                            time.time() - started))
    values = {}
    for key, entry in results.items():
        if entry['alloc'] is None:
            raise BenchError('%s reported no gc.alloc.rate.norm' % key)
        values[key] = min(entry['alloc'])
    return ok, values, os.path.relpath(stem + '.log', out)


def case_index(row, case):
    return '%03d' % row.cases().index(case)


def run_alloc(args):
    rows = select_rows(load_manifest(args.manifest), args.set, args.rows)
    java = java_command(args.java)
    out = os.path.abspath(args.out)
    shutil.rmtree(os.path.join(out, 'alloc'), ignore_errors=True)
    trees = {'base': os.path.abspath(args.base), 'head': os.path.abspath(args.tree)}
    jvm_args = DEFAULT_JVM_ARGS + ALLOC_JVM_ARGS + shlex.split(args.jvm_args or '')
    extra = {'base': shlex.split(args.base_args or ''), 'head': shlex.split(args.head_args or '')}
    classpaths = {'head': {row.module: classpath(trees['head'], row.module) for row in rows}, 'base': {}}
    notes = []
    for module in sorted({row.module for row in rows}):
        try:
            classpaths['base'][module] = classpath(trees['base'], module)
        except BenchError:
            notes.append('the base has no build of %s' % module)
    results = []
    failures = []
    for row in rows:
        for case in row.cases():
            measured = {'base': [], 'head': []}
            failed = set()
            for attempt in (1, 2):
                for side in ('base', 'head'):
                    if row.module not in classpaths[side]:
                        continue
                    ok, values, log_file = measure_alloc(side, row, case, args, java, jvm_args + extra[side],
                                                         classpaths[side][row.module], out, attempt)
                    if not ok:
                        failures.append({'side': side, 'label': case_label(row, case), 'log': log_file})
                        failed.add(side)
                    measured[side].append(values)
                over = [key for key in measured['head'][-1] if measured['base'] and key in measured['base'][-1]
                        and judge_alloc(measured['base'][-1][key], measured['head'][-1][key], args.rel,
                                        args.abs) == 'over'] if measured['head'] else []
                if not over:
                    break
                # One JVM can settle on more allocating code than the next (compilation order, inlining), so an
                # excess is confirmed in fresh JVMs of both trees, against the larger of the base's measurements.
                log('%s over budget, confirming' % ', '.join(over))
            keys = sorted({key for side in measured for values in measured[side] for key in values})
            for key in keys:
                base_values = [values[key] for values in measured['base'] if key in values]
                head_values = [values[key] for values in measured['head'] if key in values]
                base_value = max(base_values) if base_values else None
                head_value = min(head_values) if head_values else None
                verdict = judge_alloc(base_value, head_value, args.rel, args.abs)
                result = {'key': key, 'base': base_value, 'head': head_value, 'verdict': verdict,
                          'attempts': max(len(base_values), len(head_values))}
                if verdict == 'over' and args.accept:
                    result['verdict'] = 'accepted'
                for side, missing in (('base', 'new'), ('head', 'removed')):
                    if verdict == missing and side in failed:
                        result['verdict'] = 'failed'
                        result['failed_side'] = side
                results.append(result)
    os.makedirs(out, exist_ok=True)
    report = {'base': args.base_label or trees['base'], 'head': args.head_label or trees['head'],
              'rel': args.rel, 'abs': args.abs, 'jvm_args': ' '.join(jvm_args), 'base_args': args.base_args or '',
              'head_args': args.head_args or '', 'environment': environment(java),
              'notes': notes, 'failures': failures, 'results': results}
    with open(os.path.join(out, 'alloc-report.json'), 'w', encoding='utf-8') as handle:
        json.dump(report, handle, indent=1)
    markdown = alloc_markdown(report, args)
    with open(os.path.join(out, 'alloc-report.md'), 'w', encoding='utf-8') as handle:
        handle.write(markdown)
    if args.summary:
        with open(args.summary, 'a', encoding='utf-8') as handle:
            handle.write(markdown + '\n')
    print(markdown)
    for result in results:
        if result['verdict'] in ('over', 'accepted'):
            annotate('warning' if result['verdict'] == 'accepted' else 'error', 'Allocation over budget',
                     '%s allocates %d B/op where the base allocates %d (budget %d)%s'
                     % (result['key'], result['head'], result['base'], budget(result['base'], args.rel, args.abs),
                        ', accepted' if result['verdict'] == 'accepted' else ''))
    for failure in failures:
        annotate('error' if failure['side'] == 'head' else 'warning', 'Benchmark failed',
                 '%s did not run on the %s tree (%s)' % (failure['label'], failure['side'], failure['log']))
    if any(failure['side'] == 'head' for failure in failures):
        return 2
    return 1 if any(result['verdict'] == 'over' for result in results) else 0


ALLOC_ORDER = {'over': 0, 'failed': 1, 'accepted': 2, 'removed': 3, 'new': 4, 'under': 5, 'ok': 6}


def format_bytes(value):
    return '-' if value is None else '{:,}'.format(int(round(value)))


def alloc_markdown(report, args):
    env = report['environment']
    results = report['results']
    lines = ['## Benchmarks: allocation per operation', '',
             '- base: `%s`%s' % (report['base'], (' with `%s`' % report['base_args']) if report['base_args'] else ''),
             '- head: `%s`%s' % (report['head'], (' with `%s`' % report['head_args']) if report['head_args'] else ''),
             '- runner: %s, %s cores, %s; %s' % (env['cpu'], env['cores'], env['os'], env['jdk']),
             '- JMH `-prof gc`: gc.alloc.rate.norm, the bytes every thread allocates per operation, the least of %d '
             'measured iterations of %s s after %d warm-up iterations; JVM `%s`'
             % (args.iterations, args.time, args.warmup, report['jvm_args']),
             '- budget: base * %.2f + %d B/op; an excess is confirmed once, in fresh JVMs of both trees'
             % (1 + report['rel'], report['abs']), '']
    counts = {}
    for result in results:
        counts[result['verdict']] = counts.get(result['verdict'], 0) + 1
    lines.append('**%s**' % (', '.join('%d %s' % (counts[verdict], verdict)
                                        for verdict in sorted(counts, key=ALLOC_ORDER.get)) or 'no results'))
    if counts.get('over'):
        lines.append('')
        lines.append('A change that allocates more on purpose carries the `allocation-accepted` label '
                     '(tools/bench/README.md).')
    lines.append('')
    if report['failures']:
        lines.append('Did not run: ' + '; '.join('%s (%s, %s)' % (failure['label'], failure['side'], failure['log'])
                                                  for failure in report['failures']))
        lines.append('')
    if report['notes']:
        lines.append('Note: ' + '; '.join(report['notes']) + '.')
        lines.append('')
    lines += ['| Benchmark | Base B/op | Head B/op | Change | Budget B/op | Verdict |', '|---|---|---|---|---|---|']
    for result in sorted(results, key=lambda entry: (ALLOC_ORDER[entry['verdict']], entry['key'])):
        base, head = result['base'], result['head']
        change = '-'
        if base is not None and head is not None:
            change = '%+d' % round(head - base) + (' (%s)' % percent(head / base - 1) if base > 0 else '')
        lines.append('| `%s` | %s | %s | %s | %s | %s |' % (
            result['key'].replace('|', '\\|'), format_bytes(base), format_bytes(head), change,
            '-' if base is None else format_bytes(budget(base, report['rel'], report['abs'])),
            (result['verdict'].upper() if result['verdict'] == 'over' else result['verdict'])
            + (' (%s)' % result['failed_side'] if 'failed_side' in result else '')))
    return '\n'.join(lines) + '\n'


def git(repository, *arguments):
    """Runs git in repository; returns its standard output stripped, or None when it fails."""
    # A file system monitor daemon that git starts would inherit the captured output and keep it open.
    completed = subprocess.run(['git', '-c', 'core.fsmonitor=false'] + list(arguments), cwd=repository,
                               stdin=subprocess.DEVNULL, capture_output=True, text=True)
    return completed.stdout.strip() if completed.returncode == 0 else None


def choose_base(repository, ref=None, default_branch=None, pull_request=False):
    """Returns (base commit, head commit, why that base) for the head checked out in repository.

    An explicit ref wins (a commit, a local ref or a branch of origin). A pull request's checkout is the merge of
    the pull request into its base branch, so the base is that merge's first parent: the head then differs from it
    by exactly the pull request's change. Otherwise the base is the merge base with origin's default branch, and on
    the default branch itself (where that is the head) the last commit at least a week old.
    """
    head = git(repository, 'rev-parse', 'HEAD')
    if not head:
        raise BenchError('%s is not a git checkout' % repository)
    if ref:
        for candidate in (ref, 'origin/' + ref):
            base = git(repository, 'rev-parse', '--verify', '--quiet', candidate + '^{commit}')
            if base:
                return base, head, ref
        raise BenchError('no commit named %s' % ref)
    if pull_request:
        parents = (git(repository, 'rev-list', '--parents', '-n', '1', 'HEAD') or '').split()
        if len(parents) == 3:
            return parents[1], head, 'the base branch of the pull request'
    if default_branch:
        base = git(repository, 'merge-base', 'HEAD', 'origin/' + default_branch)
        if base and base != head:
            return base, head, 'merge base with ' + default_branch
    base = git(repository, 'rev-list', '-n', '1', '--before=7 days ago', 'HEAD')
    if not base:
        raise BenchError('no commit at least a week old to compare with')
    return base, head, 'last commit at least a week old'


def command_base(args):
    base, head, reason = choose_base(os.path.abspath(args.repository), args.ref, args.default_branch,
                                     args.pull_request)
    print('base=' + base)
    print('head=' + head)
    print('base_label=%s (%s)' % (base[:12], reason))
    print('head_label=%s%s' % (head[:12], ' (%s)' % args.head_name if args.head_name else ''))
    print('same=' + ('true' if base == head else 'false'))
    return 0


def command_plan(args):
    rows = select_rows(load_manifest(args.manifest), args.set, args.rows)
    modules = []
    for row in rows:
        if row.module not in modules:
            modules.append(row.module)
    print('modules=' + ','.join(modules))
    print('oracles=' + ('true' if any('oracles' in row.needs for row in rows) else 'false'))
    print('cases=%d' % sum(len(row.cases()) for row in rows))
    return 0


def command_classpath(args):
    modules = [module for module in args.modules.split(',') if module]
    if not modules:
        raise BenchError('no module given')
    tree = os.path.abspath(args.tree)
    if args.existing:
        missing = [module for module in modules if not os.path.isfile(os.path.join(tree, module, 'pom.xml'))]
        for module in missing:
            log('%s has no module %s: skipped' % (tree, module))
        modules = [module for module in modules if module not in missing]
        if not modules:
            return 0
    build_classpaths(tree, modules, args.maven, args.offline, shlex.split(args.maven_args or ''))
    return 0


def add_run_options(parser, warmup, iterations, forks):
    parser.add_argument('--manifest', default=MANIFEST)
    parser.add_argument('--rows', help='comma-separated row ids of the set (default: every row of the set)')
    parser.add_argument('--warmup', type=int, default=warmup, help='JMH warm-up iterations')
    parser.add_argument('--iterations', type=int, default=iterations, help='JMH measurement iterations')
    parser.add_argument('--time', default='1', help='seconds per iteration')
    parser.add_argument('--timeout', type=int, default=1800, help='seconds one JMH invocation may take')
    parser.add_argument('--java', help='the command that starts a JVM (default: java in JAVA_HOME, then on PATH)')
    parser.add_argument('--jvm-args', help='JVM options added for every fork')
    parser.add_argument('--out', default=os.path.join(ROOT, 'target', 'bench'))
    parser.add_argument('--summary', help='a Markdown file to append the report to (GITHUB_STEP_SUMMARY)')
    if forks:
        parser.add_argument('--forks', type=int, default=forks, help='JMH forks per side')


def positive(value):
    number = float(value)
    if not 0 < number < 10:
        raise argparse.ArgumentTypeError('expected a fraction such as 0.2')
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n', 1)[0])
    commands = parser.add_subparsers(dest='command', required=True)

    choose = commands.add_parser('base', help='print the base commit to compare the checked-out head with')
    choose.add_argument('--repository', default=ROOT)
    choose.add_argument('--ref', help='an explicit base: a commit, a ref or a branch of origin')
    choose.add_argument('--default-branch', help="origin's default branch, for the merge base")
    choose.add_argument('--pull-request', action='store_true', help='the checkout is a pull request merge')
    choose.add_argument('--head-name', help='a name for the head in its label, for example the branch')

    plan = commands.add_parser('plan', help='print the modules and tools the selected rows need')
    plan.add_argument('--manifest', default=MANIFEST)
    plan.add_argument('--set', choices=SETS, required=True)
    plan.add_argument('--rows')

    build = commands.add_parser('classpath', help='build a tree and write its benchmark class paths')
    build.add_argument('--tree', default=ROOT)
    build.add_argument('--modules', required=True)
    build.add_argument('--maven', help='the Maven command (default: mvn on PATH)')
    build.add_argument('--maven-args', help='extra Maven arguments')
    build.add_argument('--offline', action='store_true')
    build.add_argument('--existing', action='store_true',
                       help='skip the modules the tree does not have (a base that predates them)')

    ab = commands.add_parser('ab', help='time the selected benchmarks in two trees')
    ab.add_argument('--base', required=True, help='the baseline tree (A)')
    ab.add_argument('--head', required=True, help='the candidate tree (B)')
    ab.add_argument('--base-label', help='how the report names the base, for example its commit')
    ab.add_argument('--head-label', help='how the report names the head')
    ab.add_argument('--base-args', help='JVM options for the base forks only')
    ab.add_argument('--head-args', help='JVM options for the head forks only')
    ab.add_argument('--set', choices=SETS, default='ab')
    ab.add_argument('--threshold', type=positive, default=DEFAULT_THRESHOLD)
    ab.add_argument('--fail-on-regression', action='store_true')
    ab.add_argument('--store', help='write the head results in customSmallerIsBetter JSON to this file')
    add_run_options(ab, 5, 8, 5)

    alloc = commands.add_parser('alloc', help='gate the bytes allocated per operation')
    alloc.add_argument('--base', required=True, help='the baseline tree')
    alloc.add_argument('--tree', default=ROOT, help='the candidate tree (the head)')
    alloc.add_argument('--base-label', help='how the report names the base, for example its commit')
    alloc.add_argument('--head-label', help='how the report names the head')
    alloc.add_argument('--base-args', help='JVM options for the base forks only')
    alloc.add_argument('--head-args', help='JVM options for the head forks only')
    alloc.add_argument('--set', choices=SETS, default='alloc')
    alloc.add_argument('--rel', type=float, default=DEFAULT_REL, help='relative tolerance over the base')
    alloc.add_argument('--abs', type=float, default=DEFAULT_ABS, help='absolute tolerance in bytes per operation')
    alloc.add_argument('--accept', action='store_true', help='report an excess without failing (a change that '
                       'allocates more on purpose)')
    add_run_options(alloc, 8, 4, 0)

    report = commands.add_parser('report', help='redo the comparison of a finished ab run')
    report.add_argument('--out', required=True)
    report.add_argument('--threshold', type=positive, default=DEFAULT_THRESHOLD)
    report.add_argument('--fail-on-regression', action='store_true')
    report.add_argument('--summary')
    report.add_argument('--store')

    args = parser.parse_args(argv)
    try:
        if args.command == 'base':
            return command_base(args)
        if args.command == 'plan':
            return command_plan(args)
        if args.command == 'classpath':
            return command_classpath(args)
        if args.command == 'ab':
            if args.forks < 1 or args.warmup < 0 or args.iterations < 1:
                raise BenchError('forks and iterations must be positive')
            return run_ab(args)
        if args.command == 'alloc':
            if args.iterations < 1 or args.warmup < 0:
                raise BenchError('iterations must be positive')
            return run_alloc(args)
        return finish_ab(os.path.abspath(args.out), args)
    except BenchError as error:
        print('bench.py: error: %s' % error, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
