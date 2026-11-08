"""Writes the sources of the IJG jpegtran 9f vectors: baseline frames of five to seven components.

The frames are written by the encoder of tools/fuzz/make-jpeg-component-seeds.py (one scan per component), since
no libjpeg tool writes frames of more than four components.

    python -I tools/testdata/jpeg-ijg9f/sources.py <directory>
"""
import importlib.util
import os
import sys

SOURCES = {
    "five-mixed-41x35.jpg": (41, 35, [(3, 1), (1, 1), (1, 3), (2, 2), (1, 1)]),
    "six-mixed-29x21.jpg": (29, 21, [(1, 1), (2, 1), (1, 2), (1, 1), (2, 2), (1, 1)]),
    "seven-1x1-19x13.jpg": (19, 13, [(1, 1)] * 7),
    "five-small-13x11.jpg": (13, 11, [(2, 2), (1, 1), (1, 2), (2, 1), (1, 1)]),
}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    path = os.path.join(here, "..", "..", "fuzz", "make-jpeg-component-seeds.py")
    spec = importlib.util.spec_from_file_location("seeds", path)
    seeds = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(seeds)
    for name, (width, height, sampling) in SOURCES.items():
        with open(os.path.join(sys.argv[1], name), "wb") as file:
            file.write(seeds.write(width, height, sampling))


if __name__ == "__main__":
    main()
