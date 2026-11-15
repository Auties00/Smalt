package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The recovery point and field vectors of {@code random/vectors.tsv}, written by
 * {@code tools/testdata/hevc-random-access/make-vectors.py} with the HM reference encoder and x265, and helpers that
 * cut, edit and decode them access unit by access unit.
 */
final class HevcRandomVectors {
    private HevcRandomVectors() {
    }

    record Vector(String name, int width, int height, int frames, int bitDepth, String streamSha256,
                  String decodedSha256, int[] decodingPocs, List<String> frameMd5s) {
        @Override
        public String toString() {
            return name;
        }

        byte[] stream() {
            try (var input = HevcRandomVectors.class.getResourceAsStream("random/" + name + ".hevc")) {
                if (input == null) {
                    throw new IllegalStateException("missing vector " + name);
                }
                var data = input.readAllBytes();
                assertEquals(streamSha256, hex(digest("SHA-256").digest(data)), name + " stream");
                return data;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        List<List<byte[]>> accessUnits() {
            return HevcTestStreams.accessUnits(HevcTestStreams.nalUnits(stream()));
        }
    }

    static List<Vector> all() {
        try (var input = HevcRandomVectors.class.getResourceAsStream("random/vectors.tsv")) {
            var vectors = new ArrayList<Vector>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                var pocs = Arrays.stream(columns[7].split(",")).mapToInt(Integer::parseInt).toArray();
                vectors.add(new Vector(columns[0], Integer.parseInt(columns[1]), Integer.parseInt(columns[2]),
                        Integer.parseInt(columns[3]), Integer.parseInt(columns[4]), columns[5], columns[6], pocs,
                        List.of(columns[8].split(","))));
            }
            return vectors;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Vector named(String name) {
        return all().stream().filter(vector -> vector.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * One output frame: the MD5 of its samples hashed as the reference decoder writes them, a copy of its picture,
     * and its timing.
     */
    record Frame(String md5, Picture picture, long pts, long duration, boolean keyframe) {
    }

    /**
     * Decodes access units fed one per Annex B packet, with packet timestamps from {@code timing} (or none).
     */
    static List<Frame> decode(VideoTrack track, HevcDecoderOptions options, List<List<byte[]>> units,
                              long[][] timing) {
        var frames = new ArrayList<Frame>();
        Consumer<VideoFrame> sink = frame -> {
            var digest = digest("MD5");
            HevcTestStreams.hash(digest, frame.picture());
            frames.add(new Frame(hex(digest.digest()), copy(frame.picture()), frame.pts(), frame.duration(),
                    frame.keyframe()));
        };
        try (var decoder = new HevcDecoder(track, options)) {
            for (var index = 0; index < units.size(); index++) {
                var pts = timing == null ? Packet.NO_TIMESTAMP : timing[index][0];
                var duration = timing == null ? 0 : timing[index][1];
                decoder.decode(new Packet(1, annexB(units.get(index)), pts, Packet.NO_TIMESTAMP, duration, false),
                        sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    static List<Frame> decode(HevcDecoderOptions options, List<List<byte[]>> units) {
        return decode(HevcTestStreams.track(), options, units, null);
    }

    static byte[] annexB(List<byte[]> nals) {
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    static List<String> md5s(List<Frame> frames) {
        return frames.stream().map(Frame::md5).toList();
    }

    /**
     * Returns whether a NAL unit is a prefix SEI NAL unit whose first message has the given payload type.
     */
    static boolean isSei(byte[] nal, int payloadType) {
        return HevcTestStreams.type(nal) == 39 && nal.length > 2 && (nal[2] & 0xFF) == payloadType;
    }

    /**
     * Builds a prefix SEI NAL unit holding one recovery point message.
     */
    static byte[] recoveryPoint(int recoveryPocCount, boolean exactMatch) {
        var bits = new StringBuilder();
        var code = recoveryPocCount > 0 ? 2L * recoveryPocCount - 1 : -2L * recoveryPocCount;
        var value = Long.toBinaryString(code + 1);
        bits.append("0".repeat(value.length() - 1)).append(value);
        bits.append(exactMatch ? '1' : '0').append('0');
        bits.append('1');
        while (bits.length() % 8 != 0) {
            bits.append('0');
        }
        var payload = new byte[bits.length() / 8];
        for (var index = 0; index < payload.length; index++) {
            payload[index] = (byte) Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
        }
        return sei(6, payload);
    }

    /**
     * Builds a prefix SEI NAL unit holding one message, with emulation prevention and the RBSP trailing bits.
     */
    static byte[] sei(int payloadType, byte[] payload) {
        var rbsp = new ByteArrayOutputStream();
        rbsp.write(payloadType);
        rbsp.write(payload.length);
        rbsp.writeBytes(payload);
        rbsp.write(0x80);
        return nal(39, rbsp.toByteArray());
    }

    /**
     * Returns a copy of a prefix SEI NAL unit whose first message is a picture timing message, with its
     * {@code pic_struct} (the high four bits of the first payload byte) replaced.
     */
    static byte[] withPicStruct(byte[] nal, int picStruct) {
        var rbsp = unescape(nal);
        rbsp[2] = (byte) ((picStruct << 4) | (rbsp[2] & 0x0F));
        return nal(39, rbsp);
    }

    /**
     * Returns the RBSP of a NAL unit: its payload after the two-byte header without emulation prevention bytes.
     */
    static byte[] unescape(byte[] nal) {
        var out = new ByteArrayOutputStream();
        var zeros = 0;
        for (var index = 2; index < nal.length; index++) {
            var value = nal[index] & 0xFF;
            if (zeros >= 2 && value == 3) {
                zeros = 0;
                continue;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Builds a NAL unit of the base layer and temporal sub-layer 0 from its type and RBSP.
     */
    static byte[] nal(int type, byte[] rbsp) {
        var out = new ByteArrayOutputStream();
        out.write(type << 1);
        out.write(1);
        var zeros = 0;
        for (var value : rbsp) {
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Returns the access units with the {@code pic_struct} of each one's picture timing message replaced by the
     * value at its index, or with the message removed where the value is negative.
     */
    static List<List<byte[]>> withPicStructs(List<List<byte[]>> units, int[] picStructs) {
        var result = new ArrayList<List<byte[]>>();
        for (var index = 0; index < units.size(); index++) {
            var unit = new ArrayList<byte[]>();
            var replaced = false;
            for (var nal : units.get(index)) {
                if (isSei(nal, 1)) {
                    replaced = true;
                    if (picStructs[index] >= 0) {
                        unit.add(withPicStruct(nal, picStructs[index]));
                    }
                } else {
                    unit.add(nal);
                }
            }
            if (!replaced) {
                throw new IllegalStateException("access unit " + index + " has no picture timing message");
            }
            result.add(unit);
        }
        return result;
    }

    /**
     * Returns a copy of a picture, since decoders recycle the pictures they hand out.
     */
    static Picture copy(Picture picture) {
        return switch (picture) {
            case YuvPicture p -> {
                var copy = YuvPicture.allocate(p.width(), p.height(), p.chromaFormat(), false, p.color());
                copyPlane(p.y(), p.yStride(), copy.y(), copy.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    copyPlane(p.u(), p.uvStride(), copy.u(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                    copyPlane(p.v(), p.uvStride(), copy.v(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
                yield copy;
            }
            case Yuv16Picture p -> {
                var copy = Yuv16Picture.allocate(p.width(), p.height(), p.chromaFormat(), p.bitDepth(), false,
                        p.color());
                copyPlane(p.y(), p.yStride(), copy.y(), copy.yStride(), p.width(), p.height());
                if (p.u() != null) {
                    copyPlane(p.u(), p.uvStride(), copy.u(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                    copyPlane(p.v(), p.uvStride(), copy.v(), copy.uvStride(), p.chromaWidth(), p.chromaHeight());
                }
                yield copy;
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        };
    }

    private static void copyPlane(byte[] src, int srcStride, byte[] dst, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, y * srcStride, dst, y * dstStride, width);
        }
    }

    private static void copyPlane(short[] src, int srcStride, short[] dst, int dstStride, int width, int height) {
        for (var y = 0; y < height; y++) {
            System.arraycopy(src, y * srcStride, dst, y * dstStride, width);
        }
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
