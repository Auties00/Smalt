"""Rewrites C++ sources written for the pre-standard scope of for-statement variables, guided by compiler errors.

For every "'X' was not declared in this scope" error at line L, the closest for statement before L that declares X
("for (Int X = ...") gets its declaration hoisted in front of it ("Int X; for (X = ..."). A hoisted declaration that
then collides with another one in the same block ("redeclaration of") is turned back into a plain assignment.

Usage: python -I forscope.py <compile command ...> -- <source file>
"""
import re
import subprocess
import sys

NOT_DECLARED = re.compile(r':(\d+):\d+: error: \'(\w+)\' was not declared in this scope')
REDECLARED = re.compile(r':(\d+):\d+: error: redeclaration of \'(?:[\w:<> \*&]+) (\w+)\'')


def main():
    split = sys.argv.index('--')
    command = sys.argv[1:split]
    path = sys.argv[split + 1]
    for _ in range(60):
        r = subprocess.run(command, capture_output=True, text=True)
        if r.returncode == 0:
            return 0
        lines = open(path, encoding='latin-1').read().split('\n')
        changed = False
        for m in REDECLARED.finditer(r.stderr):
            line, name = int(m.group(1)) - 1, m.group(2)
            text = lines[line]
            new = re.sub(r'\b(?:Int|UInt|Bool|CoordI|Double|Float|Long|Void\s*\*|Char)\s+' + name + r'\s*;\s*', '', text,
                         count=1)
            if new != text:
                lines[line] = new
                changed = True
        for m in NOT_DECLARED.finditer(r.stderr):
            line, name = int(m.group(1)) - 1, m.group(2)
            pattern = re.compile(r'for\s*\(\s*((?:const\s+)?[A-Za-z_][\w:]*\s*\**)\s+' + name + r'\s*=')
            for back in range(line, max(-1, line - 2000), -1):
                hit = pattern.search(lines[back])
                if hit:
                    typ = hit.group(1)
                    lines[back] = lines[back][:hit.start()] + typ + ' ' + name + '; for (' + name + ' =' + \
                        lines[back][hit.end():]
                    changed = True
                    break
        if not changed:
            sys.stderr.write(r.stderr[-4000:])
            return 1
        open(path, 'w', encoding='latin-1').write('\n'.join(lines))
    return 1


if __name__ == '__main__':
    sys.exit(main())
