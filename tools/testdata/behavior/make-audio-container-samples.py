"""Writes the CAF, Sony Wave64, Sun AU, LOAS and ADIF samples of the I/O behavior harness.

ffmpeg 7.1 writes the CAF (linear PCM, ALAC, Opus, IMA4), Wave64, AU and LOAS files from its synthetic lavfi
sources. ffmpeg writes no ADIF, so the ADIF sample is assembled here from the ADTS sample: its frames' raw data
blocks after an ADIF header (ISO/IEC 14496-3 subclause 1.A.2.1) whose program configuration element is built from
the ADTS profile, sampling frequency index and channel configuration, the instance tag being that of the first
channel element of the first block.
The AU file of 3-bit G.723 codes is assembled here too, since ffmpeg declares every G.726 code size as G.721
(encoding 23): its codes are ffmpeg's adpcm_g726le output and its header declares encoding 25.

    python -I tools/testdata/behavior/make-audio-container-samples.py [samples directory]
"""
import os
import struct
import subprocess
import sys
import tempfile

SHORT = "anoisesrc=color=pink:amplitude=0.3:sample_rate=22050:seed=7:duration=0.6"
TONE = "sine=frequency=440:sample_rate=8000:duration=0.6"


def ffmpeg(*arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", *arguments], check=True)


class Bits:
    def __init__(self):
        self.value = 0
        self.count = 0

    def write(self, value, bits):
        self.value = (self.value << bits) | (value & ((1 << bits) - 1))
        self.count += bits

    def align(self):
        self.write(0, -self.count % 8)

    def bytes(self):
        return self.value.to_bytes(self.count // 8, "big")


def first_channel_tag(block):
    """Returns the instance tag of the first single channel or channel pair element of a raw data block, walking
    the fill and data stream elements that may come before it."""
    value = int.from_bytes(block, "big")
    total = 8 * len(block)
    position = 0

    def read(bits):
        nonlocal position
        position += bits
        return (value >> (total - position)) & ((1 << bits) - 1)

    while True:
        element = read(3)
        if element in (0, 1):
            return read(4)
        if element == 6:
            count = read(4)
            if count == 15:
                count += read(8) - 1
            position += 8 * count
        elif element == 4:
            read(4)
            aligned = read(1)
            count = read(8)
            if count == 255:
                count += read(8)
            if aligned:
                position += -position % 8
            position += 8 * count
        else:
            raise ValueError("raw data block starts with element %d" % element)


def adts_frames(data):
    frames = []
    offset = 0
    while offset + 7 <= len(data):
        if data[offset] != 0xFF or data[offset + 1] & 0xF6 != 0xF0:
            raise ValueError("lost ADTS sync at %d" % offset)
        protection_absent = data[offset + 1] & 1
        profile = data[offset + 2] >> 6
        index = (data[offset + 2] >> 2) & 15
        channels = ((data[offset + 2] & 1) << 2) | (data[offset + 3] >> 6)
        length = ((data[offset + 3] & 3) << 11) | (data[offset + 4] << 3) | (data[offset + 5] >> 5)
        header = 7 if protection_absent else 9
        frames.append((profile, index, channels, data[offset + header:offset + length]))
        offset += length
    return frames


def adif(adts):
    frames = adts_frames(adts)
    profile, index, channels, first = frames[0]
    if channels not in (1, 2):
        raise ValueError("the sample is mono or stereo")
    rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]
    peak = max(len(frame[3]) for frame in frames) * 8 * rates[index] // 1024
    tag = first_channel_tag(first)
    bits = Bits()
    for character in b"ADIF":
        bits.write(character, 8)
    bits.write(0, 1)  # copyright_id_present
    bits.write(0, 1)  # original_copy
    bits.write(0, 1)  # home
    bits.write(1, 1)  # bitstream_type: variable rate
    bits.write(min(peak, (1 << 23) - 1), 23)
    bits.write(0, 4)  # num_program_config_elements - 1
    bits.write(0, 4)  # element_instance_tag
    bits.write(profile, 2)
    bits.write(index, 4)
    bits.write(1, 4)  # num_front_channel_elements
    bits.write(0, 4)  # num_side_channel_elements
    bits.write(0, 4)  # num_back_channel_elements
    bits.write(0, 2)  # num_lfe_channel_elements
    bits.write(0, 3)  # num_assoc_data_elements
    bits.write(0, 4)  # num_valid_cc_elements
    bits.write(0, 3)  # mono, stereo and matrix mixdown absent
    bits.write(1 if channels == 2 else 0, 1)  # front_element_is_cpe
    bits.write(tag, 4)  # front_element_tag_select
    bits.align()
    bits.write(0, 8)  # comment_field_bytes
    bits.align()
    return bits.bytes() + b"".join(frame[3] for frame in frames)


def au_header(encoding, rate, channels, data_size):
    return struct.pack(">4sIIIII8x", b".snd", 32, data_size, encoding, rate, channels)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/samples"
    for directory in ("caf", "w64", "au", "loas", "adif"):
        os.makedirs(os.path.join(out, directory), exist_ok=True)
    ffmpeg("-f", "lavfi", "-i", SHORT, "-c:a", "pcm_s16be", "-ac", "2", os.path.join(out, "caf", "s16.caf"))
    ffmpeg("-f", "lavfi", "-i", SHORT, "-c:a", "alac", "-ac", "2", os.path.join(out, "caf", "alac.caf"))
    ffmpeg("-f", "lavfi", "-i", SHORT, "-ar", "48000", "-c:a", "libopus", "-b:a", "32k", "-ac", "1",
           os.path.join(out, "caf", "opus.caf"))
    ffmpeg("-f", "lavfi", "-i", SHORT, "-c:a", "adpcm_ima_qt", "-ac", "2", os.path.join(out, "caf", "ima4.caf"))
    ffmpeg("-f", "lavfi", "-i", SHORT, "-c:a", "pcm_s24le", "-ac", "2", os.path.join(out, "w64", "s24.w64"))
    ffmpeg("-f", "lavfi", "-i", TONE, "-c:a", "adpcm_ima_wav", "-ac", "1", os.path.join(out, "w64", "ima.w64"))
    ffmpeg("-f", "lavfi", "-i", SHORT, "-c:a", "pcm_s16be", "-ac", "2", "-metadata", "title=Pink",
           os.path.join(out, "au", "s16.au"))
    ffmpeg("-f", "lavfi", "-i", TONE, "-c:a", "pcm_mulaw", "-ac", "1", os.path.join(out, "au", "mulaw.au"))
    ffmpeg("-f", "lavfi", "-i", TONE, "-c:a", "adpcm_g726le", "-ac", "1", os.path.join(out, "au", "g721.au"))
    with tempfile.TemporaryDirectory() as scratch:
        codes = os.path.join(scratch, "g723.au")
        ffmpeg("-f", "lavfi", "-i", TONE, "-c:a", "adpcm_g726le", "-code_size", "3", "-ac", "1", codes)
        with open(codes, "rb") as file:
            data = file.read()
        data = data[struct.unpack(">I", data[4:8])[0]:]
        with open(os.path.join(out, "au", "g723-3.au"), "wb") as file:
            file.write(au_header(25, 8000, 1, len(data)) + data)
    adts = os.path.join(out, "adts", "aac.aac")
    ffmpeg("-i", adts, "-c", "copy", "-f", "latm", os.path.join(out, "loas", "aac.loas"))
    with open(adts, "rb") as file:
        data = file.read()
    with open(os.path.join(out, "adif", "aac.adif"), "wb") as file:
        file.write(adif(data))


if __name__ == "__main__":
    main()
