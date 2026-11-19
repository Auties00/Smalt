#!/usr/bin/env python3
"""Reads BMP files with Pillow, an independent reader of Smalt's BMP output for the encoder tests.

Usage: python -P tools/testdata/bmp-oracle/pillow-bmp-dump.py <file>...

For each file it writes <file>.pillow.rgba, the pixels converted with Image.convert("RGBA") (8-bit red,
green, blue and straight alpha), or <file>.pillow.error with Pillow's message when it cannot read the file.
Exits with status 3 when Pillow is not installed, which the tests take as a missing oracle. Checked with
Pillow 12.0.0.
"""
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit(3)


def main():
    for name in sys.argv[1:]:
        try:
            with Image.open(name) as image:
                data = image.convert("RGBA").tobytes()
            with open(name + ".pillow.rgba", "wb") as out:
                out.write(data)
        except Exception as error:  # every reader failure is recorded for the test
            with open(name + ".pillow.error", "w", encoding="utf-8") as out:
                out.write(repr(error))


if __name__ == "__main__":
    main()
