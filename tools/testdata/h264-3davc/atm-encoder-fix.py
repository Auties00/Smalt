"""Makes the 3DV-ATM encoder build with MinGW and write the same bitstream on every run: the user data SEI UUID
derived from the clock is replaced by zeros."""
import sys
p = sys.argv[1]
s = open(p, encoding='latin-1').read()
old_start = s.index('#ifdef _WIN32\n    len+=u_v (32,"SEI: uuid_iso_iec_11578",(int) start_time.HighPart')
old_end = s.index('#endif', old_start) + len('#endif')
s = s[:old_start] + ('    len+=u_v (32,"SEI: uuid_iso_iec_11578",0, bitstream);\n'
                     '    len+=u_v (32,"SEI: uuid_iso_iec_11578",0, bitstream);') + s[old_end:]
open(p, 'w', encoding='latin-1').write(s)
