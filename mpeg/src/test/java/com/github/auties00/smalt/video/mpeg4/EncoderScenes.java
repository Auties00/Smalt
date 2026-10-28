package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Synthetic scenes for the MPEG-4 Part 2 and H.263 encoder tests, and the bookkeeping of encoding them: a textured
 * background panning diagonally, a sharp-edged square moving the other way, a gradient and smoothly drifting
 * chrominance, all computed from the frame index so that every run sees the same pictures.
 */
public final class EncoderScenes {
    private EncoderScenes() {
    }

    /**
     * The packets of an encode and the encoder's reconstruction of each picture, keyed by presentation timestamp.
     */
    public record Encoded(List<Packet> packets, Map<Long, byte[]> reconstructions, byte[] config) {
        public byte[] elementary() {
            var out = new ByteArrayOutputStream();
            for (var packet : packets) {
                out.writeBytes(packet.data());
            }
            return out.toByteArray();
        }
    }

    /**
     * A decoded picture: its timestamp and planar 4:2:0 samples.
     */
    public record Decoded(long pts, byte[] samples, boolean keyframe) {
    }

    public static YuvPicture picture(int width, int height, int index) {
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        var y = new byte[width * height];
        var u = new byte[chromaWidth * chromaHeight];
        var v = new byte[chromaWidth * chromaHeight];
        var squareX = (width * 3 / 4 - index * 3) % Math.max(1, width);
        var squareY = (height / 4 + index) % Math.max(1, height);
        var side = Math.max(2, Math.min(width, height) / 4);
        for (var row = 0; row < height; row++) {
            for (var col = 0; col < width; col++) {
                var x = col + 2 * index;
                var yy = row + index;
                var texture = 50 * StrictMath.sin(x / 6.0) * StrictMath.cos(yy / 4.5) + 18 * StrictMath.sin((x + yy) / 2.3);
                var value = 40 + (col * 120) / Math.max(1, width) + texture;
                var inSquare = Math.floorMod(col - squareX, width) < side && Math.floorMod(row - squareY, height) < side;
                if (inSquare) {
                    value = 220 - ((col + row) & 7) * 4;
                }
                y[row * width + col] = (byte) Math.max(16, Math.min(235, (int) Math.round(value)));
            }
        }
        for (var row = 0; row < chromaHeight; row++) {
            for (var col = 0; col < chromaWidth; col++) {
                var cu = 128 + 45 * StrictMath.sin((col + index) / 7.0) + 10 * StrictMath.cos(row / 3.0);
                var cv = 128 + 40 * StrictMath.cos((row - index) / 9.0) - 12 * StrictMath.sin(col / 4.0);
                u[row * chromaWidth + col] = (byte) Math.max(16, Math.min(240, (int) Math.round(cu)));
                v[row * chromaWidth + col] = (byte) Math.max(16, Math.min(240, (int) Math.round(cv)));
            }
        }
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420)
                .color(ColorInfo.BT601_LIMITED).luma(y, width).chroma(u, v, chromaWidth).build();
    }

    public static byte[] samples(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var row = 0; row < picture.height(); row++) {
            out.write(picture.y(), row * picture.yStride(), picture.width());
        }
        for (var row = 0; row < picture.chromaHeight(); row++) {
            out.write(picture.u(), row * picture.uvStride(), picture.chromaWidth());
        }
        for (var row = 0; row < picture.chromaHeight(); row++) {
            out.write(picture.v(), row * picture.uvStride(), picture.chromaWidth());
        }
        return out.toByteArray();
    }

    /**
     * Encodes a scene, one frame every {@code step} time base units, recording the reconstruction of each packet's
     * picture as the encoder reports it when the packet comes out.
     */
    public static <E extends VideoEncoder> Encoded encode(E encoder, int frames, long step,
                                                          Function<E, byte[]> reconstruction) {
        var packets = new ArrayList<Packet>();
        var reconstructions = new LinkedHashMap<Long, byte[]>();
        for (var i = 0; i < frames; i++) {
            var picture = picture(encoder.width(), encoder.height(), i);
            encoder.encode(new VideoFrame(picture, i * step, step, false), packet -> {
                packets.add(packet);
                reconstructions.put(packet.pts(), reconstruction.apply(encoder));
            });
        }
        encoder.flush(packet -> {
            packets.add(packet);
            reconstructions.put(packet.pts(), reconstruction.apply(encoder));
        });
        return new Encoded(packets, reconstructions, encoder.codecConfig().orElse(null));
    }

    public static List<Decoded> decode(VideoTrack track, List<Packet> packets, Function<VideoTrack, VideoDecoder> factory) {
        var decoded = new ArrayList<Decoded>();
        try (var decoder = factory.apply(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> decoded.add(new Decoded(frame.pts(),
                        samples((YuvPicture) frame.picture()), frame.keyframe())));
            }
            decoder.flush(frame -> decoded.add(new Decoded(frame.pts(), samples((YuvPicture) frame.picture()),
                    frame.keyframe())));
        }
        return decoded;
    }

    /**
     * Returns the frames of a planar 4:2:0 byte stream decoded by an oracle.
     */
    public static List<byte[]> frames(byte[] raw, int width, int height) {
        var size = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2);
        var result = new ArrayList<byte[]>();
        for (var at = 0; at + size <= raw.length; at += size) {
            var frame = new byte[size];
            System.arraycopy(raw, at, frame, 0, size);
            result.add(frame);
        }
        return result;
    }

    /**
     * Returns the source pictures of a scene as one planar stream in presentation order.
     */
    public static byte[] source(int width, int height, int frames) {
        var out = new ByteArrayOutputStream();
        for (var i = 0; i < frames; i++) {
            out.writeBytes(samples(picture(width, height, i)));
        }
        return out.toByteArray();
    }

    /**
     * Describes where two planar 4:2:0 streams differ by more than a threshold: per frame, the luminance macroblocks
     * whose largest difference exceeds it, for the failure messages of the oracle tests.
     */
    public static String differences(byte[] expected, byte[] actual, int width, int height, int threshold) {
        var size = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2);
        var out = new StringBuilder();
        for (var frame = 0; (frame + 1) * size <= Math.min(expected.length, actual.length); frame++) {
            var base = frame * size;
            var line = new StringBuilder();
            for (var my = 0; my * 16 < height; my++) {
                for (var mx = 0; mx * 16 < width; mx++) {
                    var worst = 0;
                    for (var y = my * 16; y < Math.min(height, my * 16 + 16); y++) {
                        for (var x = mx * 16; x < Math.min(width, mx * 16 + 16); x++) {
                            var at = base + y * width + x;
                            worst = Math.max(worst, Math.abs((expected[at] & 0xFF) - (actual[at] & 0xFF)));
                        }
                    }
                    if (worst > threshold) {
                        line.append(" (").append(mx).append(',').append(my).append(")=").append(worst);
                    }
                }
            }
            if (!line.isEmpty()) {
                out.append("\n  frame ").append(frame).append(':').append(line);
            }
        }
        return out.toString();
    }

    public static byte[] concat(List<byte[]> frames) {
        var out = new ByteArrayOutputStream();
        frames.forEach(out::writeBytes);
        return out.toByteArray();
    }
}
