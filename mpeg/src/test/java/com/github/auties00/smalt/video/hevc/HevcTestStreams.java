package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Helpers shared by the HEVC tests: Annex B splitting, packaging into length-prefixed access units
 * with an {@code hvcC} record, decoding a whole stream, and hashing decoded pictures the way the
 * reference decoder writes them (8-bit samples, or 16-bit little-endian samples above 8 bits).
 */
final class HevcTestStreams {
    private HevcTestStreams() {
    }

    static VideoTrack track() {
        return VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).build();
    }

    static VideoTrack track(byte[] config) {
        return VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000)).codecConfig(config).build();
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }

    /**
     * Splits an Annex B byte stream into NAL units, each still carrying its start code.
     */
    static List<byte[]> splitAnnexB(byte[] data) {
        var starts = startCodes(data);
        var result = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            result.add(Arrays.copyOfRange(data, starts.get(k), end));
        }
        return result;
    }

    /**
     * Returns the NAL units of an Annex B byte stream without start codes and trailing zero bytes.
     */
    static List<byte[]> nalUnits(byte[] data) {
        var starts = startCodes(data);
        var result = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var start = starts.get(k) + 3;
            var end = k + 1 < starts.size() ? starts.get(k + 1) : data.length;
            while (end > start && data[end - 1] == 0) {
                end--;
            }
            result.add(Arrays.copyOfRange(data, start, end));
        }
        return result;
    }

    private static List<Integer> startCodes(byte[] data) {
        var starts = new ArrayList<Integer>();
        var i = 0;
        while (i + 2 < data.length) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(i);
                i += 3;
            } else {
                i++;
            }
        }
        return starts;
    }

    static int type(byte[] nal) {
        return (nal[0] >> 1) & 0x3F;
    }

    /**
     * Groups NAL units into access units: a new one starts at a first slice segment of a picture
     * (or the parameter sets, prefix SEI and delimiters that precede it).
     */
    static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var seenSlice = false;
        for (var nal : nals) {
            var type = type(nal);
            var slice = type <= 21 && (type <= 9 || type >= 16);
            var firstSlice = slice && nal.length > 2 && (nal[2] & 0x80) != 0;
            var prefix = type >= 32 && type <= 39;
            if (seenSlice && (firstSlice || prefix)) {
                units.add(current);
                current = new ArrayList<>();
                seenSlice = false;
            }
            current.add(nal);
            seenSlice |= slice;
        }
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    /**
     * Builds a minimal {@code HEVCDecoderConfigurationRecord} carrying the given parameter sets.
     */
    static byte[] hvcC(List<byte[]> parameterSets) {
        var out = new ByteArrayOutputStream();
        out.write(1);
        out.writeBytes(new byte[20]);
        out.write(0xFC | 3);
        var byType = new ArrayList<List<byte[]>>();
        var types = new ArrayList<Integer>();
        for (var set : parameterSets) {
            var index = types.indexOf(type(set));
            if (index < 0) {
                types.add(type(set));
                byType.add(new ArrayList<>(List.of(set)));
            } else {
                byType.get(index).add(set);
            }
        }
        out.write(types.size());
        for (var i = 0; i < types.size(); i++) {
            out.write(0x80 | types.get(i));
            out.write(byType.get(i).size() >> 8);
            out.write(byType.get(i).size());
            for (var set : byType.get(i)) {
                out.write(set.length >> 8);
                out.write(set.length);
                out.writeBytes(set);
            }
        }
        return out.toByteArray();
    }

    /**
     * Packages NAL units as one length-prefixed sample with 4-byte prefixes.
     */
    static byte[] lengthPrefixed(List<byte[]> nals) {
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            out.write(nal.length >>> 24);
            out.write(nal.length >>> 16);
            out.write(nal.length >>> 8);
            out.write(nal.length);
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    /**
     * Decodes an Annex B stream fed one NAL unit per packet and returns the MD5 of every output
     * picture and the frame count.
     */
    static Result decode(byte[] stream, HevcDecoderOptions options) {
        return decode(stream, options, 0, 0);
    }

    /**
     * Decodes an Annex B stream like {@link #decode(byte[], HevcDecoderOptions)}, shifting the
     * deep samples of each component right before hashing them. A mixed-depth stream's shallower
     * component reaches the output scaled up to the picture depth, while reference MD5s hash every
     * component at its own depth; the shifted-out bits must be zero.
     */
    static Result decode(byte[] stream, HevcDecoderOptions options, int lumaShift, int chromaShift) {
        var digest = md5();
        var count = new int[1];
        try (var decoder = new HevcDecoder(track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                count[0]++;
                hash(digest, unshift(frame.picture(), lumaShift, chromaShift));
            };
            for (var nal : splitAnnexB(stream)) {
                decoder.decode(packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return new Result(HexFormat.of().formatHex(digest.digest()), count[0]);
    }

    record Result(String md5, int frames) {
    }

    static Picture unshift(Picture picture, int lumaShift, int chromaShift) {
        if (lumaShift == 0 && chromaShift == 0) {
            return picture;
        }
        var deep = (Yuv16Picture) picture;
        var copy = Yuv16Picture.allocate(deep.width(), deep.height(), deep.chromaFormat(), deep.bitDepth(), false,
                deep.color());
        unshift(deep.y(), deep.yStride(), copy.y(), copy.yStride(), deep.width(), deep.height(), lumaShift);
        if (deep.u() != null) {
            unshift(deep.u(), deep.uvStride(), copy.u(), copy.uvStride(), deep.chromaWidth(), deep.chromaHeight(),
                    chromaShift);
            unshift(deep.v(), deep.uvStride(), copy.v(), copy.uvStride(), deep.chromaWidth(), deep.chromaHeight(),
                    chromaShift);
        }
        return copy;
    }

    private static void unshift(short[] src, int srcStride, short[] dst, int dstStride, int width, int height,
                                int shift) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = src[y * srcStride + x] & 0xFFFF;
                if ((value & ((1 << shift) - 1)) != 0) {
                    throw new AssertionError("sample " + value + " is not shifted left by " + shift);
                }
                dst[y * dstStride + x] = (short) (value >> shift);
            }
        }
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Hashes a picture as planar samples, 8-bit pictures one byte per sample and deeper pictures two
     * little-endian bytes per sample.
     */
    static void hash(MessageDigest digest, Picture picture) {
        switch (picture) {
            case YuvPicture p -> {
                for (var y = 0; y < p.height(); y++) {
                    digest.update(p.y(), y * p.yStride(), p.width());
                }
                if (p.u() != null) {
                    for (var y = 0; y < p.chromaHeight(); y++) {
                        digest.update(p.u(), y * p.uvStride(), p.chromaWidth());
                    }
                    for (var y = 0; y < p.chromaHeight(); y++) {
                        digest.update(p.v(), y * p.uvStride(), p.chromaWidth());
                    }
                }
            }
            case Yuv16Picture p -> {
                hashShorts(digest, p.y(), p.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    hashShorts(digest, p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                    hashShorts(digest, p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        }
    }

    private static void hashShorts(MessageDigest digest, short[] plane, int stride, int width, int height) {
        var row = new byte[width * 2];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = plane[y * stride + x];
                row[2 * x] = (byte) value;
                row[2 * x + 1] = (byte) (value >> 8);
            }
            digest.update(row);
        }
    }

    /**
     * Returns the planes of a picture as one raw planar byte array (8-bit) for comparisons with
     * ffmpeg's rawvideo output.
     */
    static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case YuvPicture p -> {
                for (var y = 0; y < p.height(); y++) {
                    out.write(p.y(), y * p.yStride(), p.width());
                }
                if (p.u() != null) {
                    for (var y = 0; y < p.chromaHeight(); y++) {
                        out.write(p.u(), y * p.uvStride(), p.chromaWidth());
                    }
                    for (var y = 0; y < p.chromaHeight(); y++) {
                        out.write(p.v(), y * p.uvStride(), p.chromaWidth());
                    }
                }
            }
            case Yuv16Picture p -> {
                rawShorts(out, p.y(), p.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    rawShorts(out, p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                    rawShorts(out, p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        }
        return out.toByteArray();
    }

    private static void rawShorts(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = plane[y * stride + x];
                out.write(value);
                out.write(value >> 8);
            }
        }
    }
}
