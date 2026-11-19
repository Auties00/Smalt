"""Tests of bench.py: the manifest, the JMH result parsing, the A/B statistics and verdicts, and both commands run
end to end against fake_jmh.py, which stands in for the JVM.

Run with: python -m unittest discover -s tools/bench -p "test_*.py"
"""
import argparse
import contextlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import bench  # noqa: E402

FAKE_JAVA = '"%s" "%s"' % (sys.executable, os.path.join(HERE, 'fake_jmh.py'))
FAKE_MAVEN = '"%s" "%s"' % (sys.executable, os.path.join(HERE, 'fake_mvn.py'))
TAB = chr(9)


def remove_tree(path):
    # Git writes its objects read-only, which Windows refuses to delete.
    def writable(function, name, _):
        os.chmod(name, stat.S_IWRITE)
        function(name)
    shutil.rmtree(path, onexc=writable) if sys.version_info >= (3, 12) else shutil.rmtree(path, onerror=writable)


def manifest(*rows):
    return '\n'.join(['# comment'] + [TAB.join(row) for row in rows]) + '\n'


def jmh_entry(benchmark, score, unit='ms/op', params=None, alloc=None, mode='avgt'):
    entry = {'benchmark': benchmark, 'mode': mode, 'params': params or {},
             'primaryMetric': {'score': score, 'scoreUnit': unit, 'rawData': [[score]]}}
    if alloc is not None:
        entry['secondaryMetrics'] = {'gc.alloc.rate.norm': {'score': max(alloc), 'scoreUnit': 'B/op',
                                                            'rawData': [alloc]}}
    return entry


class ManifestTest(unittest.TestCase):
    def test_rows_and_cases(self):
        rows = bench.parse_manifest(manifest(
            ['a', 'core', 'ab,alloc', '-', '-', r'\.FooBenchmark\.', 'x=1,2;y=p,q,r'],
            ['b', 'it', 'alloc', 'oracles', '12', 'Bar', '-']))
        self.assertEqual(['a', 'b'], [row.id for row in rows])
        self.assertEqual(['ab', 'alloc'], rows[0].sets)
        self.assertEqual(['oracles'], rows[1].needs)
        self.assertEqual([0, 12], [row.warmup for row in rows])
        cases = rows[0].cases()
        self.assertEqual(6, len(cases))
        self.assertEqual([('x', '1'), ('y', 'p')], cases[0])
        self.assertEqual([('x', '2'), ('y', 'r')], cases[-1])
        self.assertEqual([[]], rows[1].cases())

    def test_parameter_values_may_hold_spaces_and_colons(self):
        rows = bench.parse_manifest(manifest(['a', 'it', 'ab', '-', '-', 'X',
                                              'spec=h264enc:rt=true:threads=2;s=double talk']))
        self.assertEqual([('spec', 'h264enc:rt=true:threads=2'), ('s', 'double talk')], rows[0].cases()[0])

    def test_rejects_malformed_rows(self):
        bad = [
            ['a', 'core', 'ab', '-', 'X', '-'],
            ['A', 'core', 'ab', '-', '-', 'X', '-'],
            ['a', 'core', 'weekly', '-', '-', 'X', '-'],
            ['a', 'core', 'ab', 'ffmpeg', '-', 'X', '-'],
            ['a', 'core', 'ab', '-', '0', 'X', '-'],
            ['a', 'core', 'ab', '-', 'x', 'X', '-'],
            ['a', 'core', 'ab', '-', '-', '(', '-'],
            ['a', 'core', 'ab', '-', '-', 'X', 'x'],
            ['a', 'core', 'ab', '-', '-', 'X', 'x='],
        ]
        for row in bad:
            with self.subTest(row=row), self.assertRaises(bench.BenchError):
                bench.parse_manifest(manifest(row))
        with self.assertRaises(bench.BenchError):
            bench.parse_manifest(manifest(['a', 'core', 'ab', '-', '-', 'X', '-'],
                                          ['a', 'image', 'ab', '-', '-', 'Y', '-']))

    def test_jmh_command(self):
        row = bench.parse_manifest(manifest(['a', 'it', 'ab', '-', '12', 'X', 'spec=a,b;t=1']))[0]
        settings = argparse.Namespace(warmup=5, iterations=8, time='1')
        command = bench.jmh_command(['java'], ['-Xmx1g'], 'cp', row, row.cases()[1], settings, 'out.json',
                                    profile_gc=True)
        self.assertEqual(['java', '-Xmx1g', '-cp', 'cp', 'org.openjdk.jmh.Main', 'X', '-p', 'spec=b', '-p', 't=1',
                          '-f', '1', '-wi', '12', '-w', '1s', '-i', '8', '-r', '1s', '-foe', 'true', '-rf', 'json',
                          '-rff', 'out.json', '-prof', 'gc'], command)
        # The row's warm-up is a floor: a longer one asked for on the command line wins.
        settings.warmup = 20
        self.assertIn('20', bench.jmh_command(['java'], [], 'cp', row, [], settings, 'out.json'))

    def test_select_rows(self):
        rows = bench.parse_manifest(manifest(['a', 'core', 'ab', '-', '-', 'X', '-'],
                                             ['b', 'core', 'alloc', '-', '-', 'Y', '-'],
                                             ['c', 'it', 'ab,alloc', '-', '-', 'Z', '-']))
        self.assertEqual(['a', 'c'], [row.id for row in bench.select_rows(rows, 'ab')])
        self.assertEqual(['c'], [row.id for row in bench.select_rows(rows, 'alloc', 'c')])
        with self.assertRaises(bench.BenchError):
            bench.select_rows(rows, 'ab', 'b')

    def test_committed_manifest(self):
        rows = bench.load_manifest()
        self.assertTrue(bench.select_rows(rows, 'ab'))
        alloc = bench.select_rows(rows, 'alloc')
        self.assertTrue(alloc)
        for row in rows:
            self.assertTrue(os.path.isdir(os.path.join(bench.ROOT, row.module, 'src', 'test', 'java')), row.module)
        # The gate runs on pull requests without the oracle tools.
        self.assertFalse([row.id for row in alloc if row.needs])


class ResultsTest(unittest.TestCase):
    def test_units(self):
        self.assertEqual(2.5e6, bench.nanos_per_op(2.5, 'ms/op'))
        self.assertEqual(7.0, bench.nanos_per_op(7, 'ns/op'))
        self.assertEqual(3e3, bench.nanos_per_op(3, 'us/op'))
        self.assertEqual(2e9, bench.nanos_per_op(2, 's/op'))
        self.assertEqual(1e9 / 4, bench.nanos_per_op(4, 'ops/s'))
        self.assertEqual(1e6 / 4, bench.nanos_per_op(4, 'ops/ms'))
        with self.assertRaises(bench.BenchError):
            bench.nanos_per_op(1, 'B/op')

    def test_keys_sort_parameters(self):
        self.assertEqual('a.B.c x=1 y=2', bench.result_key('a.B.c', {'y': '2', 'x': '1'}))
        self.assertEqual('a.B.c', bench.result_key('a.B.c', {}))

    def test_parse(self):
        text = json.dumps([
            jmh_entry('a.B.c', 4.0, params={'n': '1'}),
            jmh_entry('a.B.d', 250.0, unit='ops/s', mode='thrpt'),
        ])
        results = bench.parse_results(text)
        self.assertEqual(4e6, results['a.B.c n=1']['nanos'])
        self.assertEqual(4e6, results['a.B.d']['nanos'])
        self.assertIsNone(results['a.B.c n=1']['alloc'])
        self.assertEqual({}, bench.parse_results(''))

    def test_allocation_metric_with_or_without_prefix(self):
        for name in ('gc.alloc.rate.norm', chr(0xb7) + 'gc.alloc.rate.norm'):
            entry = jmh_entry('a.B.c', 1.0)
            entry['secondaryMetrics'] = {'gc.alloc.rate': {'score': 9.0}, name: {'score': 5.0, 'rawData': [[7, 3, 5]]}}
            self.assertEqual([7, 3, 5], bench.parse_results(json.dumps([entry]))['a.B.c']['alloc'])
        entry = jmh_entry('a.B.c', 1.0)
        entry['secondaryMetrics'] = {'xgc.alloc.rate.norm': {'score': 5.0}}
        self.assertIsNone(bench.parse_results(json.dumps([entry]))['a.B.c']['alloc'])


class CompareTest(unittest.TestCase):
    BASE = [100.0, 101.0, 99.0, 100.5, 99.5]

    def test_same(self):
        result = bench.compare(self.BASE, [100.2, 99.8, 100.9, 99.1, 100.0], 0.2, 1)
        self.assertEqual('same', result['verdict'])
        self.assertLess(result['low'], 1)
        self.assertGreater(result['high'], 1)

    def test_regression(self):
        result = bench.compare(self.BASE, [value * 1.3 for value in self.BASE], 0.2, 1)
        self.assertEqual('regression', result['verdict'])
        self.assertAlmostEqual(1.3, result['ratio'])
        self.assertGreater(result['low'], 1.2)

    def test_slower_below_threshold(self):
        self.assertEqual('slower', bench.compare(self.BASE, [value * 1.1 for value in self.BASE], 0.2, 1)['verdict'])

    def test_improvement_and_faster(self):
        self.assertEqual('improvement',
                         bench.compare(self.BASE, [value / 1.3 for value in self.BASE], 0.2, 1)['verdict'])
        self.assertEqual('faster', bench.compare(self.BASE, [value / 1.1 for value in self.BASE], 0.2, 1)['verdict'])

    def test_noise_hides_a_large_median_change(self):
        # The medians differ by 30%, but the forks overlap so much that the interval holds no change.
        result = bench.compare([60.0, 100.0, 140.0, 70.0, 130.0], [80.0, 130.0, 180.0, 60.0, 150.0], 0.2, 1)
        self.assertEqual('same', result['verdict'])
        self.assertAlmostEqual(1.3, result['ratio'])

    def test_threshold_is_configurable(self):
        slower = [value * 1.17 for value in self.BASE]
        self.assertEqual('slower', bench.compare(self.BASE, slower, 0.2, 1)['verdict'])
        self.assertEqual('regression', bench.compare(self.BASE, slower, 0.15, 1)['verdict'])

    def test_missing_sides(self):
        self.assertEqual('new', bench.compare([], [1.0], 0.2)['verdict'])
        self.assertEqual('removed', bench.compare([1.0], [], 0.2)['verdict'])
        self.assertEqual('failed', bench.compare([], [], 0.2)['verdict'])
        self.assertEqual('n/a', bench.compare([1.0], [2.0], 0.2)['verdict'])

    def test_bootstrap_is_deterministic(self):
        head = [value * 1.05 for value in self.BASE]
        self.assertEqual(bench.bootstrap_ratio(self.BASE, head, 7), bench.bootstrap_ratio(self.BASE, head, 7))
        low, high = bench.bootstrap_ratio(self.BASE, head, 7)
        self.assertLessEqual(low, 1.05)
        self.assertGreaterEqual(high, 1.05)

    def test_median(self):
        self.assertEqual(2, bench.median([3, 1, 2]))
        self.assertEqual(2.5, bench.median([4, 1, 2, 3]))


class AllocJudgeTest(unittest.TestCase):
    def test_verdicts(self):
        self.assertEqual(1306, bench.budget(1000, 0.05, 256))
        self.assertEqual('ok', bench.judge_alloc(1000, 1306, 0.05, 256))
        self.assertEqual('over', bench.judge_alloc(1000, 1307, 0.05, 256))
        self.assertEqual('over', bench.judge_alloc(0, 257, 0.05, 256))
        self.assertEqual('ok', bench.judge_alloc(0, 256, 0.05, 256))
        self.assertEqual('under', bench.judge_alloc(1000, 693, 0.05, 256))
        self.assertEqual('ok', bench.judge_alloc(1000, 694, 0.05, 256))
        self.assertEqual('new', bench.judge_alloc(None, 5, 0.05, 256))
        self.assertEqual('removed', bench.judge_alloc(5, None, 0.05, 256))
        with self.assertRaises(ValueError):
            bench.judge_alloc(None, None, 0.05, 256)


class BaseTest(unittest.TestCase):
    """Chooses bases in a scratch git repository whose commits carry fixed dates."""

    def setUp(self):
        self.root = self.directory = tempfile.mkdtemp(prefix='bench-git-')
        self.init()
        self.old = self.commit('old', '2020-01-01T00:00:00')
        self.week = self.commit('week', '2020-01-02T00:00:00')

    def tearDown(self):
        remove_tree(self.root)

    def init(self):
        self.git('init', '-q', '-b', 'main')
        # A global core.fsmonitor would start a daemon per scratch repository, which keeps git's captured output
        # open; bench.py's own git calls in the repository read this setting too.
        self.git('config', 'core.fsmonitor', 'false')

    def git(self, *arguments, date=None):
        environment = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@example.com',
                           GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@example.com')
        if date:
            environment.update(GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
        completed = subprocess.run(['git', '-c', 'commit.gpgsign=false', '-c', 'core.fsmonitor=false']
                                   + list(arguments), cwd=self.directory, env=environment, stdin=subprocess.DEVNULL,
                                   capture_output=True, text=True, check=True)
        return completed.stdout.strip()

    def commit(self, name, date=None):
        with open(os.path.join(self.directory, name), 'w', encoding='utf-8') as handle:
            handle.write(name)
        self.git('add', name)
        self.git('commit', '-q', '-m', name, date=date)
        return self.git('rev-parse', 'HEAD')

    def test_default_branch_compares_with_a_week_back(self):
        # Fresh commits are younger than a week; the newest older one is the base.
        self.commit('fresh')
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        base, head, reason = bench.choose_base(self.directory, None, 'main')
        self.assertEqual(self.week, base)
        self.assertEqual('last commit at least a week old', reason)

    def test_branch_compares_with_its_merge_base(self):
        self.git('update-ref', 'refs/remotes/origin/main', 'HEAD')
        self.git('checkout', '-q', '-b', 'feature')
        head = self.commit('feature')
        self.assertEqual((self.week, head, 'merge base with main'), bench.choose_base(self.directory, None, 'main'))

    def test_pull_request_merge_compares_with_its_first_parent(self):
        self.git('checkout', '-q', '-b', 'feature')
        self.commit('feature')
        self.git('checkout', '-q', 'main')
        tip = self.commit('main-moved-on')
        self.git('merge', '-q', '--no-ff', '-m', 'merge', 'feature')
        base, _, reason = bench.choose_base(self.directory, None, 'main', pull_request=True)
        self.assertEqual(tip, base)
        self.assertEqual('the base branch of the pull request', reason)

    def test_explicit_ref(self):
        self.commit('fresh')
        self.assertEqual(self.old, bench.choose_base(self.directory, self.old[:10])[0])
        self.assertEqual(self.git('rev-parse', 'HEAD'), bench.choose_base(self.directory, 'HEAD')[0])
        with self.assertRaises(bench.BenchError):
            bench.choose_base(self.directory, 'no-such-ref')

    def test_no_old_commit(self):
        self.directory = os.path.join(self.directory, 'young')
        os.makedirs(self.directory)
        self.init()
        self.commit('fresh')
        with self.assertRaises(bench.BenchError):
            bench.choose_base(self.directory, None, 'main')

    def base_outputs(self, *argv):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = bench.main(['base', '--repository', self.directory] + list(argv))
        self.assertEqual(0, status)
        return dict(line.split('=', 1) for line in output.getvalue().splitlines())

    def test_command_prints_github_outputs(self):
        outputs = self.base_outputs('--ref', 'HEAD', '--head-name', 'feature')
        self.assertEqual({'base': self.week, 'head': self.week, 'base_label': self.week[:12] + ' (HEAD)',
                          'head_label': self.week[:12] + ' (feature)', 'same': 'true'}, outputs)
        outputs = self.base_outputs('--ref', self.old)
        self.assertEqual((self.old, self.week, 'false'), (outputs['base'], outputs['head'], outputs['same']))
        self.assertEqual(self.week[:12], outputs['head_label'])


class ClasspathTest(unittest.TestCase):
    """Builds class paths in a fake tree whose "mvn" is fake_mvn.py."""

    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix='bench-mvn-')
        for module in ('core', 'it'):
            os.makedirs(os.path.join(self.directory, module))
            with open(os.path.join(self.directory, module, 'pom.xml'), 'w', encoding='utf-8') as handle:
                handle.write('<project/>')
        self.environment = dict(os.environ)
        self.log = os.path.join(self.directory, 'mvn.log')
        os.environ['FAKE_MVN_LOG'] = self.log

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.environment)
        shutil.rmtree(self.directory, ignore_errors=True)

    def run_classpath(self, *argv):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return bench.main(['classpath', '--tree', self.directory, '--maven', FAKE_MAVEN] + list(argv))

    def calls(self):
        if not os.path.isfile(self.log):
            return []
        with open(self.log, encoding='utf-8') as handle:
            return [json.loads(line) for line in handle]

    def test_builds_without_tests_and_lists_the_test_class_path(self):
        self.assertEqual(0, self.run_classpath('--modules', 'core,it', '--offline', '--maven-args=-T 1C'))
        [call] = self.calls()
        self.assertTrue(os.path.samefile(self.directory, call[0]))
        self.assertEqual(['-B', '-ntp', '-q', '-DskipTests', '-pl', 'core,it', '-am', '-o', '-T', '1C', 'package',
                          bench.DEPENDENCY_PLUGIN + ':build-classpath', '-Dmdep.includeScope=test',
                          '-Dmdep.outputFile=' + bench.CLASSPATH_FILE], call[1:])
        module = os.path.join(self.directory, 'it')
        entries = bench.classpath(self.directory, 'it').split(os.pathsep)
        self.assertEqual([os.path.join(module, 'target', 'test-classes'), os.path.join(module, 'target', 'classes')],
                         entries[:2])
        self.assertTrue(entries[2].endswith('it-dependency.jar'))

    def test_existing_skips_the_modules_a_base_lacks(self):
        self.assertEqual(0, self.run_classpath('--modules', 'core,video', '--existing'))
        self.assertEqual(['core'], [call[call.index('-pl') + 1] for call in self.calls()])
        os.remove(self.log)
        self.assertEqual(0, self.run_classpath('--modules', 'video', '--existing'))
        self.assertEqual([], self.calls())
        with self.assertRaises(bench.BenchError):
            bench.classpath(self.directory, 'video')

    def test_a_failed_build_is_an_infrastructure_error(self):
        os.environ['FAKE_MVN_STATUS'] = '1'
        self.assertEqual(2, self.run_classpath('--modules', 'core'))
        with self.assertRaises(bench.BenchError):
            bench.classpath(self.directory, 'core')

    def test_explicit_commands(self):
        # A path to an executable is taken whole, so that a Windows path keeps its backslashes.
        self.assertEqual([sys.executable], bench.java_command(sys.executable))
        self.assertEqual([sys.executable], bench.maven_command(sys.executable))
        self.assertEqual(['a b', 'c'], bench.java_command('"a b" c'))


class EndToEndTest(unittest.TestCase):
    """Runs the commands against fake trees whose "java" is fake_jmh.py."""

    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix='bench-test-')
        self.environment = dict(os.environ)
        for side in ('base', 'head'):
            target = os.path.join(self.directory, side, 'mod', 'target')
            os.makedirs(target)
            with open(os.path.join(target, 'bench-classpath.txt'), 'w', encoding='utf-8') as handle:
                handle.write(os.path.join(self.directory, side, 'dependency.jar'))
        self.manifest = os.path.join(self.directory, 'benchmarks.tsv')
        with open(self.manifest, 'w', encoding='utf-8') as handle:
            handle.write(manifest(['fake', 'mod', 'ab,alloc', '-', '-', r'\.FakeBenchmark\.', 'n=1,2']))
        self.log = os.path.join(self.directory, 'invocations.log')
        os.environ['FAKE_JMH_LOG'] = self.log
        os.environ.pop('GITHUB_ACTIONS', None)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.environment)
        shutil.rmtree(self.directory, ignore_errors=True)

    def run_bench(self, *argv):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            status = bench.main(list(argv))
        return status, output.getvalue()

    def ab(self, *extra):
        out = os.path.join(self.directory, 'out')
        return self.run_bench('ab', '--manifest', self.manifest, '--java', FAKE_JAVA,
                              '--base', os.path.join(self.directory, 'base'),
                              '--head', os.path.join(self.directory, 'head'), '--out', out, '--forks', '4',
                              '--warmup', '1', '--iterations', '1', '--store', os.path.join(out, 'store.json'),
                              *extra) + (out,)

    def invocations(self):
        with open(self.log, encoding='utf-8') as handle:
            return [line.rstrip('\n').split(TAB) for line in handle]

    def test_ab_interleaves_and_reports_no_change(self):
        status, output, out = self.ab('--fail-on-regression')
        self.assertEqual(0, status)
        sides = [line[0] for line in self.invocations()]
        # Per case: base head, head base, base head, head base.
        self.assertEqual(['base', 'head', 'head', 'base'] * 4, sides)
        self.assertEqual(['n=1'] * 8 + ['n=2'] * 8, [line[1] for line in self.invocations()])
        with open(os.path.join(out, 'ab-report.json'), encoding='utf-8') as handle:
            report = json.load(handle)
        self.assertEqual(['same', 'same'], [entry['verdict'] for entry in report['results']])
        self.assertEqual([4, 4], [entry['head_forks'] for entry in report['results']])
        self.assertIn('2 same', output)
        with open(os.path.join(out, 'store.json'), encoding='utf-8') as handle:
            store = json.load(handle)
        self.assertEqual(['com.example.FakeBenchmark.op n=1', 'com.example.FakeBenchmark.op n=2'],
                         [entry['name'] for entry in store])
        self.assertEqual({'ns/op'}, {entry['unit'] for entry in store})
        self.assertTrue(all(9e6 < entry['value'] < 11e6 for entry in store))

    def test_ab_flags_a_regression(self):
        os.environ['FAKE_JMH_FACTOR'] = '1.5'
        os.environ['GITHUB_ACTIONS'] = 'true'
        status, output, out = self.ab()
        self.assertEqual(0, status)
        self.assertIn('REGRESSION', output)
        self.assertIn('::warning title=Benchmark regression::', output)
        summary = os.path.join(self.directory, 'summary.md')
        status, output = self.run_bench('report', '--out', out, '--fail-on-regression', '--summary', summary)
        self.assertEqual(1, status)
        self.assertIn('::error title=Benchmark regression::', output)
        with open(summary, encoding='utf-8') as handle:
            self.assertIn('2 regression', handle.read())
        # A threshold above the change turns the regressions into slower cases.
        status, output = self.run_bench('report', '--out', out, '--fail-on-regression', '--threshold', '0.6')
        self.assertEqual(0, status)
        self.assertIn('2 slower', output)

    def test_ab_reads_throughput_as_inverse_time(self):
        os.environ['FAKE_JMH_FACTOR'] = '0.7'
        os.environ['FAKE_JMH_MODE'] = 'thrpt'
        status, output, out = self.ab()
        self.assertIn('2 improvement', output)

    def test_ab_base_without_the_module(self):
        os.remove(os.path.join(self.directory, 'base', 'mod', 'target', 'bench-classpath.txt'))
        status, output, out = self.ab('--fail-on-regression')
        self.assertEqual(0, status)
        self.assertEqual({'head'}, {line[0] for line in self.invocations()})
        self.assertIn('2 new', output)
        self.assertIn('the base has no build of mod', output)

    def test_ab_reports_a_failed_case(self):
        os.environ['FAKE_JMH_FAIL'] = '2'
        status, output, out = self.ab()
        self.assertEqual(2, status)
        self.assertIn('Did not run: fake n=2 (base, 4 of 4 forks, ', output)
        self.assertIn('fake n=2 (head, 4 of 4 forks, ', output)
        self.assertIn('1 same', output)

    def test_ab_ends_a_hung_invocation_and_its_children(self):
        mark = os.path.join(self.directory, 'mark')
        os.environ['FAKE_JMH_HANG'] = '2'
        os.environ['FAKE_JMH_MARK'] = mark
        started = time.time()
        status, output, out = self.ab('--forks', '1', '--timeout', '1')
        self.assertLess(time.time() - started, 60)
        self.assertEqual(2, status)
        self.assertIn('Did not run: fake n=2 (base, 1 of 1 forks, ', output)
        self.assertIn('fake n=2 (head, 1 of 1 forks, ', output)
        with open(os.path.join(out, 'head', 'fake', '001-f00.log'), encoding='utf-8') as handle:
            self.assertIn('timed out after 1 s', handle.read())
        # The children of the killed invocations would write the mark 3 s after they started.
        time.sleep(4)
        self.assertFalse(os.path.exists(mark))

    def test_report_needs_a_finished_run(self):
        status, _ = self.run_bench('report', '--out', os.path.join(self.directory, 'nothing'))
        self.assertEqual(2, status)

    def test_ab_names_the_side_that_failed(self):
        os.environ['FAKE_JMH_FAIL'] = '2'
        os.environ['FAKE_JMH_FAIL_SIDE'] = 'head'
        os.environ['GITHUB_ACTIONS'] = 'true'
        status, output, out = self.ab()
        self.assertEqual(2, status)
        self.assertIn('| failed (head) |', output)
        self.assertIn('::error title=Benchmark failed::fake n=2 did not run on the head tree in 4 forks', output)
        # A failing base is a warning: the head is not to blame.
        os.environ['FAKE_JMH_FAIL_SIDE'] = 'base'
        os.remove(self.log)
        status, output, out = self.ab('--fail-on-regression')
        self.assertEqual(0, status)
        self.assertIn('| failed (base) |', output)
        self.assertIn('::warning title=Benchmark failed::', output)

    def alloc(self, *extra, allocations=None):
        if allocations is not None:
            os.environ['FAKE_JMH_ALLOC'] = json.dumps(allocations)
        out = os.path.join(self.directory, 'alloc-out')
        status, output = self.run_bench('alloc', '--manifest', self.manifest, '--java', FAKE_JAVA,
                                        '--base', os.path.join(self.directory, 'base'),
                                        '--tree', os.path.join(self.directory, 'head'), '--out', out,
                                        '--warmup', '1', '--iterations', '3', '--rel', '0.05', '--abs', '256', *extra)
        report = None
        if os.path.isfile(os.path.join(out, 'alloc-report.json')):
            with open(os.path.join(out, 'alloc-report.json'), encoding='utf-8') as handle:
                report = json.load(handle)
        return status, output, report

    @staticmethod
    def verdicts(report):
        return {result['key'].rsplit(' ', 1)[1]: result['verdict'] for result in report['results']}

    def test_alloc_within_budget(self):
        status, output, report = self.alloc(allocations={'base:1': [1000], 'head:1': [1300], 'base:2': [5000],
                                                         'head:2': [4000]})
        self.assertEqual(0, status, output)
        self.assertEqual({'n=1': 'ok', 'n=2': 'under'}, self.verdicts(report))
        # The least measured iteration is the value, and every invocation ran the gc profiler, base first.
        self.assertEqual([1000, 1300], [report['results'][0]['base'], report['results'][0]['head']])
        self.assertEqual([['base', 'n=1', 'gc'], ['head', 'n=1', 'gc'], ['base', 'n=2', 'gc'],
                          ['head', 'n=2', 'gc']], self.invocations())
        self.assertIn('| 1,000 | 1,300 | +300 (+30.0%) | 1,306 | ok |', output)
        self.assertIn('budget: base * 1.05 + 256 B/op', output)

    def test_alloc_over_budget_is_confirmed_then_fails(self):
        os.environ['GITHUB_ACTIONS'] = 'true'
        status, output, report = self.alloc(allocations={'base:1': [1000, 1100], 'head:1': [2000, 1900],
                                                         'base:2': [5000], 'head:2': [5000]})
        self.assertEqual(1, status)
        self.assertEqual({'n=1': 'over', 'n=2': 'ok'}, self.verdicts(report))
        # The head's least against the base's largest measurement.
        self.assertEqual([1100, 1900], [report['results'][0]['base'], report['results'][0]['head']])
        self.assertIn('| OVER |', output)
        self.assertIn('allocation-accepted', output)
        self.assertIn('::error title=Allocation over budget::', output)
        self.assertEqual(['n=1'] * 4 + ['n=2'] * 2, [line[1] for line in self.invocations()])

    def test_alloc_confirmation_clears_a_spike(self):
        status, output, report = self.alloc(allocations={'base:*': [1000, 1000], 'head:1': [9000, 1000],
                                                         'head:2': [1000]})
        self.assertEqual(0, status, output)
        self.assertEqual({'n=1': 'ok', 'n=2': 'ok'}, self.verdicts(report))

    def test_alloc_accept_reports_without_failing(self):
        os.environ['GITHUB_ACTIONS'] = 'true'
        status, output, report = self.alloc('--accept', allocations={'base:*': [1000], 'head:*': [5000]})
        self.assertEqual(0, status)
        self.assertEqual({'n=1': 'accepted', 'n=2': 'accepted'}, self.verdicts(report))
        self.assertIn('::warning title=Allocation over budget::', output)

    def test_alloc_tolerance_is_configurable(self):
        allocations = {'base:*': [1000], 'head:*': [1400]}
        self.assertEqual(1, self.alloc(allocations=allocations)[0])
        self.assertEqual(0, self.alloc('--rel', '0.5', '--abs', '0', allocations=allocations)[0])

    def test_alloc_side_options(self):
        status, output, report = self.alloc('--head-args=-Dx=1')
        self.assertEqual(0, status, output)
        self.assertEqual('-Dx=1', report['head_args'])
        self.assertIn('with `-Dx=1`', output)

    def test_alloc_base_without_the_module(self):
        os.remove(os.path.join(self.directory, 'base', 'mod', 'target', 'bench-classpath.txt'))
        status, output, report = self.alloc()
        self.assertEqual(0, status, output)
        self.assertEqual({'n=1': 'new', 'n=2': 'new'}, self.verdicts(report))
        self.assertIn('the base has no build of mod', output)

    def test_alloc_head_failure_is_an_infrastructure_error(self):
        os.environ['FAKE_JMH_FAIL'] = '1'
        os.environ['FAKE_JMH_FAIL_SIDE'] = 'head'
        status, output, report = self.alloc()
        self.assertEqual(2, status)
        self.assertEqual({'n=1': 'failed', 'n=2': 'ok'}, self.verdicts(report))
        self.assertIn('| failed (head) |', output)
        os.environ['FAKE_JMH_FAIL_SIDE'] = 'base'
        status, output, report = self.alloc()
        self.assertEqual(0, status)
        self.assertIn('| failed (base) |', output)

    def test_plan(self):
        status, output = self.run_bench('plan', '--manifest', self.manifest, '--set', 'alloc')
        self.assertEqual(0, status)
        self.assertEqual(['modules=mod', 'oracles=false', 'cases=2'], output.split())


if __name__ == '__main__':
    unittest.main()
