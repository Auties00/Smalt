#!/bin/sh
# Builds q.c into the QTSystem directory the README describes: bash build.sh <QTSystem directory>.
set -e
here=$(cd "$(dirname "$0")" && pwd)
kit="/c/Program Files (x86)/Windows Kits/10/Lib/10.0.26100.0/um/x86"
object=$(mktemp -d)/q.o
clang --target=i686-w64-windows-gnu -O1 -c "$here/q.c" -o "$object" -ffreestanding -fno-stack-protector -mno-sse -I/c/msys64/ucrt64/include -w
lld-link -nologo -entry:start@0 -subsystem:console -nodefaultlib -nxcompat:no -dynamicbase:no -out:"$1/q.exe" "$object" \
    "$kit/kernel32.lib" "$kit/user32.lib" "$kit/advapi32.lib"
