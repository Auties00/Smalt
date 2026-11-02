#!/usr/bin/env bash
# Builds the Media Foundation decoder drivers (MfDecode for video, MfAudioDecode for audio) with the
# C# compiler of the .NET Framework that ships with Windows. Usage: build.sh <output directory>
set -euo pipefail
OUT="${1:?usage: build.sh <output directory>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CSC="${CSC:-/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe}"
mkdir -p "$OUT"
for NAME in MfDecode MfAudioDecode; do
  "$CSC" -nologo -platform:x64 -out:"$OUT/$NAME.exe" "$(cygpath -w "$HERE/$NAME.cs" 2>/dev/null || echo "$HERE/$NAME.cs")"
  echo "$OUT/$NAME.exe"
done
