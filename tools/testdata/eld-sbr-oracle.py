#!/usr/bin/env python3
"""Writes the oracle files of smalt-mpeg's AacLowDelaySbrTest.

1. libxaac's decodes of the AAC-ELD streams with low delay SBR of ISO/IEC 14496-26 (er_eld_sbr_*), which ship
   without reference waveforms: <conformance cache>/eld-sbr-oracle/<stream>.wav.
2. AAC-ELD streams with low delay SBR and 480-sample frames, which no conformance stream covers, encoded from
   synthetic signals by libxaac's encoder and decoded by libxaac's decoder: <name>.raw (the AudioSpecificConfig
   followed by the access units), <name>.meta (their sizes) and <name>.wav in
   <conformance cache>/eld-sbr-oracle/encoded.
3. With --fdk: the conformance streams and encodes of the Fraunhofer FDK AAC encoder (480-sample frames at dual
   rate and downsampled, which libxaac does not encode) decoded by the FDK AAC decoder into
   <conformance cache>/eld-sbr-fdk, and the dual-rate ones also by libxaac into eld-sbr-oracle/encoded.
   On Windows the FDK library is the binary DLL of the MSYS2 package mingw-w64-ucrt-x86_64-fdk-aac 2.0.3-1
   (pinned by SHA-256), downloaded unless --fdk names a library; elsewhere --fdk names the system's
   libfdk-aac. The library is driven through its public API as a black box.

libxaac is downloaded at commit 2fbadd57bca46693b7a077bcf1513131106d960c (Apache-2.0, pinned by SHA-256) into
<work directory>/download and its xaacdec and xaacenc test applications are built with CMake, Ninja and gcc (on
Windows from an MSYS2 UCRT64 shell, with a Windows Python 3.14 or later for --fdk). The encoded streams are
deterministic for a given build; ENCODED_SHA256 pins those of the reference build (gcc 15, x86-64), and a
different digest is reported, not rejected, since the tests compare each stream with its own oracle decode.

    python -I tools/testdata/eld-sbr-oracle.py <work directory> [--fdk [library]]
"""
import ctypes
import hashlib
import math
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

COMMIT = '2fbadd57bca46693b7a077bcf1513131106d960c'
URL = 'https://github.com/ittiam-systems/libxaac/archive/' + COMMIT + '.tar.gz'
SHA256 = '9154b93812a11ea9352c609077fc0c276914dd1fbaf0173f0d859529763f8844'

FDK_URL = 'https://repo.msys2.org/mingw/ucrt64/mingw-w64-ucrt-x86_64-fdk-aac-2.0.3-1-any.pkg.tar.zst'
FDK_SHA256 = 'e08846148086d898d4affa0303a3fcccd0dc97017487017e4cb18dab95a10150'

STREAMS = ['er_eld_sbr_%s_%d_%s' % (signal, channels, rate) for signal in ('e', 'gh', 'i', 's')
           for channels in (1, 2) for rate in ('32', '44', '48')] + ['er_eld_sbr_twi_1_24', 'er_eld_sbr_twi_1_48']

# name: (sample rate, channels, bitrate, frame length, SBR ratio: 2 dual rate, 1 downsampled)
LIBXAAC_ENCODES = {
    'xaac480_m48': (48000, 1, 32000, 480, 2),
    'xaac480_s48': (48000, 2, 48000, 480, 2),
    'xaac480_m44': (44100, 1, 32000, 480, 2),
    'xaac480_m32': (32000, 1, 24000, 480, 2),
    'xaac512_m48': (48000, 1, 32000, 512, 2),
}
FDK_ENCODES = {
    'fdk480d_m48': (48000, 1, 32000, 480, 2),
    'fdk480d_s48': (48000, 2, 48000, 480, 2),
    'fdk480s_m48': (48000, 1, 64000, 480, 1),
    'fdk480s_s48': (48000, 2, 96000, 480, 1),
    'fdk480s_m32': (32000, 1, 32000, 480, 1),
}
ENCODED_SHA256 = {
    'xaac480_m48': '03b36781a24a6b87a432cdddbde0df83a4652c23b3c8cc5561634f51a249943d',
    'xaac480_s48': 'b285ebd142cdee22dfdf777e3336da26af6bd5c2cc7f88c2fa5b62606b93ad1e',
    'xaac480_m44': '7e1a2effe3653fae809aac918da896a2cff6ac590505fc30cfa13915b6366277',
    'xaac480_m32': '70c952835b5acdd37904b2ebb9dda1b35fa99ea8cf8bd48e8eec541d26293dd1',
    'xaac512_m48': '077d84be3a9fd53a15c67dfddc7a050b0b3382fff70af39040d0f10e6a35a87a',
    'fdk480d_m48': 'f072604a742c1eacf8cb51b706779b3342b7757cd4955e7c9a1861aee8690619',
    'fdk480d_s48': 'b95740131b1f75742acfb19b2128170ec094ff0724213bf96a94aa89dcf60b65',
    'fdk480s_m48': 'b042b7e20c4f06e6b9ed957faeaa2f3177ea688350590654b874c07271782256',
    'fdk480s_s48': '183471a905bfddca63d96914d91e137e657e947ab7c144f0fec12806071ff816',
    'fdk480s_m32': '642e1b0a478d3619633d07796581b15cad6d5a275b632bb20dec9da418512df5',
}


def vector(name):
    for row in testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS):
        if row['name'] == name:
            path = os.path.join(testdata.conformance_dir(), name)
            if not testdata.valid(path, row['sha256']):
                testdata.download(row['url'], path, row['sha256'])
            return path
    raise SystemExit(name + ' is not in vectors.tsv')


def fetch(url, target, expected):
    if not os.path.exists(target):
        urllib.request.urlretrieve(url, target)
    digest = hashlib.sha256(open(target, 'rb').read()).hexdigest()
    if digest != expected:
        raise SystemExit(target + ' has SHA-256 ' + digest + ', expected ' + expected)


def build(work):
    download = os.path.join(work, 'download')
    os.makedirs(download, exist_ok=True)
    tarball = os.path.join(download, 'libxaac-' + COMMIT + '.tar.gz')
    fetch(URL, tarball, SHA256)
    source = os.path.join(download, 'libxaac-' + COMMIT)
    if not os.path.isdir(source):
        with tarfile.open(tarball) as archive:
            archive.extractall(download, filter='data')
    binary = os.path.join(work, 'build')
    subprocess.check_call(['cmake', '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', '-DBUILD64=ON',
                           '-DCMAKE_C_COMPILER=gcc', '-S', source, '-B', binary])
    subprocess.check_call(['cmake', '--build', binary, '--target', 'xaacdec', 'xaacenc'])
    suffix = '.exe' if os.name == 'nt' else ''
    executables = [os.path.join(binary, name + suffix) for name in ('xaacdec', 'xaacenc')]
    for executable in executables:
        if not os.path.exists(executable):
            raise SystemExit('the build produced no ' + executable)
    return executables


def boxes(data, start, end):
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack('>I4s', data[position:position + 8])
        header = 8
        if size == 1:
            size = struct.unpack('>Q', data[position + 8:position + 16])[0]
            header = 16
        elif size == 0:
            size = end - position
        yield kind.decode('latin-1'), position + header, position + size
        position += size


def find(data, start, end, path):
    for kind, body, stop in boxes(data, start, end):
        if kind == path[0]:
            return (body, stop) if len(path) == 1 else find(data, body, stop, path[1:])
    return None


def descriptor(data, position):
    tag = data[position]
    position += 1
    length = 0
    while True:
        byte = data[position]
        position += 1
        length = length << 7 | byte & 0x7F
        if not byte & 0x80:
            return tag, position, length


def audio_specific_config(data, stbl):
    stsd = find(data, stbl[0], stbl[1], ['stsd'])
    entry = stsd[0] + 8
    esds = find(data, entry + 36, entry + struct.unpack('>I', data[entry:entry + 4])[0], ['esds'])
    position = esds[0] + 4
    _, position, _ = descriptor(data, position)
    flags = data[position + 2]
    position += 3
    if flags & 0x80:
        position += 2
    if flags & 0x40:
        position += 1 + data[position]
    if flags & 0x20:
        position += 2
    _, position, _ = descriptor(data, position)
    _, position, length = descriptor(data, position + 13)
    return data[position:position + length]


def access_units(path):
    data = open(path, 'rb').read()
    moov = find(data, 0, len(data), ['moov'])
    trak = next((body, stop) for kind, body, stop in boxes(data, moov[0], moov[1]) if kind == 'trak')
    stbl = find(data, trak[0], trak[1], ['mdia', 'minf', 'stbl'])
    stsz = find(data, stbl[0], stbl[1], ['stsz'])
    fixed, count = struct.unpack('>II', data[stsz[0] + 4:stsz[0] + 12])
    sizes = [fixed] * count if fixed else list(struct.unpack('>%dI' % count, data[stsz[0] + 12:stsz[0] + 12 + 4 * count]))
    stsc = find(data, stbl[0], stbl[1], ['stsc'])
    entries = struct.unpack('>I', data[stsc[0] + 4:stsc[0] + 8])[0]
    runs = [struct.unpack('>III', data[stsc[0] + 8 + 12 * i:stsc[0] + 20 + 12 * i]) for i in range(entries)]
    stco = find(data, stbl[0], stbl[1], ['stco'])
    if stco:
        chunks = struct.unpack('>I', data[stco[0] + 4:stco[0] + 8])[0]
        offsets = struct.unpack('>%dI' % chunks, data[stco[0] + 8:stco[0] + 8 + 4 * chunks])
    else:
        co64 = find(data, stbl[0], stbl[1], ['co64'])
        chunks = struct.unpack('>I', data[co64[0] + 4:co64[0] + 8])[0]
        offsets = struct.unpack('>%dQ' % chunks, data[co64[0] + 8:co64[0] + 8 + 8 * chunks])
    units = []
    for chunk, offset in enumerate(offsets):
        per_chunk = 0
        for first, samples, _ in runs:
            if chunk + 1 >= first:
                per_chunk = samples
        for _ in range(per_chunk):
            if len(units) == count:
                break
            units.append(data[offset:offset + sizes[len(units)]])
            offset += sizes[len(units) - 1]
    return audio_specific_config(data, stbl), units


def write_raw(directory, name, config, units, rate):
    raw = os.path.join(directory, name + '.raw')
    meta = os.path.join(directory, name + '.meta')
    with open(raw, 'wb') as out:
        out.write(config)
        for unit in units:
            out.write(unit)
    with open(meta, 'w', newline='\n') as out:
        out.write('-dec_info_init:%d\n-g_track_count:1\n-ia_mp4_stsz_entries:%d\n' % (len(config), len(units)))
        if rate:
            out.write('-media_time_scale:%d\n' % rate)
        for unit in units:
            out.write('-ia_mp4_stsz_size:%d\n' % len(unit))
    return raw, meta


def read_raw(raw, meta):
    data = open(raw, 'rb').read()
    config_length = 0
    sizes = []
    for line in open(meta):
        key, _, value = line.strip().partition(':')
        if key == '-dec_info_init':
            config_length = int(value)
        elif key == '-ia_mp4_stsz_size':
            sizes.append(int(value))
    units = []
    position = config_length
    for size in sizes:
        units.append(data[position:position + size])
        position += size
    return data[:config_length], units


def signal(rate, channels, seconds=6):
    """A deterministic test signal: harmonic notes with decaying attacks every half second, a bright noise floor
    and short noise bursts, about -10 dBFS, a different note sequence in each channel."""
    notes = [[220, 330, 262, 392, 294, 440, 349, 523, 247, 370, 196, 415],
             [330, 196, 440, 262, 523, 294, 392, 220, 415, 247, 349, 370]]
    total = int(rate * seconds)
    planes = []
    seed = 12345
    for channel in range(channels):
        plane = [0.0] * total
        previous = 0.0
        for n in range(total):
            t = n / rate
            note = notes[channel][int(t * 2) % 12]
            age = t - int(t * 2) / 2
            envelope = 0.15 * math.exp(-3.0 * age) * min(1.0, age * 400)
            value = 0.0
            k = 1
            while k * note < 15000 and k <= 40:
                value += math.sin(2 * math.pi * k * note * t + 0.3 * k) / k
                k += 1
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            noise = seed / 0x3FFFFFFF - 1.0
            bright = noise - previous
            previous = noise
            burst = 0.12 if (t % 1.5) < 0.02 else 0.0
            plane[n] = envelope * value + 0.004 * bright + burst * noise
        planes.append(plane)
    return planes


def write_wav(path, planes, rate):
    channels = len(planes)
    frames = len(planes[0])
    body = bytearray()
    for n in range(frames):
        for plane in planes:
            body += struct.pack('<h', max(-32768, min(32767, int(round(plane[n] * 32767)))))
    with open(path, 'wb') as out:
        out.write(b'RIFF' + struct.pack('<I', 36 + len(body)) + b'WAVEfmt ')
        out.write(struct.pack('<IHHIIHH', 16, 1, channels, rate, rate * channels * 2, channels * 2, 16))
        out.write(b'data' + struct.pack('<I', len(body)) + body)


def check_digest(name, raw):
    digest = hashlib.sha256(open(raw, 'rb').read()).hexdigest()
    expected = ENCODED_SHA256.get(name)
    if expected and digest != expected:
        print('note:', name, 'differs from the reference build (SHA-256', digest + ')')
    elif not expected:
        print(name, digest)


def libxaac_encodes(encoder, decoder, work, directory):
    inputs = os.path.join(work, 'inputs')
    os.makedirs(inputs, exist_ok=True)
    for name, (rate, channels, bitrate, frame, ratio) in LIBXAAC_ENCODES.items():
        wav = os.path.join(inputs, name + '.wav')
        write_wav(wav, signal(rate, channels), rate)
        es = os.path.join(inputs, name + '.es')
        subprocess.check_call([encoder, '-ifile:' + wav, '-ofile:' + es, '-aot:39', '-framesize:%d' % frame,
                               '-br:%d' % bitrate], stdout=subprocess.DEVNULL)
        config, units = read_raw(es, es[:-3] + '.txt')
        raw, meta = write_raw(directory, name, config, units, rate)
        check_digest(name, raw)
        target = os.path.join(directory, name + '.wav')
        subprocess.check_call([decoder, '-ifile:' + raw, '-imeta:' + meta, '-ofile:' + target, '-mp4:1'],
                              stdout=subprocess.DEVNULL)
        print('wrote', target)


class Fdk:
    """The FDK AAC library through its public API."""

    class BufDesc(ctypes.Structure):
        _fields_ = [('numBufs', ctypes.c_int), ('bufs', ctypes.POINTER(ctypes.c_void_p)),
                    ('bufferIdentifiers', ctypes.POINTER(ctypes.c_int)), ('bufSizes', ctypes.POINTER(ctypes.c_int)),
                    ('bufElSizes', ctypes.POINTER(ctypes.c_int))]

    class InArgs(ctypes.Structure):
        _fields_ = [('numInSamples', ctypes.c_int), ('numAncBytes', ctypes.c_int)]

    class OutArgs(ctypes.Structure):
        _fields_ = [('numOutBytes', ctypes.c_int), ('numInSamples', ctypes.c_int), ('numAncBytes', ctypes.c_int),
                    ('bitResState', ctypes.c_int)]

    class Info(ctypes.Structure):
        _fields_ = [('maxOutBufBytes', ctypes.c_uint), ('maxAncBytes', ctypes.c_uint),
                    ('inBufFillLevel', ctypes.c_uint), ('inputChannels', ctypes.c_uint),
                    ('frameLength', ctypes.c_uint), ('nDelay', ctypes.c_uint), ('nDelayCore', ctypes.c_uint),
                    ('confBuf', ctypes.c_ubyte * 64), ('confSize', ctypes.c_uint)]

    def __init__(self, library):
        if os.name == 'nt':
            os.add_dll_directory(os.path.dirname(os.path.abspath(library)))
            gcc = shutil.which('gcc')
            if gcc:
                os.add_dll_directory(os.path.dirname(gcc))
        self.lib = ctypes.CDLL(library)
        self.lib.aacDecoder_Open.restype = ctypes.c_void_p
        self.lib.aacDecoder_Open.argtypes = [ctypes.c_int, ctypes.c_uint]
        self.lib.aacDecoder_ConfigRaw.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_char_p),
                                                  ctypes.POINTER(ctypes.c_uint)]
        self.lib.aacDecoder_Fill.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_char_p),
                                             ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]
        self.lib.aacDecoder_DecodeFrame.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_short), ctypes.c_int,
                                                    ctypes.c_uint]
        self.lib.aacDecoder_GetStreamInfo.restype = ctypes.POINTER(ctypes.c_int * 3)
        self.lib.aacDecoder_GetStreamInfo.argtypes = [ctypes.c_void_p]
        self.lib.aacDecoder_SetParam.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        self.lib.aacDecoder_Close.argtypes = [ctypes.c_void_p]

    def decode(self, config, units, target):
        """Decodes raw access units into 16-bit PCM, the PCM limiter disabled; a unit that fails to decode is
        an error."""
        handle = self.lib.aacDecoder_Open(0, 1)
        self.lib.aacDecoder_SetParam(handle, 0x0004, 0)
        error = self.lib.aacDecoder_ConfigRaw(handle, (ctypes.c_char_p * 1)(config), (ctypes.c_uint * 1)(len(config)))
        if error:
            raise SystemExit('aacDecoder_ConfigRaw failed: 0x%x' % error)
        pcm = (ctypes.c_short * (8 * 4096))()
        out = bytearray()
        rate = channels = 0
        for index, unit in enumerate(units):
            valid = ctypes.c_uint(len(unit))
            self.lib.aacDecoder_Fill(handle, (ctypes.c_char_p * 1)(unit), (ctypes.c_uint * 1)(len(unit)),
                                     ctypes.byref(valid))
            error = self.lib.aacDecoder_DecodeFrame(handle, pcm, len(pcm), 0)
            if error:
                raise SystemExit('%s: unit %d does not decode: 0x%x' % (target, index, error))
            rate, size, channels = self.lib.aacDecoder_GetStreamInfo(handle).contents[:3]
            out += bytes(memoryview(pcm).cast('B')[:size * channels * 2])
        self.lib.aacDecoder_Close(handle)
        with open(target, 'wb') as wav:
            wav.write(b'RIFF' + struct.pack('<I', 36 + len(out)) + b'WAVEfmt ')
            wav.write(struct.pack('<IHHIIHH', 16, 1, channels, rate, rate * channels * 2, channels * 2, 16))
            wav.write(b'data' + struct.pack('<I', len(out)) + out)

    def encode(self, planes, rate, bitrate, frame, ratio):
        """Encodes AAC-ELD with low delay SBR into raw access units."""
        channels = len(planes)
        pcm = bytearray()
        for n in range(len(planes[0])):
            for plane in planes:
                pcm += struct.pack('<h', max(-32768, min(32767, int(round(plane[n] * 32767)))))
        handle = ctypes.c_void_p()
        if self.lib.aacEncOpen(ctypes.byref(handle), 0, channels):
            raise SystemExit('aacEncOpen failed')
        for param, value in ((0x0100, 39), (0x0103, rate), (0x0106, channels), (0x0101, bitrate), (0x0300, 0),
                             (0x0105, frame), (0x0104, 1), (0x0108, ratio)):
            error = self.lib.aacEncoder_SetParam(handle, param, value)
            if error:
                raise SystemExit('aacEncoder_SetParam 0x%x = %d failed: 0x%x' % (param, value, error))
        error = self.lib.aacEncEncode(handle, None, None, None, None)
        if error:
            raise SystemExit('aacEncEncode initialization failed: 0x%x' % error)
        info = Fdk.Info()
        self.lib.aacEncInfo(handle, ctypes.byref(info))
        config = bytes(info.confBuf[:info.confSize])
        step = info.frameLength * channels * 2
        out = (ctypes.c_ubyte * 8192)()
        units = []
        position = 0
        while True:
            chunk = bytes(pcm[position:position + step])
            position += step
            buffer = ctypes.create_string_buffer(chunk, max(len(chunk), 2))
            source = Fdk.BufDesc(1, (ctypes.c_void_p * 1)(ctypes.cast(buffer, ctypes.c_void_p)),
                                 (ctypes.c_int * 1)(0), (ctypes.c_int * 1)(len(chunk)), (ctypes.c_int * 1)(2))
            sink = Fdk.BufDesc(1, (ctypes.c_void_p * 1)(ctypes.cast(out, ctypes.c_void_p)), (ctypes.c_int * 1)(3),
                               (ctypes.c_int * 1)(len(out)), (ctypes.c_int * 1)(1))
            arguments = Fdk.InArgs(len(chunk) // 2 if chunk else -1, 0)
            results = Fdk.OutArgs()
            error = self.lib.aacEncEncode(handle, ctypes.byref(source), ctypes.byref(sink), ctypes.byref(arguments),
                                          ctypes.byref(results))
            if error == 0x80:
                break
            if error:
                raise SystemExit('aacEncEncode failed: 0x%x' % error)
            if results.numOutBytes:
                units.append(bytes(out[:results.numOutBytes]))
            elif not chunk:
                break
        self.lib.aacEncClose(ctypes.byref(handle))
        return config, units


def fdk_library(work, named):
    if named:
        return named
    if os.name != 'nt':
        raise SystemExit('--fdk needs the path of libfdk-aac outside Windows')
    download = os.path.join(work, 'download')
    os.makedirs(download, exist_ok=True)
    package = os.path.join(download, os.path.basename(FDK_URL))
    fetch(FDK_URL, package, FDK_SHA256)
    extracted = os.path.join(download, 'fdk-aac')
    library = os.path.join(extracted, 'ucrt64', 'bin', 'libfdk-aac-2.dll')
    if not os.path.exists(library):
        with tarfile.open(package, 'r:zst') as archive:
            archive.extractall(extracted, filter='data')
    return library


def fdk_oracle(library, decoder, encoded):
    fdk = Fdk(library)
    directory = os.path.join(testdata.conformance_dir(), 'eld-sbr-fdk')
    os.makedirs(directory, exist_ok=True)
    for stream in STREAMS:
        config, units = access_units(vector('mpeg4audio-' + stream + '.mp4'))
        target = os.path.join(directory, stream + '.wav')
        fdk.decode(config, units, target)
        print('wrote', target)
    for name, (rate, channels, bitrate, frame, ratio) in FDK_ENCODES.items():
        config, units = fdk.encode(signal(rate, channels), rate, bitrate, frame, ratio)
        raw, meta = write_raw(encoded, name, config, units, rate)
        check_digest(name, raw)
        target = os.path.join(directory, name + '.wav')
        fdk.decode(config, units, target)
        print('wrote', target)
        if ratio == 2:
            target = os.path.join(encoded, name + '.wav')
            subprocess.check_call([decoder, '-ifile:' + raw, '-imeta:' + meta, '-ofile:' + target, '-mp4:1'],
                                  stdout=subprocess.DEVNULL)
            print('wrote', target)


def main():
    arguments = sys.argv[1:]
    if not arguments or arguments[0].startswith('-'):
        raise SystemExit(__doc__)
    work = os.path.abspath(arguments[0])
    use_fdk = '--fdk' in arguments
    named = None
    if use_fdk:
        rest = arguments[arguments.index('--fdk') + 1:]
        named = rest[0] if rest else None
    decoder, encoder = build(work)
    directory = os.path.join(testdata.conformance_dir(), 'eld-sbr-oracle')
    os.makedirs(directory, exist_ok=True)
    inputs = os.path.join(work, 'inputs')
    os.makedirs(inputs, exist_ok=True)
    for stream in STREAMS:
        config, units = access_units(vector('mpeg4audio-' + stream + '.mp4'))
        raw, meta = write_raw(inputs, stream, config, units, 0)
        target = os.path.join(directory, stream + '.wav')
        subprocess.check_call([decoder, '-ifile:' + raw, '-imeta:' + meta, '-ofile:' + target, '-mp4:1'],
                              stdout=subprocess.DEVNULL)
        print('wrote', target)
    encoded = os.path.join(directory, 'encoded')
    os.makedirs(encoded, exist_ok=True)
    libxaac_encodes(encoder, decoder, work, encoded)
    if use_fdk:
        fdk_oracle(fdk_library(work, named), decoder, encoded)


if __name__ == '__main__':
    main()
