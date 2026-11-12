package com.github.auties00.smalt.video.vvc;

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
 * Helpers shared by the VVC tests: Annex B splitting, packaging into length-prefixed access units with a
 * {@code vvcC} record, decoding a whole stream, and hashing decoded pictures the way the reference decoder writes
 * them (8-bit samples, or 16-bit little-endian samples above 8 bits, luma only for 4:0:0).
 */
final class VvcTestStreams {
    private VvcTestStreams() {
    }

    static VideoTrack track() {
        return VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).build();
    }

    static VideoTrack track(byte[] config) {
        return VideoTrack.builder().id(1).codec(Codec.VVC).timeBase(new Rational(1, 90_000)).codecConfig(config)
                .build();
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }

    static Packet packet(byte[] data, long pts) {
        return new Packet(1, data, pts, pts, 0, false);
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
        return (nal[1] >> 3) & 0x1F;
    }

    static boolean isParameterSet(byte[] nal) {
        var type = type(nal);
        return type >= 12 && type <= 17;
    }

    /**
     * Groups NAL units into access units: a new one starts at an access unit delimiter, a picture header or a
     * slice carrying its picture header, taking along the prefix NAL units that precede it after the last slice.
     */
    static List<List<byte[]>> accessUnits(List<byte[]> nals) {
        var units = new ArrayList<List<byte[]>>();
        var current = new ArrayList<byte[]>();
        var pending = new ArrayList<byte[]>();
        var seenSlice = false;
        for (var nal : nals) {
            var type = type(nal);
            var slice = type <= 11;
            var suffix = type == 18 || type == 21 || type == 22 || type == 24 || type == 25;
            if (seenSlice && (type == 20 || type == 19 || slice && pictureHeaderInSlice(nal))) {
                units.add(current);
                current = new ArrayList<>(pending);
                pending.clear();
                seenSlice = false;
            }
            if (seenSlice && !slice && !suffix) {
                pending.add(nal);
                continue;
            }
            current.addAll(pending);
            pending.clear();
            current.add(nal);
            seenSlice |= slice;
        }
        current.addAll(pending);
        if (!current.isEmpty()) {
            units.add(current);
        }
        return units;
    }

    private static boolean pictureHeaderInSlice(byte[] nal) {
        return nal.length > 2 && (nal[2] & 0x80) != 0;
    }

    /**
     * Builds a minimal {@code VvcDecoderConfigurationRecord} without PTL carrying the given parameter sets.
     */
    static byte[] vvcC(List<byte[]> parameterSets, boolean fullBoxHeader) {
        var out = new ByteArrayOutputStream();
        if (fullBoxHeader) {
            out.writeBytes(new byte[4]);
        }
        out.write(0xF8 | (3 << 1));
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
            // the decoding capability and operating point information arrays hold one unit and no count
            if (types.get(i) != 12 && types.get(i) != 13) {
                out.write(byType.get(i).size() >> 8);
                out.write(byType.get(i).size());
            }
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
     * Decodes an Annex B stream fed one NAL unit per packet and returns the MD5 of every output picture and the
     * frame count.
     */
    static Result decode(byte[] stream, VvcDecoderOptions options) {
        var digest = md5();
        var count = new int[1];
        try (var decoder = new VvcDecoder(track(), options)) {
            Consumer<VideoFrame> sink = frame -> {
                count[0]++;
                hash(digest, frame.picture());
            };
            for (var nal : splitAnnexB(stream)) {
                decoder.decode(packet(nal), sink);
            }
            decoder.flush(sink);
        }
        return new Result(HexFormat.of().formatHex(digest.digest()), count[0]);
    }

    /**
     * Decodes an Annex B stream as length-prefixed access units after a {@code vvcC} record of its first
     * parameter sets, and returns the MD5 of every output picture and the frame count.
     */
    static Result decodeAccessUnits(byte[] stream, VvcDecoderOptions options) {
        var nals = nalUnits(stream);
        var sets = new ArrayList<byte[]>();
        for (var nal : nals) {
            if (type(nal) <= 11) {
                break;
            }
            if (isParameterSet(nal)) {
                sets.add(nal);
            }
        }
        var digest = md5();
        var count = new int[1];
        try (var decoder = new VvcDecoder(track(vvcC(sets, true)), options)) {
            Consumer<VideoFrame> sink = frame -> {
                count[0]++;
                hash(digest, frame.picture());
            };
            for (var unit : accessUnits(nals)) {
                decoder.decode(packet(lengthPrefixed(unit)), sink);
            }
            decoder.flush(sink);
        }
        return new Result(HexFormat.of().formatHex(digest.digest()), count[0]);
    }

    record Result(String md5, int frames) {
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

    /**
     * Returns the samples of a picture in the layout {@link #hash} hashes, as ffmpeg's rawvideo output holds them.
     */
    static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        var digest = new MessageDigest("raw") {
            @Override
            protected void engineUpdate(byte input) {
                out.write(input);
            }

            @Override
            protected void engineUpdate(byte[] input, int offset, int length) {
                out.write(input, offset, length);
            }

            @Override
            protected byte[] engineDigest() {
                return new byte[0];
            }

            @Override
            protected void engineReset() {
                out.reset();
            }
        };
        hash(digest, picture);
        return out.toByteArray();
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
}
