"""Regenerates the WAVE samples with coded payloads of the I/O behavior harness.

Each sample stores, in the data chunk of a WAVE file, an elementary stream that ffmpeg 7.1 encodes from
its synthetic lavfi sources, so that every reader WavDemuxer hands a data chunk to (MPEG audio, AC-3,
AAC in LOAS framing and as raw data blocks, FLAC frames, AMR frames, self-delimited Opus, DTS frames
and IEC 61937 bursts) runs under the harness. ffmpeg's WAVE muxer writes the MPEG audio, AC-3 and DTS
files; this script writes the others around the streams ffmpeg encodes. As for make-samples.sh, the
committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-wav-samples.py [output directory]
"""
import os
import struct
import subprocess
import sys
import tempfile

NOISE = "anoisesrc=color=pink:amplitude=0.3:sample_rate={rate}:seed=7:duration={seconds}"


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", *args], check=True)


def noise(rate, seconds=1.0):
    return ["-f", "lavfi", "-i", NOISE.format(rate=rate, seconds=seconds)]


def wave(tag, channels, rate, byte_rate, block_align, bits, extra, data):
    """Returns a RIFF WAVE file with a WAVEFORMATEX fmt chunk carrying extra and one data chunk."""
    fmt = struct.pack("<HHIIHHH", tag, channels, rate, byte_rate, block_align, bits, len(extra)) + extra
    if len(fmt) % 2:
        fmt += b"\0"
    body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(data)) + data
    if len(data) % 2:
        body += b"\0"
    return b"RIFF" + struct.pack("<I", len(body)) + body


def adts_frames(stream):
    """Splits an ADTS stream into (header, raw data block) pairs."""
    frames = []
    position = 0
    while position + 7 <= len(stream):
        if stream[position] != 0xFF or stream[position + 1] & 0xF6 != 0xF0:
            raise ValueError("lost ADTS sync at %d" % position)
        protection_absent = stream[position + 1] & 1
        length = ((stream[position + 3] & 3) << 11) | (stream[position + 4] << 3) | (stream[position + 5] >> 5)
        header = 7 if protection_absent else 9
        frames.append((stream[position:position + header], stream[position + header:position + length]))
        position += length
    return frames


def audio_specific_config(adts_header):
    profile = (adts_header[2] >> 6) + 1
    frequency = (adts_header[2] >> 2) & 15
    channels = ((adts_header[2] & 1) << 2) | (adts_header[3] >> 6)
    return struct.pack(">H", (profile << 11) | (frequency << 7) | (channels << 3))


def ogg_packets(data):
    """Returns the packets of a single-stream Ogg file."""
    packets = []
    pending = b""
    position = 0
    while position < len(data):
        if data[position:position + 4] != b"OggS":
            raise ValueError("lost Ogg capture at %d" % position)
        segments = data[position + 26]
        table = data[position + 27:position + 27 + segments]
        offset = position + 27 + segments
        for lace in table:
            pending += data[offset:offset + lace]
            offset += lace
            if lace < 255:
                packets.append(pending)
                pending = b""
        position = offset
    return packets


def frame_length(length):
    """Encodes a frame length as RFC 6716 section 3.1 does."""
    if length < 252:
        return bytes([length])
    first = 252 + (length & 3)
    return bytes([first, (length - first) >> 2])


def read_length(packet, offset):
    first = packet[offset]
    if first < 252:
        return first, offset + 1
    return first + 4 * packet[offset + 1], offset + 2


def self_delimited(packet):
    """Converts an Opus packet to the self-delimiting framing of RFC 6716 Appendix B."""
    code = packet[0] & 3
    if code == 0:
        return packet[:1] + frame_length(len(packet) - 1) + packet[1:]
    if code == 1:
        return packet[:1] + frame_length((len(packet) - 1) // 2) + packet[1:]
    if code == 2:
        first, offset = read_length(packet, 1)
        return packet[:offset] + frame_length(len(packet) - offset - first) + packet[offset:]
    count = packet[1] & 63
    vbr = packet[1] & 128
    offset = 2
    padding = 0
    if packet[1] & 64:
        while True:
            value = packet[offset]
            offset += 1
            padding += 254 if value == 255 else value
            if value != 255:
                break
    if not vbr:
        return packet[:offset] + frame_length((len(packet) - offset - padding) // count) + packet[offset:]
    total = 0
    for _ in range(count - 1):
        length, offset = read_length(packet, offset)
        total += length
    last = len(packet) - offset - padding - total
    return packet[:offset] + frame_length(last) + packet[offset:]


def main():
    out = os.path.join(sys.argv[1] if len(sys.argv) > 1 else os.path.join("core", "src", "test", "resources",
                                                                           "samples"), "wav")
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        def temp(name):
            return os.path.join(work, name)

        ffmpeg(*noise(44100), "-c:a", "libmp3lame", "-b:a", "64k", "-ac", "2", "-f", "wav",
               os.path.join(out, "mp3.wav"))
        ffmpeg(*noise(48000), "-c:a", "ac3", "-b:a", "128k", "-ac", "2", "-f", "wav", os.path.join(out, "ac3.wav"))
        ffmpeg(*noise(48000), "-c:a", "dca", "-strict", "-2", "-b:a", "768k", "-ac", "2", "-f", "wav",
               os.path.join(out, "dts.wav"))

        ffmpeg(*noise(44100), "-c:a", "aac", "-b:a", "64k", "-ac", "2", "-f", "latm", temp("a.latm"))
        latm = open(temp("a.latm"), "rb").read()
        open(os.path.join(out, "loas.wav"), "wb").write(wave(0x1602, 2, 44100, 8000, 1, 0, b"", latm))

        ffmpeg(*noise(44100), "-c:a", "aac", "-b:a", "64k", "-ac", "2", "-f", "adts", temp("a.aac"))
        frames = adts_frames(open(temp("a.aac"), "rb").read())
        raw = b"".join(block for _, block in frames)
        config = audio_specific_config(frames[0][0])
        open(os.path.join(out, "aac-raw.wav"), "wb").write(wave(0x00FF, 2, 44100, 8000, 1, 0, config, raw))

        ffmpeg(*noise(22050, 0.6), "-c:a", "flac", "-ac", "2", temp("a.flac"))
        flac = open(temp("a.flac"), "rb").read()
        if flac[:4] != b"fLaC" or flac[4] & 0x7F != 0:
            raise ValueError("unexpected FLAC layout")
        streaminfo = flac[8:42]
        position = 4
        while True:
            last = flac[position] & 0x80
            length = int.from_bytes(flac[position + 1:position + 4], "big")
            position += 4 + length
            if last:
                break
        open(os.path.join(out, "flac.wav"), "wb").write(wave(0xF1AC, 2, 22050, 0, 1, 24, streaminfo,
                                                             flac[position:]))

        ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=8000:duration=1", "-c:a", "libopencore_amrnb",
               "-b:a", "7.95k", "-ac", "1", temp("a.amr"))
        amr = open(temp("a.amr"), "rb").read()
        if not amr.startswith(b"#!AMR\n"):
            raise ValueError("unexpected AMR magic")
        open(os.path.join(out, "amr.wav"), "wb").write(wave(0x0057, 1, 8000, 1600, 1, 0, b"", amr[6:]))

        ffmpeg(*noise(48000), "-c:a", "libopus", "-b:a", "48k", "-vbr", "on", "-frame_duration", "40", "-ac", "2",
               temp("a.opus"))
        packets = ogg_packets(open(temp("a.opus"), "rb").read())
        if not packets[0].startswith(b"OpusHead") or not packets[1].startswith(b"OpusTags"):
            raise ValueError("unexpected Ogg Opus headers")
        body = b"".join(self_delimited(packet) for packet in packets[2:])
        open(os.path.join(out, "opus.wav"), "wb").write(wave(0x704F, 2, 48000, 6000, 1, 0, packets[0], body))

        ffmpeg(*noise(48000), "-c:a", "ac3", "-b:a", "192k", "-ac", "2", "-f", "spdif", temp("a.spdif"))
        bursts = open(temp("a.spdif"), "rb").read()
        open(os.path.join(out, "iec61937-ac3.wav"), "wb").write(wave(0x0001, 2, 48000, 192000, 4, 16, b"", bursts))

        # the ADPCM and telephony formats WavDemuxer reads itself, as ffmpeg's WAVE muxer writes them (libgsm, the
        # permissive GSM 06.10 library, encodes the GSM sample)
        ffmpeg(*noise(22050, 0.5), "-c:a", "adpcm_ima_wav", "-block_size", "512", "-ac", "2",
               os.path.join(out, "ima-adpcm.wav"))
        ffmpeg(*noise(8000, 0.5), "-c:a", "adpcm_ms", "-block_size", "256", "-ac", "1", os.path.join(out, "ms-adpcm.wav"))
        ffmpeg(*noise(8000, 0.5), "-c:a", "libgsm_ms", "-ac", "1", os.path.join(out, "gsm.wav"))
        ffmpeg(*noise(16000, 0.5), "-c:a", "g722", "-ac", "1", os.path.join(out, "g722.wav"))
        ffmpeg(*noise(8000, 0.5), "-c:a", "g726", "-b:a", "24k", "-ac", "1", os.path.join(out, "g726.wav"))

        ffmpeg(*noise(48000), "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-f", "adts", temp("b.aac"))
        ffmpeg("-i", temp("b.aac"), "-c:a", "copy", "-f", "spdif", temp("b.spdif"))
        bursts = open(temp("b.spdif"), "rb").read()
        open(os.path.join(out, "iec61937-aac.wav"), "wb").write(wave(0x0001, 2, 48000, 192000, 4, 16, b"", bursts))


if __name__ == "__main__":
    main()
