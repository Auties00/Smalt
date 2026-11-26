#!/bin/bash -eu
# Builds Smalt's fuzz targets into $OUT for ClusterFuzzLite (tools/fuzz/README.md): one launcher per @FuzzTest
# method, the bundled Java 26 JDK, the module class paths, seed corpus zips and dictionaries.
export JAVA_HOME="$SMALT_JDK"
export PATH="$JAVA_HOME/bin:$SMALT_MAVEN/bin:$PATH"
python3 tools/fuzz/fuzz.py build --online
# The vectors of the hosted test data add seeds to the committed ones when SMALT_TESTDATA holds them; the build
# downloads none.
python3 tools/fuzz/fuzz.py seeds
python3 tools/fuzz/fuzz.py package --no-build --out "$OUT"
