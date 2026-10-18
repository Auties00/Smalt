"""Counts the skipped tests of a build by reason id and gates them with the skip baseline.

Usage:
  python tools/testdata/skips.py report --job JOB [--out FILE]   count the skips of the surefire reports
  python tools/testdata/skips.py check --job JOB                 fail if a skip count grew over the baseline
  python tools/testdata/skips.py update --job JOB                lower the baseline to the current counts
  python tools/testdata/skips.py accept --job JOB [--environment]
                                                                 raise the baseline to the current counts
  python tools/testdata/skips.py shrink-only OLD                 fail if an environment count grew from OLD

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
or device is fixed in the workflow, not accepted. shrink-only, for pull requests, fails when the committed
baseline raises an environment count over the baseline of the base branch.

The families match the test kit's Skip.Family (core's SkipTest checks it):
"""
import argparse
import collections
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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['report', 'check', 'update', 'accept', 'shrink-only'])
    parser.add_argument('old', nargs='?', help='the base baseline, for shrink-only')
    parser.add_argument('--job')
    parser.add_argument('--root', default=ROOT, help='the reactor root holding the module directories')
    parser.add_argument('--baseline', default=BASELINE)
    parser.add_argument('--report', help='read the counts of a report file instead of the surefire reports')
    parser.add_argument('--out', help='report: the file to write (default: target/skips-<job>.tsv)')
    parser.add_argument('--environment', action='store_true', help='accept: also raise environment families')
    args = parser.parse_args()

    if args.command == 'shrink-only':
        if not args.old:
            parser.error('shrink-only needs the base baseline')
        old, new = read(args.old), read(args.baseline)
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
        return 1 if grown or unclassified else 0

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

    refused = [k for k in grown if environment(k.reason) and not args.environment]
    for key in refused:
        print('refusing to raise the environment skip %s %s %s: %d -> %d (provision it, or pass --environment)'
              % (key.module, key.cls, key.reason, in_scope.get(key, 0), counts[key]))
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
