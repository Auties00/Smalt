package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Helpers of the AV1 tests: reading the temporal units of a stream, rewriting low-overhead temporal units in the
 * length-delimited format of the AV1 specification's Annex B, and hashing pictures the way libaom hashes frames
 * (8-bit samples as bytes, deeper ones as 16-bit little-endian values, mid-gray chroma for monochrome frames).
 */
final class Av1Streams {
    private Av1Streams() {
        throw new AssertionError();
    }

    /** A temporal unit read from a container, with its timestamp. */
    record Unit(byte[] data, long pts) {
    }

    /** Returns the AV1 track of a container file, read by its demuxer. */
    static VideoTrack track(byte[] file) {
        try (var source = MediaSource.of(file);
             var demuxer = Containers.openDemuxer(source)) {
            return (VideoTrack) demuxer.tracks().getFirst();
        }
    }

    /** Returns the packets of the first track of a container file, in order. */
    static List<Unit> units(byte[] file) {
        var units = new ArrayList<Unit>();
        try (var source = MediaSource.of(file);
             var demuxer = Containers.openDemuxer(source)) {
            for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                units.add(new Unit(packet.data(), packet.pts()));
            }
        }
        return units;
    }

    /** Returns the bytes of a conformance vector listed in {@code tools/testdata/vectors.tsv}. */
    static byte[] vector(String name) {
        try {
            return Files.readAllBytes(Conformance.fetchListed(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the per-frame MD5 references of a libaom test vector. */
    static List<String> referenceMd5(String name) {
        try {
            var md5 = new ArrayList<String>();
            for (var line : Files.readAllLines(Conformance.fetchListed(name + ".md5"))) {
                if (!line.isBlank()) {
                    md5.add(line.strip().split("[ \t]+")[0]);
                }
            }
            return md5;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns a track of codec AV1 without a codec configuration. */
    static VideoTrack bareTrack() {
        return VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 30)).build();
    }

    /**
     * Rewrites a low-overhead temporal unit in the length-delimited format: {@code temporal_unit_size}, then one frame
     * unit per frame (a frame header or frame OBU starts a new frame unit once the current one holds a frame header),
     * each OBU preceded by its {@code obu_length}. The random source decides, OBU by OBU, whether the OBU keeps its
     * {@code obu_size} (with the matching length) or drops it, and pads every size field with redundant
     * continuation bytes up to the 8 bytes a {@code leb128()} may take.
     */
    static byte[] toAnnexB(byte[] unit, SplittableRandom random) {
        return toAnnexB(unit, random, random);
    }

    /**
     * Rewrites a low-overhead temporal unit in the length-delimited format as {@link #toAnnexB(byte[],
     * SplittableRandom)} does, the size fields padded only when {@code padding} is not {@code null}.
     */
    static byte[] toAnnexB(byte[] unit, SplittableRandom random, SplittableRandom padding) {
        var frames = new ArrayList<ByteArrayOutputStream>();
        var current = new ByteArrayOutputStream();
        var hasFrameHeader = false;
        var position = 0;
        while (position < unit.length) {
            var header = unit[position] & 0xFF;
            var type = (header >> 3) & 0xF;
            var extension = (header & 0x4) != 0;
            var headerLength = extension ? 2 : 1;
            if ((header & 0x2) == 0) {
                throw new IllegalArgumentException("OBU without obu_size at " + position);
            }
            var size = leb128(unit, position + headerLength);
            var payloadStart = (int) size[1];
            var payloadEnd = Math.toIntExact(payloadStart + size[0]);
            if ((type == 3 || type == 6) && hasFrameHeader) {
                frames.add(current);
                current = new ByteArrayOutputStream();
                hasFrameHeader = false;
            }
            hasFrameHeader |= type == 3 || type == 6;
            var obu = new ByteArrayOutputStream();
            if (random.nextBoolean()) {
                obu.write(unit, position, headerLength);
                writeLeb128(obu, payloadEnd - payloadStart, padding);
            } else {
                obu.write(header & ~0x2);
                if (extension) {
                    obu.write(unit[position + 1]);
                }
            }
            obu.write(unit, payloadStart, payloadEnd - payloadStart);
            writeLeb128(current, obu.size(), padding);
            current.writeBytes(obu.toByteArray());
            position = payloadEnd;
        }
        frames.add(current);
        var body = new ByteArrayOutputStream();
        for (var frame : frames) {
            writeLeb128(body, frame.size(), padding);
            body.writeBytes(frame.toByteArray());
        }
        var out = new ByteArrayOutputStream();
        writeLeb128(out, body.size(), padding);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** Splits a raw length-delimited stream into its temporal units, each with its {@code temporal_unit_size}. */
    static List<byte[]> splitAnnexB(byte[] stream) {
        var units = new ArrayList<byte[]>();
        var position = 0;
        while (position < stream.length) {
            var size = leb128(stream, position);
            var end = Math.toIntExact(size[1] + size[0]);
            units.add(Arrays.copyOfRange(stream, position, end));
            position = end;
        }
        return units;
    }

    /** Reads a {@code leb128()} value: returns {value, index after it}. */
    static long[] leb128(byte[] data, int position) {
        long value = 0;
        for (var i = 0; i < 8; i++) {
            var b = data[position + i] & 0xFF;
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return new long[]{value, position + i + 1};
            }
        }
        throw new IllegalArgumentException("leb128 longer than 8 bytes at " + position);
    }

    /** Writes a {@code leb128()} value, padded with redundant continuation bytes when the random source says so. */
    static void writeLeb128(ByteArrayOutputStream out, long value, SplittableRandom random) {
        var minimal = 1;
        while (value >>> (7 * minimal) != 0) {
            minimal++;
        }
        var length = random == null || random.nextInt(4) != 0 ? minimal : random.nextInt(minimal, 9);
        for (var i = 0; i < length; i++) {
            var b = (int) ((value >>> (7 * i)) & 0x7F);
            out.write(i + 1 < length ? b | 0x80 : b);
        }
    }

    /** Hashes a picture as libaom hashes a frame. */
    static String md5(Picture picture) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        digest.update(raw(picture, true));
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Returns the planes of a picture in the layout of aomdec's raw output: luma, then Cb and Cr, each row tight;
     * 16-bit little-endian samples above 8 bits; for a monochrome picture, mid-gray 4:2:0 chroma when
     * {@code grayChroma} is set and none otherwise.
     */
    static byte[] raw(Picture picture, boolean grayChroma) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case YuvPicture yuv -> {
                for (var y = 0; y < yuv.height(); y++) {
                    out.write(yuv.y(), y * yuv.yStride(), yuv.width());
                }
                if (yuv.chromaFormat().hasChroma()) {
                    for (var plane : new byte[][]{yuv.u(), yuv.v()}) {
                        for (var y = 0; y < yuv.chromaHeight(); y++) {
                            out.write(plane, y * yuv.uvStride(), yuv.chromaWidth());
                        }
                    }
                } else if (grayChroma) {
                    var gray = new byte[2 * ((yuv.width() + 1) >> 1) * ((yuv.height() + 1) >> 1)];
                    Arrays.fill(gray, (byte) 128);
                    out.writeBytes(gray);
                }
            }
            case Yuv16Picture yuv -> {
                write16(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    write16(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    write16(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                } else if (grayChroma) {
                    var chromaWidth = (yuv.width() + 1) >> 1;
                    var chromaHeight = (yuv.height() + 1) >> 1;
                    var gray = new short[chromaWidth * chromaHeight];
                    Arrays.fill(gray, (short) (1 << (yuv.bitDepth() - 1)));
                    write16(out, gray, chromaWidth, chromaWidth, chromaHeight);
                    write16(out, gray, chromaWidth, chromaWidth, chromaHeight);
                }
            }
            default -> throw new IllegalArgumentException("not a YUV picture: " + picture);
        }
        return out.toByteArray();
    }

    private static void write16(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = plane[y * stride + x];
                out.write(value & 0xFF);
                out.write((value >> 8) & 0xFF);
            }
        }
    }
}
