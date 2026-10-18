"""Wraps Java lines longer than the limit: method chains at their outermost level, argument and
parameter lists at commas. Usage: fmt_java.py <file>... (edits in place)."""
import re
import sys

LIMIT = 120

def depth_map(line):
    depths = []
    d = 0
    in_str = False
    for i, ch in enumerate(line):
        if ch == '"' and (i == 0 or line[i - 1] != '\\'):
            in_str = not in_str
        if not in_str and ch in "([{":
            d += 1
        depths.append(d)
        if not in_str and ch in ")]}":
            d -= 1
    return depths

def wrap(line, cont=None):
    if len(line) <= LIMIT or line.lstrip().startswith(("*", "/", "//")):
        return [line]
    indent = len(line) - len(line.lstrip())
    if cont is None:
        cont = indent + 8
    depths = depth_map(line)
    # candidate breaks: '.' starting a call at some depth, or ', ' after which we can break
    candidates = []
    for m in re.finditer(r"\.(lanewise|blend|and|or|andNot|intoArray|compare|convertShape|cast)\(", line):
        pos = m.start()
        if pos > cont + 10:
            candidates.append((depths[pos], pos, "dot"))
    for m in re.finditer(r", ", line):
        pos = m.start() + 1
        if pos > cont + 10:
            candidates.append((depths[m.start()], pos, "comma"))
    if not candidates:
        return [line]
    fitting = [c for c in candidates if c[1] <= LIMIT]
    if not fitting:
        fitting = [min(candidates, key=lambda c: c[1])]
    best_depth = min(c[0] for c in fitting)
    choice = max((c for c in fitting if c[0] == best_depth), key=lambda c: c[1])
    _, pos, kind = choice
    head = line[:pos].rstrip()
    tail = line[pos:].lstrip()
    rest = " " * cont + tail
    return [head] + wrap(rest, cont)

def main():
    for path in sys.argv[1:]:
        with open(path, encoding="utf-8", newline="") as f:
            text = f.read()
        eol = "\r\n" if "\r\n" in text else "\n"
        out = []
        for line in text.replace("\r\n", "\n").split("\n"):
            out.extend(wrap(line))
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(eol.join(out))

main()
