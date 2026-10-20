"""Runs the libopus reference on every golden configuration and prints the golden lines.

Usage: python -I tools/testdata/opus-oracle/goldens.py <vector dir> <msenc> <work dir>

<vector dir> is the unpacked RFC 8251 test vectors (opus_testvectors-rfc8251 in the conformance
cache), <msenc> the harness build.py compiles, <work dir> receives the generated inputs and
bitstreams. Each configuration: name input rate channels family application bitrate mode
complexity frame_us signal maxbw fec loss dtx s16 (family "s" is the single-stream encoder). The
printed line appends the SHA-256 of the opus_demo bitstream and that of the libopus decoder's
float output for it (little-endian, interleaved, at the input rate): the bitstream hash of the
"check_" lines must equal the corresponding goldens of OpusEncoderGoldenTest, which validates the
build, the "p" lines (mapping family 3, the projection encoder and decoder) are the goldens of
OpusProjectionGoldenTest, and the other lines are the goldens of OpusMultistreamGoldenTest.
"""
import hashlib
import os
import subprocess
import sys

vec_dir, msenc, work = sys.argv[1], sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
CONFIGS = [
    "check_music128 music48k 48000 2 s audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "check_voicenote speech16k 16000 1 s voip 16000 vbr 10 20000 auto auto 0 0 0 0",
    "check_celt40 music48k 48000 2 s audio 96000 vbr 10 40000 auto auto 0 0 0 0",
    "s51_256 surround6_4 48000 6 1 audio 256000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_128cvbr surround6_4 48000 6 1 audio 128000 cvbr 10 20000 auto auto 0 0 0 0",
    "s51_96cbr surround6_4 48000 6 1 audio 96000 cbr 10 20000 auto auto 0 0 0 0",
    "s51_64 surround6_4 48000 6 1 audio 64000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_voip48 surround6_4 48000 6 1 voip 48000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_voip32_fec surround6_4 48000 6 1 voip 32000 vbr 9 20000 voice auto 1 10 1 0",
    "s51_10ms surround6_4 48000 6 1 audio 192000 vbr 10 10000 auto auto 0 0 0 0",
    "s51_2_5ms surround6_4 48000 6 1 audio 320000 vbr 10 2500 auto auto 0 0 0 0",
    "s51_40ms surround6_4 48000 6 1 audio 160000 vbr 10 40000 auto auto 0 0 0 0",
    "s51_60ms_cbr surround6_4 48000 6 1 audio 128000 cbr 10 60000 auto auto 0 0 0 0",
    "s51_120ms surround6_4 48000 6 1 voip 64000 vbr 10 120000 auto auto 0 0 0 0",
    "s51_100ms surround6_4 48000 6 1 audio 128000 vbr 10 100000 auto auto 0 0 0 0",
    "s51_c5 surround6_4 48000 6 1 audio 192000 vbr 5 20000 auto auto 0 0 0 0",
    "s51_c0 surround6_4 48000 6 1 audio 192000 vbr 0 20000 auto auto 0 0 0 0",
    "s51_24k surround6_4 24000 6 1 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_16k surround6_4 16000 6 1 voip 96000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_8k surround6_4 8000 6 1 voip 48000 vbr 10 20000 auto auto 0 0 0 0",
    "s51_lowdelay surround6_4 48000 6 1 lowdelay 256000 vbr 10 10000 auto auto 0 0 0 0",
    "s51_music surround6_4 48000 6 1 audio 192000 vbr 10 20000 music auto 0 0 0 0",
    "d6_music_swb surround6_4 48000 6 255 audio 192000 vbr 10 20000 music swb 0 0 0 0",
    "d4_wb_voip surround4_4 16000 4 255 voip 64000 vbr 10 20000 voice wb 0 0 0 0",
    "s51_s16 surround6_4 48000 6 1 audio 224000 vbr 10 20000 auto auto 0 0 0 1",
    "s71_320 surround8_4 48000 8 1 audio 320000 vbr 10 20000 auto auto 0 0 0 0",
    "s71_cbr160 surround8_4 48000 8 1 audio 160000 cbr 8 20000 auto auto 0 0 0 0",
    "s71_cvbr96 surround8_4 48000 8 1 audio 96000 cvbr 10 20000 auto auto 0 0 0 0",
    "s30 surround3_4 48000 3 1 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "s40 surround4_4 48000 4 1 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
    "s50 surround5_4 48000 5 1 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
    "s61 surround7_4 48000 7 1 audio 224000 vbr 10 20000 auto auto 0 0 0 0",
    "v2 surround2_4 48000 2 1 audio 96000 vbr 10 20000 auto auto 0 0 0 0",
    "v1 surround1_4 48000 1 1 audio 48000 vbr 10 20000 auto auto 0 0 0 0",
    "d6 surround6_4 48000 6 255 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
    "d6_auto surround6_4 48000 6 255 audio auto vbr 10 20000 auto auto 0 0 0 0",
    "d6_max surround6_4 48000 6 255 audio max vbr 10 20000 auto auto 0 0 0 0",
    "d6_cbr_auto surround6_4 48000 6 255 audio auto cbr 10 20000 auto auto 0 0 0 0",
    "d2 surround2_4 48000 2 255 voip 48000 vbr 10 20000 auto auto 0 0 0 0",
    "d3_16k surround3_4 16000 3 255 voip 36000 vbr 10 20000 auto auto 0 0 0 0",
    "amb4 surround4_4 48000 4 2 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "amb6 surround6_4_nolfe 48000 6 2 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
    "amb9_auto surround8_4_nolfe 48000 8 255 audio auto vbr 10 20000 auto auto 0 0 0 0",
    "q51 quiet6_4 48000 6 1 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "q51_s16 quiet6_4 48000 6 1 audio 128000 vbr 10 20000 auto auto 0 0 0 1",
    "qd6 quiet6_4 48000 6 255 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "p4_128 amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 0",
    "p4_cbr96 amb4_4 48000 4 3 audio 96000 cbr 10 20000 auto auto 0 0 0 0",
    "p4_s16 amb4_4 48000 4 3 audio 128000 vbr 10 20000 auto auto 0 0 0 1",
    "p4_auto amb4_4 48000 4 3 audio auto vbr 10 20000 auto auto 0 0 0 0",
    "p4_voip_dtx amb4_4 48000 4 3 voip 32000 vbr 10 20000 auto auto 1 20 1 0",
    "p6_160 amb6_4 48000 6 3 audio 160000 vbr 10 20000 auto auto 0 0 0 0",
    "p9_192 amb9_2 48000 9 3 audio 192000 vbr 10 20000 auto auto 0 0 0 0",
    "p9_voip_fec amb9_2 16000 9 3 voip 72000 vbr 10 20000 voice auto 1 10 0 0",
    "p9_s16_cvbr amb9_2 48000 9 3 audio 160000 cvbr 10 20000 auto auto 0 0 0 1",
    "p11_10ms amb11_2 48000 11 3 audio 220000 vbr 10 10000 auto auto 0 0 0 0",
    "p16_256 amb16_2 48000 16 3 audio 256000 vbr 10 20000 auto auto 0 0 0 0",
    "p18_60ms amb18_2 48000 18 3 audio 288000 vbr 10 60000 auto auto 0 0 0 0",
    "p25_c5 amb25_1 48000 25 3 audio 400000 vbr 5 20000 auto auto 0 0 0 0",
    "p36_c3 amb36_1 48000 36 3 audio 512000 vbr 3 20000 auto auto 0 0 0 0",
    "p38_cbr24k amb38_1 24000 38 3 audio 400000 cbr 5 20000 auto auto 0 0 0 0",
]

os.makedirs(work, exist_ok=True)
for line in CONFIGS:
    f = line.split()
    pcm = os.path.join(work, f[1] + ".pcm")
    if not os.path.exists(pcm):
        subprocess.check_call([sys.executable, "-I", os.path.join(here, "gen.py"), vec_dir, f[1], pcm])
    bit = os.path.join(work, f[0] + ".bit")
    decoded = os.path.join(work, f[0] + ".f32")
    info = subprocess.run([msenc, pcm, bit, f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10], f[11], f[12],
                           f[13], f[14], f[15], decoded], check=True, capture_output=True, text=True).stdout.strip()
    digest = hashlib.sha256(open(bit, "rb").read()).hexdigest()
    decoded_digest = hashlib.sha256(open(decoded, "rb").read()).hexdigest()
    print('"%s %s %s",' % (line, digest, decoded_digest), "//", info, file=sys.stdout)
