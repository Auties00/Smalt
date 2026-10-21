#!/usr/bin/env bash
# Builds the Media Foundation decoder driver with the C# compiler of the .NET Framework that ships
# with Windows. Usage: build.sh <output directory>
set -euo pipefail
OUT="${1:?usage: build.sh <output directory>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CSC="${CSC:-/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe}"
mkdir -p "$OUT"
"$CSC" -nologo -platform:x64 -out:"$OUT/MfDecode.exe" "$(cygpath -w "$HERE/MfDecode.cs" 2>/dev/null || echo "$HERE/MfDecode.cs")"
echo "$OUT/MfDecode.exe"
