package com.github.auties00.smalt.internal.sei;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Writes the SEI messages of ITU-T H.264 and H.265 that the decoders use for display, and inserts SEI NAL units into
 * Annex B streams before the first slice of each picture.
 */
public final class SeiWriter {
    /** A film grain intensity interval: its bounds and its model values. */
    public record Interval(int lower, int upper, int... values) {
    }

    private SeiWriter() {
    }

    /** A bit writer for SEI payloads. */
    public static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int filled;

        public Bits u(int count, long value) {
            for (var i = count - 1; i >= 0; i--) {
                current = (current << 1) | (int) ((value >>> i) & 1);
                if (++filled == 8) {
                    out.write(current);
                    current = 0;
                    filled = 0;
                }
            }
            return this;
        }

        public Bits flag(boolean value) {
            return u(1, value ? 1 : 0);
        }

        public Bits ue(long value) {
            var code = value + 1;
            var length = 64 - Long.numberOfLeadingZeros(code);
            u(length - 1, 0);
            return u(length, code);
        }

        public Bits se(long value) {
            return ue(value > 0 ? 2 * value - 1 : -2 * value);
        }

        /** Returns the payload, its last byte completed with zero bits. */
        public byte[] bytes() {
            var copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            if (filled != 0) {
                copy.write(current << (8 - filled));
            }
            return copy.toByteArray();
        }
    }

    /**
     * Writes a film grain characteristics payload with the frequency filtering model and additive blending, one
     * list of intervals per component ({@code null} or empty for a component without a model).
     */
    public static byte[] filmGrain(boolean hevc, int log2ScaleFactor, int persistence, List<List<Interval>> components) {
        var bits = new Bits().flag(false).u(2, 0).flag(false).u(2, 0).u(4, log2ScaleFactor);
        for (var c = 0; c < 3; c++) {
            bits.flag(components.get(c) != null && !components.get(c).isEmpty());
        }
        for (var c = 0; c < 3; c++) {
            var intervals = components.get(c);
            if (intervals == null || intervals.isEmpty()) {
                continue;
            }
            bits.u(8, intervals.size() - 1).u(3, intervals.getFirst().values().length - 1);
            for (var interval : intervals) {
                bits.u(8, interval.lower()).u(8, interval.upper());
                for (var value : interval.values()) {
                    bits.se(value);
                }
            }
        }
        if (hevc) {
            bits.flag(persistence != 0);
        } else {
            bits.ue(persistence);
        }
        return bits.bytes();
    }

    /** Writes a film grain characteristics payload that cancels the previous message. */
    public static byte[] filmGrainCancel() {
        return new Bits().flag(true).bytes();
    }

    /**
     * Writes a frame packing arrangement payload; the grid positions are written when the arrangement carries them.
     */
    public static byte[] framePacking(boolean hevc, int id, int type, boolean quincunx, int interpretation,
                                      boolean spatialFlipping, boolean frame0Flipped, boolean fieldViews,
                                      boolean currentFrameIsFrame0, boolean frame0SelfContained,
                                      boolean frame1SelfContained, int[] grid, int persistence, boolean upsampled) {
        var bits = new Bits().ue(id).flag(false).u(7, type).flag(quincunx).u(6, interpretation).flag(spatialFlipping)
                .flag(frame0Flipped).flag(fieldViews).flag(currentFrameIsFrame0).flag(frame0SelfContained)
                .flag(frame1SelfContained);
        if (!quincunx && type != 5) {
            for (var position : grid) {
                bits.u(4, position);
            }
        }
        bits.u(8, 0);
        if (hevc) {
            bits.flag(persistence != 0).flag(upsampled);
        } else {
            bits.ue(persistence).flag(false);
        }
        return bits.bytes();
    }

    /** Writes a frame packing arrangement payload that cancels the previous message. */
    public static byte[] framePackingCancel(boolean hevc) {
        var bits = new Bits().ue(0).flag(true);
        if (hevc) {
            bits.flag(false);
        } else {
            bits.flag(false);
        }
        return bits.bytes();
    }

    /** Writes a display orientation payload. */
    public static byte[] displayOrientation(boolean hevc, boolean horizontalFlip, boolean verticalFlip, int rotation,
                                            int persistence) {
        var bits = new Bits().flag(false).flag(horizontalFlip).flag(verticalFlip).u(16, rotation);
        if (hevc) {
            bits.flag(persistence != 0);
        } else {
            bits.ue(persistence).flag(false);
        }
        return bits.bytes();
    }

    /** Writes a display orientation payload that cancels the previous message. */
    public static byte[] displayOrientationCancel() {
        return new Bits().flag(true).bytes();
    }

    /** Builds an SEI NAL unit (without start code) holding messages, given as type, payload pairs. */
    public static byte[] nal(boolean hevc, List<Object[]> messages) {
        var body = new ByteArrayOutputStream();
        for (var message : messages) {
            var type = (int) message[0];
            var payload = (byte[]) message[1];
            for (var t = type; ; t -= 255) {
                body.write(Math.min(t, 255));
                if (t < 255) {
                    break;
                }
            }
            for (var s = payload.length; ; s -= 255) {
                body.write(Math.min(s, 255));
                if (s < 255) {
                    break;
                }
            }
            body.writeBytes(payload);
        }
        body.write(0x80);
        var out = new ByteArrayOutputStream();
        if (hevc) {
            out.write(0x4E);
            out.write(0x01);
        } else {
            out.write(0x06);
        }
        escape(body.toByteArray(), out);
        return out.toByteArray();
    }

    /** Builds an SEI NAL unit holding one message. */
    public static byte[] nal(boolean hevc, int type, byte[] payload) {
        return nal(hevc, List.<Object[]>of(new Object[]{type, payload}));
    }

    private static void escape(byte[] rbsp, ByteArrayOutputStream out) {
        var zeros = 0;
        for (var b : rbsp) {
            var value = b & 0xFF;
            if (zeros >= 2 && value <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
    }

    /** Parses a film grain characteristics payload. */
    public static FilmGrainParameters parseFilmGrain(byte[] payload, boolean hevc) {
        var reader = new com.github.auties00.smalt.video.h264.internal.H264BitReader();
        reader.init(java.util.Arrays.copyOf(payload, payload.length + 16), 0, payload.length);
        return FilmGrainParameters.parse(reader, hevc);
    }

    /**
     * Renders film grain onto a whole decoded picture (no cropping) with the renderer itself and returns the
     * samples as tightly packed planes, 16-bit samples little-endian, for checking how a decoder wires the
     * renderer (chroma format, bit depth, picture order offset).
     */
    public static byte[] grained(com.github.auties00.smalt.image.Picture picture, FilmGrainParameters params,
                                 int picOffset) {
        var synthesizer = new FilmGrainSynthesizer();
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case com.github.auties00.smalt.image.YuvPicture p -> {
                var format = format(p.chromaFormat());
                for (var c = 0; c < (p.u() == null ? 1 : 3); c++) {
                    var plane = c == 0 ? p.y() : c == 1 ? p.u() : p.v();
                    var stride = c == 0 ? p.yStride() : p.uvStride();
                    var w = c == 0 ? p.width() : p.chromaWidth();
                    var h = c == 0 ? p.height() : p.chromaHeight();
                    var dst = new byte[w * h];
                    synthesizer.apply(params, picOffset, c, format, plane, 0, stride, dst, 0, w, w, h, 1);
                    out.writeBytes(dst);
                }
            }
            case com.github.auties00.smalt.image.Yuv16Picture p -> {
                var format = format(p.chromaFormat());
                for (var c = 0; c < (p.u() == null ? 1 : 3); c++) {
                    var plane = c == 0 ? p.y() : c == 1 ? p.u() : p.v();
                    var stride = c == 0 ? p.yStride() : p.uvStride();
                    var w = c == 0 ? p.width() : p.chromaWidth();
                    var h = c == 0 ? p.height() : p.chromaHeight();
                    var dst = new short[w * h];
                    synthesizer.apply(params, picOffset, c, format, p.bitDepth(), plane, 0, stride, dst, 0, w, w, h, 1);
                    for (var value : dst) {
                        out.write(value);
                        out.write(value >> 8);
                    }
                }
            }
            default -> throw new IllegalArgumentException("unexpected picture " + picture);
        }
        return out.toByteArray();
    }

    /** Returns the tightly packed planes of a picture, 16-bit samples little-endian. */
    public static byte[] raw(com.github.auties00.smalt.image.Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case com.github.auties00.smalt.image.YuvPicture p -> {
                for (var c = 0; c < (p.u() == null ? 1 : 3); c++) {
                    var plane = c == 0 ? p.y() : c == 1 ? p.u() : p.v();
                    var stride = c == 0 ? p.yStride() : p.uvStride();
                    var w = c == 0 ? p.width() : p.chromaWidth();
                    for (var y = 0; y < (c == 0 ? p.height() : p.chromaHeight()); y++) {
                        out.write(plane, y * stride, w);
                    }
                }
            }
            case com.github.auties00.smalt.image.Yuv16Picture p -> {
                for (var c = 0; c < (p.u() == null ? 1 : 3); c++) {
                    var plane = c == 0 ? p.y() : c == 1 ? p.u() : p.v();
                    var stride = c == 0 ? p.yStride() : p.uvStride();
                    var w = c == 0 ? p.width() : p.chromaWidth();
                    for (var y = 0; y < (c == 0 ? p.height() : p.chromaHeight()); y++) {
                        for (var x = 0; x < w; x++) {
                            out.write(plane[y * stride + x]);
                            out.write(plane[y * stride + x] >> 8);
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("unexpected picture " + picture);
        }
        return out.toByteArray();
    }

    private static int format(com.github.auties00.smalt.image.ChromaFormat format) {
        return switch (format) {
            case MONOCHROME -> 0;
            case YUV420 -> 1;
            case YUV422 -> 2;
            default -> 3;
        };
    }

    /** Splits an Annex B stream into its NAL units, without start codes. */
    public static List<byte[]> nalUnits(byte[] stream) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 2 < stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                starts.add(i + 3);
                i += 2;
            }
        }
        var units = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var start = starts.get(k);
            var end = k + 1 < starts.size() ? starts.get(k + 1) - 3 : stream.length;
            while (end > start && stream[end - 1] == 0) {
                end--;
            }
            var unit = new byte[end - start];
            System.arraycopy(stream, start, unit, 0, unit.length);
            units.add(unit);
        }
        return units;
    }

    /** Returns whether a NAL unit is the first slice (segment) of a picture. */
    public static boolean firstSlice(boolean hevc, byte[] nal) {
        if (hevc) {
            var type = (nal[0] >> 1) & 0x3F;
            return type < 32 && (nal[2] & 0x80) != 0;
        }
        var type = nal[0] & 0x1F;
        return (type == 1 || type == 5) && (nal[1] & 0x80) != 0;
    }

    /** Returns whether a NAL unit is an H.264 IDR slice or an H.265 IRAP slice segment. */
    public static boolean randomAccess(boolean hevc, byte[] nal) {
        return hevc ? ((nal[0] >> 1) & 0x3F) >= 16 && ((nal[0] >> 1) & 0x3F) <= 23 : (nal[0] & 0x1F) == 5;
    }

    /**
     * Rewrites an Annex B stream, inserting before the first slice of picture {@code i} (in decoding order) the SEI
     * NAL units {@code inserts.apply(i)} returns; existing SEI NAL units are dropped when {@code dropSei} is set.
     */
    public static byte[] insert(byte[] stream, boolean hevc, boolean dropSei, IntFunction<List<byte[]>> inserts) {
        var out = new ByteArrayOutputStream();
        var picture = 0;
        for (var nal : nalUnits(stream)) {
            var sei = hevc ? ((nal[0] >> 1) & 0x3F) == 39 || ((nal[0] >> 1) & 0x3F) == 40 : (nal[0] & 0x1F) == 6;
            if (sei && dropSei) {
                continue;
            }
            if (firstSlice(hevc, nal)) {
                for (var unit : inserts.apply(picture)) {
                    out.writeBytes(new byte[]{0, 0, 0, 1});
                    out.writeBytes(unit);
                }
                picture++;
            }
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }
}
