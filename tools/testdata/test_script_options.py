"""Checks that the tools taking an optional output or root path refuse an option in its place.

Such a script used to take "--help" as its output directory and write its files under a directory of that name.
Every script under tools/ whose first argument is an optional path (a Python sys.argv[1] with a default, a shell
${1:-default}) must stop before writing anything when that argument starts with "-".

    python -m unittest discover -s tools/testdata -p "test_*.py"
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
PYTHON = re.compile(r"sys\.argv\[1\]\)* if len\(sys\.argv\) > 1")
SHELL = re.compile(r'^\s*\w+="\$\{1:-', re.MULTILINE)


def scripts():
    """Yields the scripts under tools/ whose first argument is an optional path."""
    for path in sorted((ROOT / "tools").rglob("*")):
        if "node_modules" in path.parts or "__pycache__" in path.parts or not path.is_file():
            continue
        if path.suffix == ".py" and path.name != pathlib.Path(__file__).name:
            if PYTHON.search(path.read_text(encoding="utf-8")):
                yield path
        elif path.suffix == ".sh" and SHELL.search(path.read_text(encoding="utf-8")):
            yield path


class ScriptOptionsTest(unittest.TestCase):

    def test_scripts_found(self):
        names = {path.name for path in scripts()}
        self.assertIn("make-descriptive-samples.py", names)
        self.assertIn("make-samples.sh", names)

    def test_guard_precedes_use(self):
        for path in scripts():
            with self.subTest(script=str(path.relative_to(ROOT))):
                text = path.read_text(encoding="utf-8")
                use = (PYTHON if path.suffix == ".py" else SHELL).search(text).start()
                guard = text.find("sys.argv[1].startswith('-')" if path.suffix == ".py" else 'case "${1:-}" in -*)')
                self.assertTrue(0 <= guard < use, "no guard before the first use of the argument")

    def test_option_writes_nothing(self):
        shell = shutil.which("bash")
        for path in scripts():
            if path.suffix == ".sh" and shell is None:
                continue
            for option in ("--help", "-h"):
                with self.subTest(script=str(path.relative_to(ROOT)), option=option), \
                        tempfile.TemporaryDirectory() as directory:
                    # not isolated, since some generators import the modules beside them
                    command = [shell, str(path)] if path.suffix == ".sh" else [sys.executable, str(path)]
                    result = subprocess.run(command + [option], cwd=directory, capture_output=True, text=True,
                                            timeout=60, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
                    text = result.stdout + result.stderr
                    if "ModuleNotFoundError" in text:
                        self.skipTest(str(path.relative_to(ROOT)) + " needs a module this Python lacks")
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual([], os.listdir(directory))
                    self.assertFalse((ROOT / option).exists())
                    # the guard answered, not a tool that failed before it
                    self.assertNotIn("Traceback", text)
                    self.assertTrue(text.strip(), "the script printed no usage")


if __name__ == "__main__":
    unittest.main()
