#!/usr/bin/env python3
"""Summarizes a JaCoCo XML report as a map of what Smalt's tests do not exercise.

The report of `mvn -Pcoverage verify` (it/target/site/jacoco-aggregate/jacoco.xml holds every module) is grouped
by area: the package path below com/github/auties00/smalt, without its internal parts, cut to two segments
(video/h264, container/mp4, image/jpeg, audio/opus, ...). For each area the summary gives the line and branch
coverage and the methods with the most missed branches, which are the spec features no vector or test reaches.

Usage:
  coverage.py REPORT [--markdown FILE] [--tsv FILE] [--top N] [--check BASELINE] [--update BASELINE]

  --markdown FILE   writes the summary as Markdown (default: standard output)
  --tsv FILE        writes one line per area: area, lines covered, lines, branches covered, branches
  --top N           methods listed per area (default 5)
  --check BASELINE  fails when an area's branch coverage fell more than two points below the baseline, or the
                    report has no coverage of an area the baseline lists
  --update BASELINE raises the baseline to this report's coverage, never lowering an area
"""
import argparse
import sys
import xml.etree.ElementTree as ElementTree

PREFIX = 'com/github/auties00/smalt/'
TOLERANCE = 2.0


def area_of(package):
    if not package.startswith(PREFIX):
        return package
    parts = [part for part in package[len(PREFIX):].split('/') if part]
    kept = []
    for part in parts:
        if part == 'internal':
            break
        kept.append(part)
    if not kept:
        kept = parts[:2]
    return '/'.join(kept[:2]) if kept else '(root)'


def counter(element, kind):
    for child in element.findall('counter'):
        if child.get('type') == kind:
            missed = int(child.get('missed'))
            covered = int(child.get('covered'))
            return covered, missed + covered
    return 0, 0


def summarize(report):
    root = ElementTree.parse(report).getroot()
    areas = {}
    for package in root.iter('package'):
        area = areas.setdefault(area_of(package.get('name')), {'lines': [0, 0], 'branches': [0, 0], 'methods': []})
        for kind, key in (('LINE', 'lines'), ('BRANCH', 'branches')):
            covered, total = counter(package, kind)
            area[key][0] += covered
            area[key][1] += total
        for cls in package.findall('class'):
            name = cls.get('name').rsplit('/', 1)[-1]
            for method in cls.findall('method'):
                covered, total = counter(method, 'BRANCH')
                lines_covered, lines_total = counter(method, 'LINE')
                missed = total - covered
                if missed > 0 or (lines_total > 0 and lines_covered == 0):
                    area['methods'].append((missed, lines_total - lines_covered, name + '.' + method.get('name'),
                                            method.get('line') or '?', total))
    return areas


def percent(pair):
    return 100.0 * pair[0] / pair[1] if pair[1] else 100.0


def markdown(areas, top):
    out = ['# Coverage map', '',
           'Areas by missed branches; every listed method is code no test reaches.', '',
           '| Area | Lines | Branches | Missed branches |', '|---|---:|---:|---:|']
    ordered = sorted(areas.items(), key=lambda item: item[1]['branches'][0] - item[1]['branches'][1])
    for name, area in ordered:
        lines, branches = area['lines'], area['branches']
        out.append(f'| {name} | {percent(lines):.1f}% of {lines[1]} | {percent(branches):.1f}% of {branches[1]} | '
                   f'{branches[1] - branches[0]} |')
    out.append('')
    for name, area in ordered:
        methods = sorted(area['methods'], key=lambda method: (-method[0], -method[1]))[:top]
        if not methods:
            continue
        out.append(f'## {name}')
        out.append('')
        for missed, lines, method, line, total in methods:
            out.append(f'- `{method}` (line {line}): {missed} of {total} branches and {lines} lines missed')
        out.append('')
    return '\n'.join(out)


def read_baseline(path):
    baseline = {}
    try:
        with open(path, encoding='utf-8') as source:
            for line in source:
                if line.startswith('#') or not line.strip():
                    continue
                area, value = line.rstrip('\n').split('\t')[:2]
                baseline[area] = float(value)
    except FileNotFoundError:
        pass
    return baseline


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('report')
    parser.add_argument('--markdown')
    parser.add_argument('--tsv')
    parser.add_argument('--top', type=int, default=5)
    parser.add_argument('--check')
    parser.add_argument('--update')
    arguments = parser.parse_args()
    areas = summarize(arguments.report)
    text = markdown(areas, arguments.top)
    if arguments.markdown:
        with open(arguments.markdown, 'w', encoding='utf-8', newline='\n') as out:
            out.write(text + '\n')
    else:
        print(text)
    if arguments.tsv:
        with open(arguments.tsv, 'w', encoding='utf-8', newline='\n') as out:
            out.write('# area\tlines covered\tlines\tbranches covered\tbranches\n')
            for name, area in sorted(areas.items()):
                out.write(f"{name}\t{area['lines'][0]}\t{area['lines'][1]}\t{area['branches'][0]}\t"
                          f"{area['branches'][1]}\n")
    status = 0
    if arguments.check:
        baseline = read_baseline(arguments.check)
        for name, value in sorted(baseline.items()):
            if name not in areas:
                # an empty or partial report must not pass; a renamed area is renamed in the baseline too
                print(f'{name}: no coverage in the report (baseline {value:.1f}%)', file=sys.stderr)
                status = 1
            elif percent(areas[name]['branches']) < value - TOLERANCE:
                print(f'{name}: branch coverage {percent(areas[name]["branches"]):.1f}% fell below the baseline '
                      f'{value:.1f}%', file=sys.stderr)
                status = 1
    if arguments.update:
        baseline = read_baseline(arguments.update)
        for name, area in areas.items():
            baseline[name] = max(baseline.get(name, 0.0), round(percent(area['branches']), 1))
        with open(arguments.update, 'w', encoding='utf-8', newline='\n') as out:
            out.write('# The branch coverage of each area that the nightly coverage job may not fall below by more than\n')
            out.write(f'# {TOLERANCE} points; raised by coverage.py --update, never lowered.\n')
            for name in sorted(baseline):
                out.write(f'{name}\t{baseline[name]:.1f}\n')
    return status


if __name__ == '__main__':
    sys.exit(main())
