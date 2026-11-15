# Prints the native stacks of the crash dumps a failed Windows job left: the minidumps the JVM writes beside its
# hs_err files (hs_err_pid*.mdmp, anywhere in the working tree), and in the dump directory the minidumps and reports
# of the test kit's native crash filter (native-crash-*.dmp and .txt) and the full dumps of the capture job's
# debugger (capture.yml with debugger=true). For each it prints the faulting thread, every thread's
# stack and the loaded modules through cdb, which the Windows runners have (Debugging Tools for Windows). It also
# prints the exception rings of the test JVMs that died (native-exceptions.py). Never fails the job.
# The JVM reports no access violation on a thread it did not create: such a crash ends java.exe with 0xC0000005,
# no hs_err file and, on the hosted runners, no Windows Error Reporting dump either; the test kit's filter or a
# debugger attached from the start sees it, and a crash no handler receives leaves at least its exception ring.
# Usage: windows-crash-dumps.ps1 <dump directory>
param(
    [Parameter(Mandatory = $true)][string] $Directory
)
$ErrorActionPreference = "Continue"
Set-StrictMode -Version 2.0

$dumps = @()
if (Test-Path $Directory) {
    foreach ($report in Get-ChildItem -Path $Directory -Filter "native-crash-*.txt" -File -ErrorAction SilentlyContinue) {
        Write-Host "::error::$(Get-Content -Raw $report.FullName)"
    }
    if (Get-Command python -ErrorAction SilentlyContinue) {
        Write-Host "::group::exception rings of the test JVMs that died"
        & python -I (Join-Path $PSScriptRoot "native-exceptions.py") $Directory 2>&1 | Out-String -Width 400 | Write-Host
        Write-Host "::endgroup::"
    }
    $dumps += Get-ChildItem -Path $Directory -Filter *.dmp -File -ErrorAction SilentlyContinue
}
$dumps += Get-ChildItem -Path . -Recurse -Filter "hs_err_pid*.mdmp" -File -ErrorAction SilentlyContinue
if ($dumps.Count -eq 0) {
    Write-Host "no crash dumps"
    exit 0
}
$cdb = @(
    "${env:ProgramFiles(x86)}\Windows Kits\10\Debuggers\x64\cdb.exe",
    "${env:ProgramFiles}\Windows Kits\10\Debuggers\x64\cdb.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
$symbols = Join-Path $env:RUNNER_TEMP "symbols"
foreach ($dump in $dumps) {
    Write-Host "::group::$($dump.FullName) ($([math]::Round($dump.Length / 1MB)) MiB)"
    if ($cdb) {
        $commands = ".symfix $symbols; .reload; .ecxr; kn 64; !analyze -v; ~*kn 64; lm; q"
        & $cdb -z $dump.FullName -c $commands 2>&1 | Out-String -Width 400 | Write-Host
    } else {
        Write-Host "cdb is not installed: the dump is in the uploaded artifact"
    }
    Write-Host "::endgroup::"
}
exit 0
