"""Prints the exception rings of the Windows test JVMs that died (the test kit's NativeCrashDumps).

Usage: python native-exceptions.py <dump directory> [entries]

Each test JVM that installed the test kit's native crash filter records every exception its process raises into
exceptions-<pid>.bin and writes modules-<pid>.txt (base, size and path of the loaded modules); a JVM that exits
normally also writes exit-<pid>.txt. For every ring without an exit marker, this prints its last entries (64 unless
given), oldest first: exception code, thread id, address with the module and offset holding it, stack pointer and
the second exception parameter (the address an access violation touched), then the last entry of every thread.
The JVM's own exceptions (safepoint polls, implicit null checks) are among them; an access violation outside
jvm.dll and the code cache is the one to look at. Never fails.
"""
import glob
import os
import re
import struct
import sys

HEADER = 64
ENTRY = 32
ENTRIES = 1024
CODES = {
    0xC0000005: 'ACCESS_VIOLATION',
    0xC00000FD: 'STACK_OVERFLOW',
    0xC0000409: 'STACK_BUFFER_OVERRUN',
    0xC000001D: 'ILLEGAL_INSTRUCTION',
    0x80000003: 'BREAKPOINT',
    0xE06D7363: 'C++ EXCEPTION',
    0x406D1388: 'SET_THREAD_NAME',
    0x40010006: 'OUTPUT_DEBUG_STRING',
    0x80000004: 'SINGLE_STEP',
    0xC0000096: 'PRIVILEGED_INSTRUCTION',
    0xC0000094: 'INTEGER_DIVIDE_BY_ZERO',
    0x8001010E: 'RPC_E_WRONG_THREAD',
}


def modules(path):
    found = []
    if os.path.exists(path):
        with open(path, encoding='utf-8', errors='replace') as file:
            for line in file:
                parts = line.rstrip('\r\n').split(' ', 2)
                if len(parts) == 3 and re.fullmatch(r'[0-9a-f]{16}', parts[0]):
                    found.append((int(parts[0], 16), int(parts[1], 16), parts[2]))
    return found


def where(address, loaded):
    for base, size, path in loaded:
        if base <= address < base + size:
            return '%s+0x%x' % (os.path.basename(path), address - base)
    return '?'


def main():
    directory = sys.argv[1]
    shown = int(sys.argv[2]) if len(sys.argv) > 2 else 64
    rings = sorted(glob.glob(os.path.join(directory, 'exceptions-*.bin')))
    died = 0
    for ring in rings:
        pid = re.search(r'exceptions-(\d+)\.bin$', ring).group(1)
        if os.path.exists(os.path.join(directory, 'exit-%s.txt' % pid)):
            continue
        died += 1
        loaded = modules(os.path.join(directory, 'modules-%s.txt' % pid))
        with open(ring, 'rb') as file:
            data = file.read()
        if len(data) < HEADER + ENTRY * ENTRIES:
            print('process %s: truncated ring (%d bytes)' % (pid, len(data)))
            continue
        count = struct.unpack_from('<I', data, 0)[0]
        first = max(0, count - min(shown, ENTRIES))
        print('process %s died; %d exceptions recorded, the last %d:' % (pid, count, count - first))
        last = {}
        for index in range(first, count):
            offset = HEADER + (index % ENTRIES) * ENTRY
            code, tid, address, rsp, parameter = struct.unpack_from('<IIQQQ', data, offset)
            line = '  #%d thread %d: 0x%08x %s at 0x%016x (%s), rsp 0x%016x, parameter 0x%016x' % (
                index, tid, code, CODES.get(code, ''), address, where(address, loaded), rsp, parameter)
            print(line)
            last[tid] = line
        print('process %s: the last exception of each thread:' % pid)
        for line in last.values():
            print(line)
    if not rings:
        print('no exception rings in %s' % directory)
    elif died == 0:
        print('every JVM with an exception ring exited normally')


if __name__ == '__main__':
    try:
        main()
    except Exception as exception:  # a report must never fail the job
        print('cannot read the exception rings: %s' % exception)
