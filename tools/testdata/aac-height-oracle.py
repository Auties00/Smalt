#!/usr/bin/env python3
"""Black-box oracle for the height_extension_element of an AAC program config element.

Builds AudioSpecificConfigs with a height extension and silent raw data blocks by hand, gives them to a libfdk-aac
decoder binary through its C entry points and prints the channel types the decoder reports (0x11 top front, 0x12 top
side, 0x13 top back, 0x21 bottom front). The hex strings it prints are the fixtures of AacHeightExtensionTest. It never
needs the fdk-aac source or headers, which must not be read: the library is not permissive.

The binary of the recorded run: mingw-w64-x86_64-fdk-aac-2.0.3-1-any.pkg.tar.zst from mirror.msys2.org (SHA-256
c537fa3ee0ea37d149b8a1031414f3bf42586b3fda91c694836c445743373279), bin/libfdk-aac-2.dll, on Windows x64. Keep it in its
own directory, outside the repository. That decoder refuses more than 8 channels, so the 12-channel 7.1.4 config is only
printed, and it refuses a wrong CRC or a reserved height, where Smalt reads a plain comment.

Usage: python -I aac-height-oracle.py <directory of libfdk-aac-2.dll>
       python -I aac-height-oracle.py --seed <output file>
The second form needs no binary and writes the valid-lc-pce-height.bin seed of MpegFuzzTest.aac.
"""
import ctypes
import os
import sys


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, value, count):
        for i in range(count - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def align(self):
        while len(self.bits) % 8:
            self.bits.append(0)

    def bytes(self):
        self.align()
        out = bytearray()
        for i in range(0, len(self.bits), 8):
            value = 0
            for bit in self.bits[i:i + 8]:
                value = value * 2 + bit
            out.append(value)
        return bytes(out)


def crc8(data, poly=0x07, init=0xFF):
    crc = init
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = ((crc << 1) ^ poly) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def height_ext(heights, sync=0xAC, poly=0x07, init=0xFF, cover_sync=True, flip=0):
    bits = Bits()
    bits.put(sync, 8)
    for height in heights:
        bits.put(height, 2)
    body = bits.bytes()
    return body + bytes([crc8(body if cover_sync else body[1:], poly, init) ^ flip])


def pce(bits, front, side, back, lfe, comment):
    bits.put(0, 4)  # element instance tag
    bits.put(1, 2)  # AAC LC
    bits.put(3, 4)  # 48 kHz
    bits.put(len(front), 4)
    bits.put(len(side), 4)
    bits.put(len(back), 4)
    bits.put(len(lfe), 2)
    bits.put(0, 3)
    bits.put(0, 4)
    bits.put(0, 3)
    for elements in (front, side, back):
        for pair, tag in elements:
            bits.put(1 if pair else 0, 1)
            bits.put(tag, 4)
    for tag in lfe:
        bits.put(tag, 4)
    bits.align()
    bits.put(len(comment), 8)
    for byte in comment:
        bits.put(byte, 8)


def asc(front, side, back, lfe, comment):
    bits = Bits()
    bits.put(2, 5)  # AAC LC
    bits.put(3, 4)  # 48 kHz
    bits.put(0, 4)  # channel configuration 0
    bits.put(0, 3)  # frameLengthFlag, dependsOnCoreCoder, extensionFlag
    pce(bits, front, side, back, lfe, comment)
    return bits.bytes()


def silent_ics(bits):
    bits.put(100, 8)  # global_gain
    bits.put(0, 1)  # reserved
    bits.put(0, 2)  # ONLY_LONG_SEQUENCE
    bits.put(0, 1)  # window shape
    bits.put(0, 6)  # max_sfb
    bits.put(0, 1)  # predictor_data_present
    bits.put(0, 3)  # pulse, TNS and gain control absent


def frame(elements):
    """Elements are (id, tag): 0 SCE, 1 CPE, 3 LFE."""
    bits = Bits()
    for kind, tag in elements:
        bits.put(kind, 3)
        bits.put(tag, 4)
        if kind == 1:
            bits.put(0, 1)  # common_window
            silent_ics(bits)
        silent_ics(bits)
    bits.put(7, 3)
    return bits.bytes()


def S(tag):
    return (False, tag)


def P(tag):
    return (True, tag)


FRONT_TOP = ([S(0), P(0), P(1)], [P(2)], [], [0], [(0, 0), (1, 0), (1, 1), (1, 2), (3, 0)])

LAYOUTS = [
    ("5.1 side + top front pair", [S(0), P(0), P(1)], [P(2)], [], [0], [0, 0, 1, 0], [(0, 0), (1, 0), (1, 1), (1, 2), (3, 0)]),
    ("5.1 back + top side pair", [S(0), P(0)], [P(1)], [P(2)], [0], [0, 0, 1, 0], [(0, 0), (1, 0), (1, 1), (1, 2), (3, 0)]),
    ("5.1 back + bottom front pair", [S(0), P(0), P(1)], [], [P(2)], [0], [0, 0, 2, 0], [(0, 0), (1, 0), (1, 1), (1, 2), (3, 0)]),
    ("stereo + top back pair", [P(0)], [], [P(1)], [], [0, 1], [(1, 0), (1, 1)]),
    ("3.0 + top front center", [S(0), P(0), S(1)], [], [], [], [0, 0, 1], [(0, 0), (1, 0), (0, 1)]),
    ("3.0 + top back center", [S(0), P(0)], [], [S(1)], [], [0, 0, 1], [(0, 0), (1, 0), (0, 1)]),
    ("3.0 + top center", [S(0), P(0)], [S(1)], [], [], [0, 0, 1], [(0, 0), (1, 0), (0, 1)]),
    ("stereo + bottom front center", [P(0), S(0)], [], [], [], [0, 2], [(1, 0), (0, 0)]),
]

COMMENTS = [
    ("no comment", b""),
    ("plain text", b"hello"),
    ("valid extension", height_ext([0, 0, 1, 0])),
    ("trailing bytes", height_ext([0, 0, 1, 0]) + b"xx"),
    ("top before normal", height_ext([0, 1, 0, 0])),
    ("CRC initial value 0x00", height_ext([0, 0, 1, 0], init=0x00)),
    ("CRC without the sync word", height_ext([0, 0, 1, 0], cover_sync=False)),
    ("CRC polynomial 0x1D", height_ext([0, 0, 1, 0], poly=0x1D)),
    ("flipped CRC bit", height_ext([0, 0, 1, 0], flip=0x01)),
    ("sync word 0xAD", height_ext([0, 0, 1, 0], sync=0xAD)),
    ("reserved height 3", height_ext([0, 0, 3, 0])),
]


class StreamInfo(ctypes.Structure):
    # The leading members only; the decoder owns the structure.
    _fields_ = [("sampleRate", ctypes.c_int), ("frameSize", ctypes.c_int), ("numChannels", ctypes.c_int),
                ("pChannelType", ctypes.POINTER(ctypes.c_int)), ("pChannelIndices", ctypes.POINTER(ctypes.c_ubyte))]


def load(directory):
    os.add_dll_directory(directory)
    lib = ctypes.CDLL(os.path.join(directory, "libfdk-aac-2.dll"))
    lib.aacDecoder_Open.restype = ctypes.c_void_p
    lib.aacDecoder_Open.argtypes = [ctypes.c_int, ctypes.c_uint]
    lib.aacDecoder_ConfigRaw.argtypes = [ctypes.c_void_p] * 3
    lib.aacDecoder_Fill.argtypes = [ctypes.c_void_p] * 4
    lib.aacDecoder_DecodeFrame.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_uint]
    lib.aacDecoder_GetStreamInfo.restype = ctypes.POINTER(StreamInfo)
    lib.aacDecoder_GetStreamInfo.argtypes = [ctypes.c_void_p]
    lib.aacDecoder_SetParam.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    lib.aacDecoder_Close.argtypes = [ctypes.c_void_p]
    return lib


def probe(lib, config, data):
    handle = lib.aacDecoder_Open(0, 1)  # raw packets, one layer
    lib.aacDecoder_SetParam(handle, 0x12, 8)  # up to 8 output channels, so the decoder does not downmix
    config_buffer = (ctypes.c_ubyte * len(config)).from_buffer_copy(config)
    config_pointer = ctypes.cast(config_buffer, ctypes.POINTER(ctypes.c_ubyte))
    config_size = ctypes.c_uint(len(config))
    error = lib.aacDecoder_ConfigRaw(handle, ctypes.byref(config_pointer), ctypes.byref(config_size))
    if error:
        lib.aacDecoder_Close(handle)
        return "config refused (0x%x)" % error
    output = (ctypes.c_short * (8 * 2048 * 2))()
    result = None
    for _ in range(3):
        data_buffer = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
        data_pointer = ctypes.cast(data_buffer, ctypes.POINTER(ctypes.c_ubyte))
        size = ctypes.c_uint(len(data))
        valid = ctypes.c_uint(len(data))
        lib.aacDecoder_Fill(handle, ctypes.byref(data_pointer), ctypes.byref(size), ctypes.byref(valid))
        error = lib.aacDecoder_DecodeFrame(handle, output, len(output), 0)
        info = lib.aacDecoder_GetStreamInfo(handle).contents
        result = "error 0x%x, channel types %s" % (error, [hex(info.pChannelType[i]) for i in range(info.numChannels)])
    lib.aacDecoder_Close(handle)
    return result


def write_seed(path):
    # The FuzzPackets audio format: options, rate, channels, bits, the configuration, then marked packets.
    front, side, back, lfe, elements = FRONT_TOP
    header = bytes([0]) + (48000).to_bytes(3, "big") + bytes([8, 16])
    packet = b"FUZZ-TAG\x01" + frame(elements)
    with open(path, "wb") as output:
        output.write(header + asc(front, side, back, lfe, height_ext([0, 0, 1, 0])) + packet * 3)


def main():
    if sys.argv[1] == "--seed":
        write_seed(sys.argv[2])
        return
    lib = load(sys.argv[1])
    for name, front, side, back, lfe, heights, elements in LAYOUTS:
        config = asc(front, side, back, lfe, height_ext(heights))
        print("%-32s %s\n    %s" % (name, config.hex(), probe(lib, config, frame(elements))))
    front, side, back, lfe, elements = ([S(0), P(0), P(1)], [], [P(2)], [0], [(0, 0), (1, 0), (1, 1), (1, 2), (3, 0)])
    for name, comment in COMMENTS:
        config = asc(front, side, back, lfe, comment)
        print("%-32s %s\n    %s" % (name, config.hex(), probe(lib, config, frame(elements))))
    print("7.1.4 (not decoded) " + asc([S(0), P(0), P(1)], [P(2)], [P(3), P(4)], [0], height_ext([0, 0, 1, 0, 0, 1])).hex())


main()
