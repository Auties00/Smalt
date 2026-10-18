package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Loads the RFC 8251 Opus test vectors: downloads the official tarball once (pinned SHA-256),
 * unpacks it next to the cache, and parses the {@code opus_demo} bitstream format.
 */
final class OpusVectors {
    static final String TARBALL = "opus_testvectors-rfc8251.tar.gz";
    static final String URL = "https://opus-codec.org/static/testvectors/opus_testvectors-rfc8251.tar.gz";
    static final String SHA256 = "6b26a22f9ba87b2b836906a9bb7afec5f8e54d49553b1200382520ee6fedfa55";

    /** One packet of a {@code .bit} file. */
    record BitPacket(byte[] data, int finalRange) {
    }

    private static Path directory;

    private OpusVectors() {
    }

    /** Returns the directory holding the unpacked vectors, downloading them when needed. */
    static synchronized Path directory() {
        if (directory != null) {
            return directory;
        }
        var tarball = Conformance.fetch(TARBALL, URL, SHA256);
        var target = tarball.resolveSibling("opus_testvectors-rfc8251");
        if (!Files.isRegularFile(target.resolve("testvector12m.dec"))) {
            try {
                var temporary = Files.createTempDirectory(tarball.getParent(), "opus-vectors-");
                try (var input = new GZIPInputStream(Files.newInputStream(tarball), 1 << 16)) {
                    untar(input, temporary);
                }
                if (Files.isDirectory(target)) {
                    try (var files = Files.list(target)) {
                        for (var file : files.toList()) {
                            Files.delete(file);
                        }
                    }
                    Files.delete(target);
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        directory = target;
        return target;
    }

    private static void untar(InputStream input, Path target) throws IOException {
        var header = new byte[512];
        while (true) {
            if (input.readNBytes(header, 0, 512) < 512) {
                return;
            }
            var empty = true;
            for (var b : header) {
                if (b != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                return;
            }
            var name = new String(header, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
            var sizeField = new String(header, 124, 12, StandardCharsets.US_ASCII).trim().replace("\0", "");
            var size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField.trim(), 8);
            var type = header[156];
            var padded = (size + 511) / 512 * 512;
            if ((type == '0' || type == 0) && (name.endsWith(".bit") || name.endsWith(".dec"))) {
                var fileName = name.substring(name.lastIndexOf('/') + 1);
                var bytes = input.readNBytes((int) size);
                Files.write(target.resolve(fileName), bytes);
                input.skipNBytes(padded - size);
            } else {
                input.skipNBytes(padded);
            }
        }
    }

    /** Parses an {@code opus_demo} bitstream: per packet a BE length, a BE final range, the bytes. */
    static List<BitPacket> readBit(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            var packets = new ArrayList<BitPacket>();
            while (buffer.remaining() >= 8) {
                var length = buffer.getInt();
                var range = buffer.getInt();
                if (length < 0 || length > buffer.remaining()) {
                    throw new IllegalStateException("corrupt .bit file " + file);
                }
                var data = new byte[length];
                buffer.get(data);
                packets.add(new BitPacket(data, range));
            }
            return packets;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Reads a little-endian 16-bit PCM file. */
    static short[] readPcm16(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            var samples = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            return samples;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Converts float samples to 16-bit exactly as libopus's {@code FLOAT2INT16}. */
    static short[] toPcm16(float[] samples, int length) {
        var out = new short[length];
        for (var i = 0; i < length; i++) {
            var x = samples[i] * 32768.f;
            x = x > -32768 ? x : -32768;
            x = x < 32767 ? x : 32767;
            out[i] = (short) Math.rint(x);
        }
        return out;
    }

    /** Collects interleaved float samples of unknown total length. */
    static final class FloatSink {
        private float[] data = new float[1 << 16];
        private int size;

        void append(float[] samples, int offset, int length) {
            if (size + length > data.length) {
                data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + length));
            }
            System.arraycopy(samples, offset, data, size, length);
            size += length;
        }

        float[] data() {
            return data;
        }

        int size() {
            return size;
        }
    }
}
