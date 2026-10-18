"""Checks the comment rules of CLAUDE.md (Documentation, section 11) that a script can check.

Usage:
  python tools/lint/doclint.py                        check src/main/java against the baseline
  python tools/lint/doclint.py --javadoc-log FILE     also gate the doclint warnings of a javadoc run
  python tools/lint/doclint.py --list [RULE]          print every finding (of one rule), ignoring the baseline
  python tools/lint/doclint.py --update               rewrite the baseline after fixing findings (shrink only)
  python tools/lint/doclint.py --shrink-only OLD      fail if the baseline grew from the baseline file OLD

The baseline (tools/lint/baseline.tsv) holds the accepted number of findings per rule and file. A check fails
when a file has more findings of a rule than the baseline allows, and prints them; it reports the entries that
can shrink. --update writes the current counts but refuses to raise any count, so the baseline only shrinks:
fix the new finding instead. --init (first run only) writes the baseline whatever it holds.

Rules on comments (javadoc, block and line comments) of every */src/main/java file:
  param-restates-name  "@param x the x"
  return-restates      a "Returns X." summary followed by "@return X"
  non-ascii            a character outside ASCII
  long-line            a comment line longer than 120 characters
  implnote-start       an @implNote that does not start "This implementation"
  history              "started as", "now handles", "originally", and "previously" as a sentence adverb
                       ("previously," or "previously it/this/the code/they/we"; not "a previously decoded frame")
  latin-abbreviation   "e.g." or "i.e."
  pre-tag              <pre>
  dangling-doc         a doc comment that documents nothing (followed by another doc comment)
  ports-header         a package named in tools/notices that has no file with a "/* Ports" header
With --javadoc-log, the warnings and errors of the javadoc-check profile's doclint-all execution (doclint
"all" on the exported API) count as rules javadoc-missing (a missing comment or tag) and javadoc-other.
"""
import argparse
import collections
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
BASELINE = os.path.join(HERE, 'baseline.tsv')
MODULES = ['core', 'image', 'audio', 'video', 'mpeg', 'capture', 'raw', 'desktop', 'aot']
SOURCES = os.path.join('src', 'main', 'java')
BASE_PATH = os.path.join(*'com.github.auties00.smalt'.split('.'))
LIMIT = 120

PARAM_RESTATES = re.compile(r'@param\s+(<?\w+>?)\s+the\s+(\w+)\s*$')
HISTORY = re.compile(r'\b(started as|now handles|originally|previously(?=,| (?:it|this|the code|they|we)\b))', re.I)
LATIN = re.compile(r'\b(e\.g\.|i\.e\.)', re.I)
PRE = re.compile(r'<pre\b', re.I)
TAG = re.compile(r'^@(\w+)\s*(.*)$')

Finding = collections.namedtuple('Finding', 'rule path line message')


class Comment:
    """One comment: its kind ('doc', 'block' or 'line'), first line, text lines, and the source after it."""

    def __init__(self, kind, line, text, end):
        self.kind = kind
        self.line = line
        self.text = text
        self.end = end


def comments(source):
    """Scans Java source for comments, skipping string, character and text-block literals."""
    found = []
    i, n, line = 0, len(source), 1
    while i < n:
        c = source[i]
        if c == '\n':
            line += 1
            i += 1
        elif source.startswith('"""', i):
            j = source.find('"""', i + 3)
            while j != -1 and source[j - 1] == '\\' and source[j - 2] != '\\':
                j = source.find('"""', j + 1)
            j = n if j == -1 else j + 3
            line += source.count('\n', i, j)
            i = j
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and source[j] != c and source[j] != '\n':
                j += 2 if source[j] == '\\' else 1
            i = j + 1
        elif source.startswith('//', i):
            j = source.find('\n', i)
            j = n if j == -1 else j
            found.append(Comment('line', line, source[i + 2:j], j))
            i = j
        elif source.startswith('/*', i):
            j = source.find('*/', i + 2)
            j = n if j == -1 else j + 2
            doc = source.startswith('/**', i) and not source.startswith('/**/', i)
            found.append(Comment('doc' if doc else 'block', line, source[i:j], j))
            line += source.count('\n', i, j)
            i = j
        else:
            i += 1
    return found


def doc_lines(text):
    """Returns the content lines of a doc comment, without the delimiters and leading asterisks."""
    body = text[3:-2] if text.endswith('*/') else text[3:]
    out = []
    for raw in body.split('\n'):
        stripped = raw.strip()
        if stripped.startswith('*'):
            stripped = stripped[1:]
            if stripped.startswith(' '):
                stripped = stripped[1:]
        out.append(stripped.rstrip())
    return out


def blocks(lines):
    """Splits doc comment lines into the main description and its block tags, each as joined text."""
    main, tags, current = [], [], None
    for text in lines:
        m = TAG.match(text.strip())
        if m:
            current = [m.group(1), m.group(2)]
            tags.append(current)
        elif current is not None:
            current[1] += ' ' + text.strip()
        else:
            main.append(text.strip())
    return ' '.join(t for t in main if t).strip(), [(name, ' '.join(value.split())) for name, value in tags]


def normalize(text):
    text = re.sub(r'\{@(?:code|link|linkplain)\s+([^}]*)\}', r'\1', text)
    return ' '.join(text.lower().rstrip('.').split())


def check_doc(path, comment, report):
    lines = doc_lines(comment.text)
    for offset, text in enumerate(lines):
        m = PARAM_RESTATES.search(text)
        if m and m.group(1).strip('<>') == m.group(2):
            report('param-restates-name', comment.line + offset, text.strip())
    main, tags = blocks(lines)
    summary = re.match(r'Returns\s+(.+?)\.(?:\s|$)', main)
    if summary:
        for name, value in tags:
            if name == 'return' and normalize(value) == normalize(summary.group(1)):
                report('return-restates', comment.line, 'Returns %s. / @return %s' % (summary.group(1), value))
    for offset, text in enumerate(lines):
        if text.startswith('@implNote') and not text[len('@implNote'):].strip().startswith('This implementation'):
            report('implnote-start', comment.line + offset, text.strip())
    rest = SOURCE_CACHE[path][comment.end:]
    if re.match(r'\s*/\*\*', rest):
        report('dangling-doc', comment.line, 'doc comment followed by another doc comment')


SOURCE_CACHE = {}


def check_file(path, rel, findings):
    with open(path, encoding='utf-8', newline='') as f:
        source = f.read().replace('\r\n', '\n')
    SOURCE_CACHE[path] = source
    lines = source.split('\n')

    def report(rule, line, message):
        findings.append(Finding(rule, rel, line, message))

    comment_lines = set()
    for comment in comments(source):
        span = comment.text.count('\n')
        for k in range(comment.line, comment.line + span + 1):
            comment_lines.add(k)
        for offset, text in enumerate(comment.text.split('\n')):
            if any(ord(ch) > 127 for ch in text):
                report('non-ascii', comment.line + offset, text.strip())
            if HISTORY.search(text):
                report('history', comment.line + offset, text.strip())
            if LATIN.search(text):
                report('latin-abbreviation', comment.line + offset, text.strip())
            if PRE.search(text):
                report('pre-tag', comment.line + offset, text.strip())
        if comment.kind == 'doc':
            check_doc(path, comment, report)
    for number in sorted(comment_lines):
        if number <= len(lines) and len(lines[number - 1]) > LIMIT:
            report('long-line', number, '%d characters' % len(lines[number - 1]))
    del SOURCE_CACHE[path]
    return source


def notices_packages():
    path = os.path.join(ROOT, 'tools', 'notices', 'THIRD-PARTY-NOTICES')
    if not os.path.exists(path):
        return set()
    with open(path, encoding='utf-8') as f:
        text = f.read().replace('\r\n', '\n')
    found = set()
    for m in re.finditer(r'Derived Smalt packages:\n(.*?)(?:\n\n|\Z)', text, re.S):
        found.update(re.findall(r'^\s+([a-z][a-z0-9]*(?:\.[a-z0-9]+)*)', m.group(1), re.M))
    return found


def scan():
    findings = []
    headers = collections.defaultdict(bool)
    package_dirs = {}
    for module in MODULES:
        top = os.path.join(ROOT, module, SOURCES)
        base = os.path.join(top, BASE_PATH)
        for directory, dirs, files in os.walk(top):
            dirs.sort()
            for name in sorted(files):
                if not name.endswith('.java'):
                    continue
                path = os.path.join(directory, name)
                rel = os.path.relpath(path, ROOT).replace(os.sep, '/')
                source = check_file(path, rel, findings)
                if directory.startswith(base):
                    package = os.path.relpath(directory, base).replace(os.sep, '.')
                    package = '' if package == '.' else package
                    package_dirs[package] = os.path.relpath(directory, ROOT).replace(os.sep, '/')
                    if re.match(r'\s*/\*\s*Ports\b', source):
                        headers[package] = True
    for package in sorted(notices_packages()):
        if package in package_dirs and not headers[package]:
            findings.append(Finding('ports-header', package_dirs[package], 0,
                                    'no file of %s has a "/* Ports" header' % package))
    return findings


LOG_LINE = re.compile(r'^(?:\[(?:WARNING|ERROR)\]\s+)?(.+?\.java):(\d+): (warning|error): (.*)$')


def javadoc_findings(log):
    findings = []
    seen = set()
    root = os.path.normcase(os.path.abspath(ROOT))
    with open(log, encoding='utf-8', errors='replace') as f:
        for raw in f:
            if 'Skipping javadoc generation, everything is up to date' in raw:
                sys.exit('%s: the javadoc plugin skipped a module as up to date, so its warnings are missing; '
                         'run the javadoc-check profile again (it drops the plugin stale data)' % log)
            m = LOG_LINE.match(raw.strip())
            if not m:
                continue
            path = os.path.abspath(m.group(1))
            if not os.path.normcase(path).startswith(root):
                continue
            rel = os.path.relpath(path, ROOT).replace(os.sep, '/')
            key = (rel, m.group(2), m.group(4))
            if key in seen:
                continue
            seen.add(key)
            message = m.group(4)
            missing = re.match(r'no (comment|@param|@return|@throws|main description|summary)', message)
            rule = 'javadoc-missing' if missing else 'javadoc-other'
            findings.append(Finding(rule, rel, int(m.group(2)), message))
    return findings


def read_baseline(path):
    counts = {}
    if os.path.exists(path):
        with open(path, encoding='utf-8') as f:
            for line in f:
                if line.startswith('#') or not line.strip():
                    continue
                rule, rel, count = line.rstrip('\n').split('\t')
                counts[(rule, rel)] = int(count)
    return counts


def write_baseline(path, counts):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Accepted comment-lint findings per rule and file (tools/lint/doclint.py). Only ever shrinks.\n')
        for (rule, rel) in sorted(counts):
            if counts[(rule, rel)]:
                f.write('%s\t%s\t%d\n' % (rule, rel, counts[(rule, rel)]))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--javadoc-log', action='append', default=[])
    parser.add_argument('--list', nargs='?', const='', default=None)
    parser.add_argument('--update', action='store_true')
    parser.add_argument('--init', action='store_true')
    parser.add_argument('--shrink-only', metavar='OLD')
    args = parser.parse_args()

    if args.shrink_only:
        old, new = read_baseline(args.shrink_only), read_baseline(BASELINE)
        grown = [(k, old.get(k, 0), v) for k, v in sorted(new.items()) if v > old.get(k, 0)]
        for (rule, rel), before, after in grown:
            print('%s\t%s\t%d -> %d' % (rule, rel, before, after))
        if grown:
            print('The lint baseline grew; fix the new findings instead of accepting them.', file=sys.stderr)
            return 1
        return 0

    findings = scan()
    for log in args.javadoc_log:
        findings += javadoc_findings(log)
    if args.list is not None:
        for f in findings:
            if not args.list or f.rule == args.list:
                print('%s:%d: %s: %s' % (f.path, f.line, f.rule, f.message))
        return 0

    counts = collections.Counter((f.rule, f.path) for f in findings)
    baseline = read_baseline(BASELINE)
    if not args.javadoc_log:
        baseline = {k: v for k, v in baseline.items() if not k[0].startswith('javadoc-')}
    if args.init:
        merged = dict(read_baseline(BASELINE))
        merged.update(counts)
        write_baseline(BASELINE, merged)
        print('wrote %s: %d findings in %d entries' % (BASELINE, sum(counts.values()), len(counts)))
        return 0

    over = {k: v for k, v in counts.items() if v > baseline.get(k, 0)}
    shrinkable = {k: v for k, v in baseline.items() if counts.get(k, 0) < v}
    for f in findings:
        if (f.rule, f.path) in over:
            print('%s:%d: %s: %s' % (f.path, f.line, f.rule, f.message))
    if args.update:
        if over:
            print('Not updating: %d entries would grow; fix the findings above.' % len(over), file=sys.stderr)
            return 1
        merged = dict(read_baseline(BASELINE))
        for key in list(merged):
            if key in baseline:
                merged[key] = counts.get(key, 0)
        write_baseline(BASELINE, merged)
        print('baseline updated: %d entries shrank' % len(shrinkable))
        return 0
    if over:
        print('%d files have more comment-lint findings than tools/lint/baseline.tsv allows (rule, file: found/'
              'allowed):' % len(over), file=sys.stderr)
        for (rule, rel), v in sorted(over.items()):
            print('  %s, %s: %d/%d' % (rule, rel, v, baseline.get((rule, rel), 0)), file=sys.stderr)
        return 1
    if shrinkable:
        print('%d baseline entries can shrink: run python tools/lint/doclint.py --update%s and commit the baseline.'
              % (len(shrinkable), ' --javadoc-log <log>' if args.javadoc_log else ''))
    print('comment lint: %d findings, none beyond the baseline' % sum(counts.values()))
    return 0


if __name__ == '__main__':
    sys.exit(main())
