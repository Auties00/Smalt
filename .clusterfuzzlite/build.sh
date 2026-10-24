#!/bin/bash -eu
# Builds Smalt's fuzz targets into $OUT for ClusterFuzzLite (tools/fuzz/README.md): one launcher per @FuzzTest
# method, the bundled Java 26 JDK, the module class paths, seed corpus zips and dictionaries.
export JAVA_HOME="$SMALT_JDK"
export PATH="$JAVA_HOME/bin:$SMALT_MAVEN/bin:$PATH"
python3 tools/fuzz/fuzz.py build --online
# The smoke data tier adds seeds to the committed ones; a host that is down only costs its seeds.
python3 tools/testdata/testdata.py fetch --tier smoke --no-clips || echo "warning: the smoke tier is incomplete"
python3 tools/fuzz/fuzz.py seeds
python3 tools/fuzz/fuzz.py package --no-build --out "$OUT"
