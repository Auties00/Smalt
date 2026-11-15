"""Runs the libopus 1.5.2 neural build on the golden configurations of OpusDnnGoldenTest and prints its lines.

Usage: python -I tools/testdata/opus-oracle/dnn_goldens.py <vector dir> <oracle dir> <work dir>

<vector dir> is the unpacked RFC 8251 test vectors, <oracle dir> holds the dnn harness build.py compiles, <work dir>
receives the inputs (gen.py), bitstreams and decoded outputs.

An encoder line names the configuration: name input rate channels application bitrate mode complexity frame_us
signal maxbw fec loss dtx dred (the DRED duration in 10 ms frames), then the SHA-256 of the libopus bitstream in
opus_demo form. A decoder line names a trace: name encoder-configuration rate complexity loss seed mode
(plc, fec or dred) format, then the SHA-256 of the libopus decoder output (interleaved, little endian) of dnn.c
"dec" and the number of packets rebuilt from DRED. A plc trace below complexity 5 is decoded by msdec, the build
without the neural extensions, which Smalt's decoder matches below that complexity.
"""
import hashlib
import os
import re
import subprocess
import sys

vec_dir, oracle, work = sys.argv[1], sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
exe = ".exe" if os.name == "nt" else ""
ENCODERS = [
    "e_dred48 speech48k 48000 1 voip 32000 cvbr 10 20000 auto auto 0 20 0 100",
    "e_dred16_fec speech16k 16000 1 voip 40000 vbr 10 20000 auto auto 1 15 0 50",
    "e_dred48_cbr40 speech48k 48000 1 voip 24000 cbr 10 40000 auto auto 0 25 0 100",
    "e_dred16_st60 speech16k_stereo 16000 2 voip 32000 vbr 9 60000 voice auto 0 30 0 60",
    "e_dred48_dtx speech48k 48000 1 voip 20000 vbr 10 20000 auto auto 0 20 1 100",
    "e_dred48_hyb40 speech48k 48000 1 audio 48000 cvbr 10 40000 auto auto 0 20 0 30",
    "e_dred12 speech12k 12000 1 voip 32000 vbr 10 20000 auto auto 0 20 0 40",
    "e_dred8 speech8k 8000 1 voip 28000 vbr 10 10000 auto auto 0 25 0 104",
    "e_dred24 speech24k 24000 1 voip 40000 vbr 10 20000 auto auto 0 15 0 20",
    "e_plain16 speech16k 16000 1 voip 20000 vbr 10 20000 auto auto 1 15 0 0",
    "e_celt48 music48k_mono 48000 1 audio 48000 vbr 10 20000 auto auto 0 0 0 0",
    "e_celt48st music48k 48000 2 audio 96000 vbr 10 20000 auto auto 0 0 0 0",
]
DECODERS = [
    "d_dred48_c5 e_dred48 48000 5 20 3 dred f32",
    "d_dred48_c7_s16 e_dred48 48000 7 20 3 dred s16",
    "d_dred48_at16k e_dred48 16000 6 25 5 dred f32",
    "d_dred16_fec e_dred16_fec 16000 10 15 2 fec f32",
    "d_dred16_dred e_dred16_fec 16000 5 30 9 dred f32",
    "d_dred48_cbr40 e_dred48_cbr40 48000 5 25 1 dred f32",
    "d_dred16_st60 e_dred16_st60 16000 7 30 2 dred f32",
    "d_dred48_dtx e_dred48_dtx 48000 5 20 6 dred f32",
    "d_dred48_hyb40 e_dred48_hyb40 48000 5 20 4 dred f32",
    "d_dred12 e_dred12 12000 6 20 8 dred f32",
    "d_dred8 e_dred8 8000 5 35 3 dred f32",
    "d_dred24 e_dred24 24000 9 20 4 dred s16",
    "d_plc16 e_plain16 16000 5 15 2 plc f32",
    "d_lace16 e_plain16 16000 6 0 0 plc f32",
    "d_nolace16 e_plain16 16000 7 0 0 plc f32",
    "d_nolace16_loss e_plain16 16000 10 15 2 plc s16",
    "d_nolace48 e_dred48 48000 8 0 0 plc f32",
    "d_celt48 e_celt48 48000 5 15 4 plc f32",
    "d_celt48st e_celt48st 48000 5 15 7 plc f32",
    "d_celt48st_c4 e_celt48st 48000 4 15 7 plc f32",
]


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    os.makedirs(work, exist_ok=True)
    configs = {}
    for line in ENCODERS:
        f = line.split()
        configs[f[0]] = f
        pcm = os.path.join(work, f[1] + ".pcm")
        if not os.path.exists(pcm):
            subprocess.check_call([sys.executable, "-I", os.path.join(here, "gen.py"), vec_dir, f[1], pcm])
        bit = os.path.join(work, f[0] + ".bit")
        subprocess.check_call([os.path.join(oracle, "dnn" + exe), "enc", pcm, bit] + f[2:], stdout=subprocess.DEVNULL)
        print(line + " " + sha256(bit))
    for line in DECODERS:
        t = line.split()
        f = configs[t[1]]
        out = os.path.join(work, t[0] + ".out")
        bit = os.path.join(work, t[1] + ".bit")
        if int(t[3]) < 5:
            assert t[6] == "plc"
            subprocess.check_call([os.path.join(oracle, "msdec" + exe), bit, out, t[2], f[3], "s", "1", "0", "x", "0",
                                   "0", t[4], t[5], t[7]])
            rebuilt = "0"
        else:
            text = subprocess.check_output([os.path.join(oracle, "dnn" + exe), "dec", bit, out, t[2], f[3], t[3],
                                            t[4], t[5], t[6], t[7]], text=True)
            rebuilt = re.search(r"rebuilt (\d+)", text).group(1)
        print(line + " " + sha256(out) + " " + rebuilt)


if __name__ == "__main__":
    main()
