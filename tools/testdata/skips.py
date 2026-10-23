"""Counts the skipped tests of a build by reason id and gates them with the skip baseline.

Usage:
  python tools/testdata/skips.py report --job JOB [--out FILE]   count the skips of the surefire reports
  python tools/testdata/skips.py check --job JOB                 fail if a skip count grew over the baseline
  python tools/testdata/skips.py update --job JOB                lower the baseline to the current counts
  python tools/testdata/skips.py accept --job JOB [--environment]
                                                                 raise the baseline to the current counts
  python tools/testdata/skips.py shrink-only OLD                 fail if an environment count grew from OLD
  python tools/testdata/skips.py outcomes --job JOB [--out FILE] write the outcome of every test case of the build
  python tools/testdata/skips.py cover FILE... [--evidence FILE]... [--families F,F] [--impossible FILE]
                                                                 fail if a skipped test passed in no other job

Every skip message of a Smalt test starts with a reason id in brackets, "[family:name] detail" (the test kit's
Skip class, and the disabledReason of JUnit's conditions). The reports are the surefire XML files under
*/target/surefire-reports, so a skip is counted per job, module, test class and reason id; a skip without a
reason id, or with an unknown family, fails every command.

The baseline, tools/testdata/skip-baseline.tsv, holds the accepted count per job, module, class and reason. A job
names the operating system, the architecture, the SIMD leg and the data tier of a CI job, for example
linux-x64-vector-smoke; CI passes it. check fails when any count is above the baseline, and lists the rows that
can shrink; it compares only the modules that have reports, so a build of some modules checks those. update
writes the lower counts and never raises one. accept also raises counts, for skips that a change adds on purpose
(new tests whose data is outside the tier, new kernels the scalar leg skips), but it refuses to raise the
environment families (oracle, device, env), which CI provisions, unless --environment is given: a missing oracle
or device is fixed in the workflow, not accepted. A reason of NEVER_ACCEPTED (oracle:ffmpeg-feature: every
runner's ffmpeg has every component of tools/testdata/ffmpeg-oracle/features.tsv) is refused even with
--environment, and check fails while the baseline holds one. shrink-only, for pull requests, fails when the
committed baseline raises an environment count over the baseline of the base branch.

A skip is acceptable only when the same test runs and passes on another CI job: the scalar twin of a vector kernel,
an operating system's own tests on that system, a device test on the job that provisions the device. outcomes
writes, for the job, one row per test case (module, test class, case, status, reason id), and cover reads the
outcome files of every job of a workflow run and fails when a test skipped with a reason of the checked families
(simd and platform unless --families names others) passed in none of the other jobs. --evidence adds the outcome
files of another run (the nightly's, for tests that only run there) as passes, without checking their skips. A case
matches a passed case of the same module and test class with the same name, or, for a skip reported on a whole
method or class (an assumption in a parameter source, a disabled class), any passed case of that method or class.
--impossible names tools/testdata/impossible-skips.tsv, the skips no hosted runner can run (a device no runner can
provision): reason id, job glob, test class glob and the reason, one per row; cover accepts the skips that match a
row, and the skips of a test that another job enabled and a matching row then stopped (an opt-in device test that
the capture job runs and that finds no camera there), and lists them.

The families match the test kit's Skip.Family (core's SkipTest checks it):
"""
import argparse
import collections
import fnmatch
import glob
import os
import re
import sys
import xml.etree.ElementTree as ElementTree

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
BASELINE = os.path.join(HERE, 'skip-baseline.tsv')

# family id -> whether it is an environment family (CI provisions it, so its counts only shrink)
FAMILIES = {
    'oracle': True,
    'device': True,
    'env': True,
    'data': False,
    'simd': False,
    'platform': False,
    'opt-in': False,
    'input': False,
}

# Reasons the baseline may never hold, whatever the job: CI provisions what they lack on every runner.
NEVER_ACCEPTED = {'oracle:ffmpeg-feature'}

# At the start of the message, or after the " ==> " with which JUnit appends a condition's disabledReason.
REASON = re.compile(r'(?:^|.*? ==> )\[([a-z-]+):([a-z0-9][a-z0-9._+-]*)\] ')
UNCLASSIFIED = 'unclassified'
EXCEPTION_PREFIX = re.compile(r'^[\w.$]+(?:Exception|Error): ')

__doc__ += ''.join('  %-9s %s\n' % (family, 'environment' if environment else 'structural')
                   for family, environment in FAMILIES.items())

Key = collections.namedtuple('Key', 'job module cls reason')


def reason_of(message):
    """Returns the reason id of a skip message, or None."""
    match = REASON.match(message or '')
    if not match or match.group(1) not in FAMILIES:
        return None
    return match.group(1) + ':' + match.group(2)


def skip_message(skipped):
    """Returns the message of a skipped element: its message attribute, or the first line of the reported
    exception without the exception class."""
    message = skipped.get('message')
    if message:
        return message
    first = (skipped.text or '').strip().splitlines()[0].strip() if (skipped.text or '').strip() else ''
    return EXCEPTION_PREFIX.sub('', first, count=1)


def scan(root, job):
    """Counts the skipped test cases of every surefire report under root.

    Returns (counts, unclassified, modules): counts maps Key to a count, unclassified lists
    (module, class, test, message) of skips without a valid reason id, modules the modules with reports. The
    class is the top-level test class of the report, which also counts the skips of its nested classes.
    """
    counts = collections.Counter()
    unclassified = []
    modules = set()
    pattern = os.path.join(root, '*', 'target', 'surefire-reports', 'TEST-*.xml')
    for path in sorted(glob.glob(pattern)):
        module = os.path.relpath(path, root).replace('\\', '/').split('/')[0]
        modules.add(module)
        try:
            tree = ElementTree.parse(path)
        except ElementTree.ParseError as error:
            raise SystemExit('cannot parse %s: %s' % (path, error))
        suite = tree.getroot().get('name') or os.path.basename(path)[5:-4]
        for case in tree.iter('testcase'):
            skipped = case.find('skipped')
            if skipped is None:
                continue
            message = skip_message(skipped)
            cls = suite
            reason = reason_of(message)
            if reason is None:
                unclassified.append((module, cls, case.get('name') or '', message))
                reason = UNCLASSIFIED
            counts[Key(job, module, cls, reason)] += 1
    return counts, unclassified, modules


def read(path):
    rows = collections.OrderedDict()
    if not os.path.isfile(path):
        return rows
    with open(path, encoding='utf-8') as file:
        for line in file:
            line = line.rstrip('\r\n')
            if not line or line.startswith('#'):
                continue
            job, module, cls, reason, count = line.split('\t')
            rows[Key(job, module, cls, reason)] = int(count)
    return rows


def write(path, rows):
    with open(path, 'w', encoding='utf-8', newline='\n') as file:
        file.write('# Accepted skipped tests per CI job, module, test class and reason id (tools/testdata/skips.py).\n')
        file.write('# job\tmodule\tclass\treason\tcount\n')
        for key in sorted(rows):
            if rows[key] > 0:
                file.write('%s\t%s\t%s\t%s\t%d\n' % (key.job, key.module, key.cls, key.reason, rows[key]))


def family(reason):
    return reason.split(':', 1)[0]


def environment(reason):
    return FAMILIES.get(family(reason), True)


def report_unclassified(unclassified):
    for module, cls, name, message in unclassified[:50]:
        print('unclassified skip: %s %s.%s: %s' % (module, cls, name, message))
    if len(unclassified) > 50:
        print('... and %d more' % (len(unclassified) - 50))
    if unclassified:
        print('every skip must carry a reason id: skip through the test kit\'s Skip ("[family:name] detail")')


def current(args):
    if args.report:
        rows = read(args.report)
        counts = collections.Counter({k: v for k, v in rows.items() if k.job == args.job})
        return counts, [], {k.module for k in counts}
    counts, unclassified, modules = scan(args.root, args.job)
    return counts, unclassified, modules


def summarize(counts):
    by_family = collections.Counter()
    for key, count in counts.items():
        by_family[family(key.reason)] += count
    return ', '.join('%s %d' % (name, by_family[name]) for name in sorted(by_family)) or 'none'


Outcome = collections.namedtuple('Outcome', 'job module cls case name status reason')
OUTCOME_COLUMNS = Outcome._fields


def outcomes(root, job):
    """Returns the Outcome of every test case of the surefire reports under root."""
    rows = []
    pattern = os.path.join(root, '*', 'target', 'surefire-reports', 'TEST-*.xml')
    for path in sorted(glob.glob(pattern)):
        module = os.path.relpath(path, root).replace('\\', '/').split('/')[0]
        try:
            tree = ElementTree.parse(path)
        except ElementTree.ParseError as error:
            raise SystemExit('cannot parse %s: %s' % (path, error))
        suite = tree.getroot().get('name') or os.path.basename(path)[5:-4]
        for case in tree.iter('testcase'):
            name = ' '.join((case.get('name') or '').split())
            classname = case.get('classname') or suite
            skipped = case.find('skipped')
            if skipped is not None:
                status, reason = 'skipped', reason_of(skip_message(skipped)) or UNCLASSIFIED
            elif case.find('failure') is not None or case.find('error') is not None:
                status, reason = 'failed', ''
            else:
                status, reason = 'passed', ''
            rows.append(Outcome(job, module, suite, classname, name, status, reason))
    return rows


def write_outcomes(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as file:
        file.write('# Test outcomes per CI job (tools/testdata/skips.py outcomes).\n')
        file.write('# ' + '\t'.join(OUTCOME_COLUMNS) + '\n')
        for row in rows:
            file.write('\t'.join(value.replace('\t', ' ') for value in row) + '\n')


def read_outcomes(path):
    rows = []
    with open(path, encoding='utf-8') as file:
        for line in file:
            line = line.rstrip('\r\n')
            if line and not line.startswith('#'):
                rows.append(Outcome(*line.split('\t')))
    return rows


def method_of(name):
    """Returns the method part of a case name: without its parameters and invocation index."""
    return re.sub(r'[\[(].*$', '', name).strip()


def read_impossible(path):
    """Returns the rows of an impossible-skips file: (reason, job glob, class glob, why)."""
    rows = []
    with open(path, encoding='utf-8') as file:
        for line in file:
            line = line.rstrip('\r\n')
            if line and not line.startswith('#'):
                reason, job, cls, why = line.split('\t')
                rows.append((reason, job, cls, why))
    return rows


def impossible(row, rows):
    """Returns the why of the impossible-skips row that accepts a skipped Outcome, or None."""
    for reason, job, cls, why in rows:
        if row.reason == reason and fnmatch.fnmatch(row.job, job) and fnmatch.fnmatch(row.cls, cls):
            return why
    return None


def cover(checked, evidence, families):
    """Returns the skipped Outcomes of checked, of the given families, that passed in no other job."""
    passed = collections.defaultdict(set)
    for row in checked + evidence:
        if row.status == 'passed':
            passed[(row.module, row.case)].add((row.job, row.name))
    holes = []
    for row in checked:
        if row.status != 'skipped' or family(row.reason) not in families:
            continue
        others = [name for job, name in passed.get((row.module, row.case), ()) if job != row.job]
        method = method_of(row.name)
        whole = not row.name or row.name == row.case or row.name == row.case.rsplit('.', 1)[-1]
        # an invocation of a parameterized test matches only the same invocation, a skip of a whole method any
        # invocation of it
        if row.name in others or (whole and others) or ('[' not in row.name
                                                        and any(method_of(name) == method for name in others)):
            continue
        # a skip reported on a whole class covers the cases of its nested classes too
        if whole and any(module == row.module and case.startswith(row.case + '$')
                         and any(job != row.job for job, _ in names) for (module, case), names in passed.items()):
            continue
        holes.append(row)
    return holes


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['report', 'check', 'update', 'accept', 'shrink-only', 'outcomes',
                                            'cover'])
    parser.add_argument('old', nargs='*', help='the base baseline, for shrink-only; the outcome files, for cover')
    parser.add_argument('--evidence', action='append', default=[],
                        help='cover: outcome files of another run, read as passes only')
    parser.add_argument('--families', default='simd,platform', help='cover: the reason families to check')
    parser.add_argument('--impossible', help='cover: the file of the skips no hosted runner can run')
    parser.add_argument('--job')
    parser.add_argument('--root', default=ROOT, help='the reactor root holding the module directories')
    parser.add_argument('--baseline', default=BASELINE)
    parser.add_argument('--report', help='read the counts of a report file instead of the surefire reports')
    parser.add_argument('--out', help='report, outcomes: the file to write (default: target/skips-<job>.tsv, '
                                      'target/outcomes-<job>.tsv)')
    parser.add_argument('--environment', action='store_true', help='accept: also raise environment families')
    args = parser.parse_args()

    if args.command == 'cover':
        if not args.old:
            parser.error('cover needs the outcome files')
        checked = [row for path in args.old for row in read_outcomes(path)]
        evidence = [row for path in args.evidence if os.path.isfile(path) for row in read_outcomes(path)]
        families = {name.strip() for name in args.families.split(',') if name.strip()}
        holes = cover(checked, evidence, families)
        accepted = collections.Counter()
        if args.impossible:
            rows = read_impossible(args.impossible)
            # a test another job ran and only an impossible provisioning limit stopped (an opt-in test enabled on
            # the capture job that then finds no camera) is covered as far as the hosted runners allow
            stopped = collections.defaultdict(list)
            for row in checked + evidence:
                if row.status == 'skipped':
                    why = impossible(row, rows)
                    if why is not None:
                        stopped[(row.module, row.case)].append((row.job, row.name, row.reason, why))
            kept = []
            for row in holes:
                why = impossible(row, rows)
                if why is None:
                    method = method_of(row.name)
                    for job, name, reason, other in stopped.get((row.module, row.case), ()):
                        if job != row.job and (name == row.name or method_of(name) == method):
                            why = 'enabled in %s, where %s is impossible: %s' % (job, reason, other)
                            break
                if why is None:
                    kept.append(row)
                else:
                    accepted[(row.reason, row.job, row.cls, why)] += 1
            holes = kept
        for (reason, job, cls, why), count in sorted(accepted.items()):
            print('accepted as impossible: %d %s skips of %s in %s (%s)' % (count, reason, cls, job, why))
        jobs = sorted({row.job for row in checked})
        skipped = sum(1 for row in checked if row.status == 'skipped' and family(row.reason) in families)
        for row in holes[:200]:
            print('skipped and passed in no other job: %s %s %s.%s [%s]'
                  % (row.job, row.module, row.case, row.name, row.reason))
        if len(holes) > 200:
            print('... and %d more' % (len(holes) - 200))
        print('%d jobs (%s), %d skips of %s checked, %d not covered'
              % (len(jobs), ', '.join(jobs), skipped, ', '.join(sorted(families)), len(holes)))
        return 1 if holes else 0

    if args.command == 'shrink-only':
        if not args.old:
            parser.error('shrink-only needs the base baseline')
        old, new = read(args.old[0]), read(args.baseline)
        grown = [(k, old.get(k, 0), v) for k, v in new.items() if environment(k.reason) and v > old.get(k, 0)]
        for key, was, now in grown:
            print('environment skip grew: %s %s %s %s: %d -> %d' % (key.job, key.module, key.cls, key.reason, was, now))
        if grown:
            print('provision the missing oracle, device or environment in the workflow instead of accepting skips')
            return 1
        print('the skip baseline did not grow in an environment family')
        return 0

    if not args.job:
        parser.error('--job is required')
    args.job = args.job.lower()
    if args.command == 'outcomes':
        rows = outcomes(args.root, args.job)
        out = args.out or os.path.join(args.root, 'target', 'outcomes-%s.tsv' % args.job)
        write_outcomes(out, rows)
        print('%d test cases (%d skipped) written to %s'
              % (len(rows), sum(1 for row in rows if row.status == 'skipped'), out))
        return 0
    counts, unclassified, modules = current(args)
    baseline = read(args.baseline)

    if args.command == 'report':
        out = args.out or os.path.join(args.root, 'target', 'skips-%s.tsv' % args.job)
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        write(out, counts)
        print('%d skipped tests in %d modules (%s); written to %s'
              % (sum(counts.values()), len(modules), summarize(counts), out))
        report_unclassified(unclassified)
        return 1 if unclassified else 0

    in_scope = {k: v for k, v in baseline.items() if k.job == args.job and k.module in modules}
    keys = set(counts) | set(in_scope)
    grown = sorted(k for k in keys if counts.get(k, 0) > in_scope.get(k, 0) and k.reason != UNCLASSIFIED)
    shrinkable = sorted(k for k in keys if counts.get(k, 0) < in_scope.get(k, 0))

    if args.command == 'check':
        print('%s: %d skipped tests in %d modules (%s)' % (args.job, sum(counts.values()), len(modules),
                                                            summarize(counts)))
        forbidden = sorted(k for k in baseline if k.reason in NEVER_ACCEPTED)
        for key in forbidden:
            print('the baseline may not hold %s: %s %s %s' % (key.reason, key.job, key.module, key.cls))
        for key in grown:
            print('skips grew: %s %s %s: %d, baseline %d'
                  % (key.module, key.cls, key.reason, counts[key], in_scope.get(key, 0)))
        report_unclassified(unclassified)
        if shrinkable:
            print('%d baseline rows can shrink: run python tools/testdata/skips.py update --job %s and commit the '
                  'baseline' % (len(shrinkable), args.job))
        if grown:
            print('a skip grew: provision what the test needs, or, for a skip a change adds on purpose, run '
                  'python tools/testdata/skips.py accept --job %s and commit the baseline' % args.job)
        return 1 if grown or unclassified or forbidden else 0

    if unclassified:
        report_unclassified(unclassified)
        return 1
    if args.command == 'update':
        changed = 0
        for key in shrinkable:
            baseline[key] = counts.get(key, 0)
            changed += 1
        write(args.baseline, baseline)
        print('skip baseline updated: %d rows shrank' % changed)
        return 0

    refused = [k for k in grown if k.reason in NEVER_ACCEPTED or (environment(k.reason) and not args.environment)]
    for key in refused:
        print('refusing to raise the environment skip %s %s %s: %d -> %d (%s)'
              % (key.module, key.cls, key.reason, in_scope.get(key, 0), counts[key],
                 'provision it' if key.reason in NEVER_ACCEPTED else 'provision it, or pass --environment'))
    for key in keys:
        if key in refused:
            continue
        baseline[key] = counts.get(key, 0)
    write(args.baseline, baseline)
    print('skip baseline of %s accepted: %d rows grew, %d shrank%s'
          % (args.job, len(grown) - len(refused), len(shrinkable),
             ', %d refused' % len(refused) if refused else ''))
    return 1 if refused else 0


if __name__ == '__main__':
    sys.exit(main())
