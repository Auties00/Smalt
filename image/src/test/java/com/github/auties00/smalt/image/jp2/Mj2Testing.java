package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builds the synthetic frames, streams and files of the Motion JPEG 2000 tests.
 */
final class Mj2Testing {
    private Mj2Testing() {
    }

    record Stream(VideoTrack track, List<Packet> packets) {
    }

    // Gradients, a checkerboard and a moving sine, so that every frame differs and the wavelet has detail to code
    static YuvPicture yuv(int width, int height, ChromaFormat format, int frame) {
        var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.JPEG);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var check = ((x / 4 + y / 4) & 1) == 0 ? 40 : 0;
                picture.y()[y * picture.yStride() + x] = (byte) ((x * 5 + y * 3 + frame * 7 + check) & 0xFF);
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

    static Yuv16Picture yuv16(int width, int height, ChromaFormat format, int depth, int frame) {
        var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
        var max = (1 << depth) - 1;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (short) ((x * 37 + y * 11 + frame * 101) % (max + 1));
            }
        }
        if (format.hasChroma()) {
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    picture.u()[y * picture.uvStride() + x] = (short) ((x * 53 + y * 7 + frame) % (max + 1));
                    picture.v()[y * picture.uvStride() + x] = (short) ((x * 3 + y * 71 + 2 * frame) % (max + 1));
                }
            }
        }
        return picture;
    }

    static RgbaPicture rgba(int width, int height, boolean alpha, int frame) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + 4 * x;
                pixels[at] = (byte) (x * 255 / Math.max(1, width - 1));
                pixels[at + 1] = (byte) (((x / 8 + y / 8 + frame) & 1) == 0 ? 200 : 40 + y % 64);
                pixels[at + 2] = (byte) (128 + 100 * Math.sin((x + y + frame) * 0.15));
                pixels[at + 3] = (byte) (alpha ? (x * 7 + y * 13) & 0xFF : 255);
            }
        }
        return picture;
    }

    static Rgba16Picture rgba16(int width, int height, boolean alpha, int frame) {
        var pixels = new short[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = (y * width + x) * 4;
                pixels[at] = (short) (x * 1021 + frame * 17);
                pixels[at + 1] = (short) (y * 2039 + x * 3);
                pixels[at + 2] = (short) ((x ^ y) * 509);
                pixels[at + 3] = (short) (alpha ? x * 401 + y * 97 : 65535);
            }
        }
        return Rgba16Picture.of(width, height, width * 4, pixels, alpha);
    }

    static List<Packet> encode(Mj2Encoder encoder, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        for (var picture : pictures) {
            encoder.encode(new VideoFrame(picture, Packet.NO_TIMESTAMP, Packet.UNKNOWN_DURATION, true), packets::add);
        }
        encoder.flush(packets::add);
        return packets;
    }

    static List<Picture> decode(Mj2Decoder decoder, List<Packet> packets) {
        var pictures = new ArrayList<Picture>();
        for (var packet : packets) {
            decoder.decode(packet, frame -> pictures.add(frame.picture()));
        }
        decoder.flush(frame -> pictures.add(frame.picture()));
        return pictures;
    }

    static byte[] mux(Function<MediaSink, Muxer> factory, VideoTrack track, List<Packet> packets) {
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(track.id()));
            }
            muxer.finish();
        }
        return sink.toByteArray();
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

    // The visible samples of every plane, luma then Cb then Cr, one byte (or two little-endian bytes) per sample, or
    // the packed pixels of an RGBA picture: the raw frame layout of ffmpeg's planar and packed pixel formats
    static byte[] raw(Picture picture) {
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
                wide(out, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.u() != null) {
                    wide(out, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    wide(out, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
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

    private static void wide(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                out.write(plane[y * stride + x]);
                out.write(plane[y * stride + x] >> 8);
            }
        }
    }

    static byte[] concat(List<byte[]> parts) {
        var out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }
}
