"""Runs the libopus reference decoders over lossy packet traces and prints the golden lines.

Usage: python -I tools/testdata/opus-oracle/traces.py <vector dir> <oracle dir> <work dir>

<vector dir> is the unpacked RFC 8251 test vectors, <oracle dir> holds the msenc and msdec harnesses
build.py compiles, <work dir> receives the inputs, bitstreams and decoded outputs. Each trace names an
encoder configuration (the fields of goldens.py), then the decoding rate, whether lost packets are
rebuilt from the in-band FEC of the next one, the loss percentage and seed of msdec's loss rule, and the
output format. The printed line appends the SHA-256 of the libopus bitstream (in opus_demo form) and of the
libopus decoder output (interleaved, little endian, coded channel order): the goldens of
OpusPacketLossGoldenTest.
"""
import hashlib
import os
import re
import subprocess
import sys

vec_dir, oracle, work = sys.argv[1], sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
exe = ".exe" if os.name == "nt" else ""
CONFIGS = {
    "s51_256": "surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_voip32_fec": "surround6_4 48000 6 1 voip 32000 vbr 9 20000 voice auto 1 10 1 0",
    "d6": "surround6_4 48000 6 255 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
    "d4_wb_voip_fec": "surround4_4 16000 4 255 voip 48000 vbr 10 20000 voice wb 1 15 0 0",
    "amb6": "surround6_4_nolfe 48000 6 2 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
    "p4_128": "amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "p4_voip_dtx": "amb4_4 48000 4 3 voip 32000 vbr 10 20000 auto auto 1 20 1 0",
    "p9_voip_fec": "amb9_2 16000 9 3 voip 72000 vbr 10 20000 voice auto 1 10 0 0",
    "p6_40ms_fec": "amb6_4 48000 6 3 voip 64000 vbr 10 40000 voice auto 1 15 0 0",
}
TRACES = [
    "t_s51_fec_f32 s51_voip32_fec 48000 1 20 3 f32",
    "t_s51_fec_s16 s51_voip32_fec 48000 1 20 3 s16",
    "t_s51_fec_16k s51_voip32_fec 16000 1 20 3 f32",
    "t_s51_plc_f32 s51_256 48000 0 15 11 f32",
    "t_s51_s16 s51_256 48000 0 0 0 s16",
    "t_d6_plc_s16 d6 48000 0 25 7 s16",
    "t_d4_fec_f32 d4_wb_voip_fec 16000 1 25 1 f32",
    "t_amb6_plc_f32 amb6 24000 0 10 4 f32",
    "t_p4_plc_f32 p4_128 48000 0 15 2 f32",
    "t_p4_s16 p4_128 48000 0 0 0 s16",
    "t_p4dtx_fec_s16 p4_voip_dtx 48000 1 20 9 s16",
    "t_p9_fec_f32 p9_voip_fec 16000 1 20 5 f32",
    "t_p9_fec_s16 p9_voip_fec 16000 1 20 5 s16",
    "t_p9_fec_8k p9_voip_fec 8000 1 30 6 f32",
    "t_p6_fec_f32 p6_40ms_fec 48000 1 25 8 f32",
]

os.makedirs(work, exist_ok=True)
streams = {}
for name, config in CONFIGS.items():
    f = config.split()
    pcm = os.path.join(work, f[0] + ".pcm")
    if not os.path.exists(pcm):
        subprocess.check_call([sys.executable, "-I", os.path.join(here, "gen.py"), vec_dir, f[0], pcm])
    bit = os.path.join(work, "trace_" + name + ".bit")
    info = subprocess.run([os.path.join(oracle, "msenc" + exe), pcm, bit] + f[1:], check=True,
                          capture_output=True, text=True).stdout
    s = re.search(r"streams=(\d+) coupled=(\d+)", info)
    demixing = re.search(r"gain=(-?\d+) demixing=([0-9a-f]+)", info)
    if demixing:
        streams[name] = (bit, f[2], "proj", s.group(1), s.group(2), demixing.group(2), demixing.group(1))
    else:
        mapping = re.search(r"mapping=([0-9,]+)", info).group(1)
        streams[name] = (bit, f[2], "ms", s.group(1), s.group(2), mapping, "0")
for line in TRACES:
    t = line.split()
    bit, channels, kind, nb_streams, coupled, table, gain = streams[t[1]]
    out = os.path.join(work, t[0] + "." + t[6])
    info = subprocess.run([os.path.join(oracle, "msdec" + exe), bit, out, t[2], channels, kind, nb_streams, coupled,
                           table, gain, t[3], t[4], t[5], t[6]], check=True, capture_output=True, text=True).stdout
    bit_digest = hashlib.sha256(open(bit, "rb").read()).hexdigest()
    digest = hashlib.sha256(open(out, "rb").read()).hexdigest()
    print('"%s %s %s",' % (line, bit_digest, digest), "//", info.strip())
