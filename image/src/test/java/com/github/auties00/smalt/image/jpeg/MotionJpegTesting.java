package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and inspects Motion-JPEG frames for the tests: synthetic pictures, the marker rewrites of the OpenDML and
 * QuickTime conventions written from their specifications, and plane comparisons.
 */
final class MotionJpegTesting {
    private MotionJpegTesting() {
    }

    record Stream(VideoTrack track, List<Packet> packets) {
    }

    // A moving gradient with edges and texture, so that the frames differ and JPEG has detail to code
    static RgbaPicture pattern(int width, int height, int frame) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + 4 * x;
                var u = x + 3 * frame;
                var v = y + 2 * frame;
                pixels[at] = (byte) (u * 255 / Math.max(1, width + 90));
                pixels[at + 1] = (byte) (((u / 8 + v / 8) & 1) == 0 ? 200 : 40 + v % 64);
                pixels[at + 2] = (byte) (128 + 100 * Math.sin((u + v) * 0.15));
                pixels[at + 3] = (byte) 255;
            }
        }
        return picture;
    }

    static YuvPicture yuvPattern(int width, int height, ChromaFormat format, int frame) {
        var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.JPEG);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) ((x * 5 + y * 3 + frame * 7) & 0xFF);
            }
        }
        if (format.hasChroma()) {
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    picture.u()[y * picture.uvStride() + x] = (byte) (128 + 60 * Math.sin((x + frame) * 0.3));
                    picture.v()[y * picture.uvStride() + x] = (byte) (128 + 60 * Math.cos((y - frame) * 0.2));
                }
            }
        }
        return picture;
    }

    static Stream demux(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst()
                    .orElseThrow();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            return new Stream(track, packets);
        }
    }

    // The bytes of the segment at index at (its marker code included) through its end
    static int segmentEnd(byte[] jpeg, int at) {
        return at + 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
    }

    static int find(byte[] jpeg, int marker) {
        var at = 2;
        while (at + 3 < jpeg.length) {
            var code = jpeg[at + 1] & 0xFF;
            if (code == marker) {
                return at;
            }
            if (code == 0xDA) {
                return -1;
            }
            at = segmentEnd(jpeg, at);
        }
        return -1;
    }

    static byte[] stripDht(byte[] jpeg) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        var at = 2;
        while (true) {
            var code = jpeg[at + 1] & 0xFF;
            if (code == 0xDA) {
                out.write(jpeg, at, jpeg.length - at);
                return out.toByteArray();
            }
            var end = segmentEnd(jpeg, at);
            if (code != 0xC4) {
                out.write(jpeg, at, end - at);
            }
            at = end;
        }
    }

    // Replaces the 14-byte JFIF payload of the APP0 segment after SOI by an OpenDML AVI1 payload of the same size
    static byte[] withAvi1(byte[] jpeg, int polarity, int fieldSize) {
        var out = jpeg.clone();
        if (out[2] != (byte) 0xFF || out[3] != (byte) 0xE0 || out[6] != 'J') {
            throw new IllegalArgumentException("no JFIF segment");
        }
        var payload = ByteBuffer.wrap(out, 6, 14);
        payload.put(new byte[]{'A', 'V', 'I', '1', (byte) polarity, 0});
        payload.putInt(fieldSize);
        payload.putInt(fieldSize);
        return out;
    }

    // Inserts after SOI the QuickTime Motion-JPEG format A APP1 segment, whose offsets are relative to the field start
    static byte[] withMjpgSegment(byte[] jpeg, int nextField, int padding) {
        var length = 2 + 40;
        var size = jpeg.length + 2 + length;
        var shift = 2 + length;
        var out = ByteBuffer.allocate(size + padding);
        out.put(jpeg, 0, 2);
        out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length);
        out.putInt(0).put(new byte[]{'m', 'j', 'p', 'g'});
        out.putInt(size).putInt(size + padding).putInt(nextField);
        out.putInt(find(jpeg, 0xDB) + shift);
        var dht = find(jpeg, 0xC4);
        out.putInt(dht < 0 ? 0 : dht + shift);
        out.putInt(find(jpeg, 0xC0) + shift);
        var sos = find(jpeg, 0xDA);
        out.putInt(sos + shift);
        out.putInt(segmentEnd(jpeg, sos) + shift);
        out.put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }

    // Converts interchange fields to one QuickTime Motion-JPEG format B frame: a header of ten 32-bit words padded to
    // 48 bytes, the DQT, DHT, SOF and SOS segments without their marker codes, and the entropy-coded data unstuffed
    static byte[] toFormatB(boolean huffmanOffset, byte[]... fields) {
        var parts = new ArrayList<byte[]>();
        for (var field : fields) {
            var body = new ByteArrayOutputStream();
            var offsets = new int[4];
            var markers = new int[]{0xDB, 0xC4, 0xC0, 0xDA};
            for (var slot = 0; slot < 4; slot++) {
                if (slot == 1 && !huffmanOffset) {
                    continue;
                }
                // every segment of the kind merged into one: format B has one offset for all quantization tables and
                // one for all Huffman tables
                var payload = new ByteArrayOutputStream();
                for (var at = 2; at + 3 < field.length; at = segmentEnd(field, at)) {
                    if ((field[at + 1] & 0xFF) == markers[slot]) {
                        payload.write(field, at + 4, segmentEnd(field, at) - at - 4);
                    }
                    if ((field[at + 1] & 0xFF) == 0xDA) {
                        break;
                    }
                }
                if (payload.size() == 0) {
                    continue;
                }
                offsets[slot] = 48 + body.size();
                body.write((payload.size() + 2) >> 8);
                body.write(payload.size() + 2);
                body.writeBytes(payload.toByteArray());
            }
            var sod = 48 + body.size();
            var sos = find(field, 0xDA);
            var end = field.length - 2;
            for (var i = segmentEnd(field, sos); i < end; i++) {
                body.write(field[i]);
                if (field[i] == (byte) 0xFF && field[i + 1] == 0) {
                    i++;
                }
            }
            var header = ByteBuffer.allocate(48);
            var size = 48 + body.size();
            header.putInt(0).put(new byte[]{'m', 'j', 'p', 'g'}).putInt(size).putInt(size).putInt(0);
            for (var offset : offsets) {
                header.putInt(offset);
            }
            header.putInt(sod);
            var part = new byte[size];
            System.arraycopy(header.array(), 0, part, 0, 48);
            System.arraycopy(body.toByteArray(), 0, part, 48, body.size());
            parts.add(part);
        }
        var total = parts.stream().mapToInt(part -> part.length).sum();
        var out = new byte[total];
        var at = 0;
        for (var i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (i + 1 < parts.size()) {
                ByteBuffer.wrap(part).putInt(16, part.length);
            }
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    // The field of a picture: every second row from row 0 (top) or 1 (bottom), chroma rows alternating the same way
    static YuvPicture field(YuvPicture frame, boolean bottom) {
        var rows = bottom ? frame.height() / 2 : (frame.height() + 1) / 2;
        var format = frame.chromaFormat();
        var field = YuvPicture.allocate(frame.width(), rows, format, false, frame.color());
        for (var y = 0; y < rows; y++) {
            System.arraycopy(frame.y(), (2 * y + (bottom ? 1 : 0)) * frame.yStride(), field.y(), y * field.yStride(),
                    frame.width());
        }
        if (format.hasChroma()) {
            for (var y = 0; y < field.chromaHeight(); y++) {
                var source = Math.min(2 * y + (bottom ? 1 : 0), lastRow(frame.chromaHeight(), bottom));
                System.arraycopy(frame.u(), source * frame.uvStride(), field.u(), y * field.uvStride(),
                        frame.chromaWidth());
                System.arraycopy(frame.v(), source * frame.uvStride(), field.v(), y * field.uvStride(),
                        frame.chromaWidth());
            }
        }
        return field;
    }

    private static int lastRow(int rows, boolean bottom) {
        if (!bottom) {
            return rows - 1;
        }
        return rows >= 2 ? (rows / 2) * 2 - 1 : 0;
    }

    // Interleaves the rows of two fields, a field that runs out repeating its last row
    static YuvPicture weave(YuvPicture top, YuvPicture bottom, int height) {
        var format = top.chromaFormat();
        var frame = YuvPicture.allocate(top.width(), height, format, false, top.color());
        for (var y = 0; y < height; y++) {
            var source = (y & 1) == 0 ? top : bottom;
            System.arraycopy(source.y(), Math.min(y >> 1, source.height() - 1) * source.yStride(), frame.y(),
                    y * frame.yStride(), frame.width());
        }
        if (format.hasChroma()) {
            for (var y = 0; y < frame.chromaHeight(); y++) {
                var source = (y & 1) == 0 ? top : bottom;
                var row = Math.min(y >> 1, source.chromaHeight() - 1) * source.uvStride();
                System.arraycopy(source.u(), row, frame.u(), y * frame.uvStride(), frame.chromaWidth());
                System.arraycopy(source.v(), row, frame.v(), y * frame.uvStride(), frame.chromaWidth());
            }
        }
        return frame;
    }

    // The visible samples of every plane, luma then Cb then Cr, one byte (or two little-endian bytes) per sample
    static byte[] planes(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case YuvPicture yuv -> {
                for (var y = 0; y < yuv.height(); y++) {
                    out.write(yuv.y(), y * yuv.yStride(), yuv.width());
                }
                if (yuv.u() != null) {
                    for (var y = 0; y < yuv.chromaHeight(); y++) {
                        out.write(yuv.u(), y * yuv.uvStride(), yuv.chromaWidth());
                    }
                    for (var y = 0; y < yuv.chromaHeight(); y++) {
                        out.write(yuv.v(), y * yuv.uvStride(), yuv.chromaWidth());
                    }
                }
            }
            case Yuv16Picture yuv -> {
                writeWide(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.u() != null) {
                    writeWide(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    writeWide(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
            }
            case RgbaPicture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    out.write(rgba.pixels(), y * rgba.stride(), 4 * rgba.width());
                }
            }
            default -> throw new IllegalArgumentException(picture.getClass().getName());
        }
        return out.toByteArray();
    }

    private static void writeWide(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                out.write(plane[y * stride + x]);
                out.write(plane[y * stride + x] >> 8);
            }
        }
    }

    // Compares ffmpeg's raw frames of an 8-bit planar format with Smalt's; ffmpeg's IDCT is not libjpeg's, so samples
    // may differ by a few levels
    static void assertCloseToFfmpeg(byte[] ffmpeg, List<? extends Picture> frames, int maxDifference, double maxMean) {
        var at = 0;
        var total = 0L;
        var count = 0L;
        var largest = 0;
        for (var frame : frames) {
            var ours = planes(frame);
            for (var b : ours) {
                var difference = Math.abs((b & 0xFF) - (ffmpeg[at++] & 0xFF));
                largest = Math.max(largest, difference);
                total += difference;
                count++;
            }
        }
        if (at != ffmpeg.length) {
            throw new AssertionError("ffmpeg decoded " + ffmpeg.length + " bytes, Smalt " + at);
        }
        if (largest > maxDifference || total > maxMean * count) {
            throw new AssertionError("frames differ from ffmpeg's by up to " + largest + ", " + (double) total / count
                    + " on average");
        }
    }
}
