package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared helpers of the HEVC encoder tests: synthetic moving sequences, encoding, decoding with Smalt's decoder
 * (which checks the decoded picture hashes the streams carry) and with ffmpeg, and sample comparisons.
 */
final class HevcEncoderHarness {
    private static final Path WORK = Scratch.directory("hevcenc-work");

    private HevcEncoderHarness() {
        throw new AssertionError();
    }

    /** The packets and configuration of an encoded sequence. */
    record Encoded(List<Packet> packets, byte[] config, HevcEncoderOptions options) {
        /** Returns the stream as an Annex B byte stream, the parameter sets of the configuration first. */
        byte[] annexB() {
            var out = new ByteArrayOutputStream();
            if (config != null) {
                var arrays = config[22] & 0xFF;
                var position = 23;
                for (var array = 0; array < arrays; array++) {
                    var count = ((config[position + 1] & 0xFF) << 8) | (config[position + 2] & 0xFF);
                    position += 3;
                    for (var i = 0; i < count; i++) {
                        var length = ((config[position] & 0xFF) << 8) | (config[position + 1] & 0xFF);
                        position += 2;
                        out.writeBytes(new byte[]{0, 0, 0, 1});
                        out.write(config, position, length);
                        position += length;
                    }
                }
            }
            for (var packet : packets) {
                var data = packet.data();
                if (config == null) {
                    out.writeBytes(data);
                    continue;
                }
                var offset = 0;
                while (offset < data.length) {
                    var length = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                            | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                    out.writeBytes(new byte[]{0, 0, 0, 1});
                    out.write(data, offset + 4, length);
                    offset += 4 + length;
                }
            }
            return out.toByteArray();
        }

        long bytes() {
            return packets.stream().mapToLong(Packet::size).sum();
        }
    }

    /**
     * Returns a sequence of 8-bit 4:2:0 pictures with smooth gradients, edges, texture and noise that move from
     * picture to picture.
     */
    static List<Picture> sequence(int width, int height, int frames, long seed) {
        var pictures = new ArrayList<Picture>();
        for (var f = 0; f < frames; f++) {
            pictures.add(picture(width, height, f, seed, 8));
        }
        return pictures;
    }

    /** Returns a sequence of 4:2:0 pictures of a bit depth, 8-bit pictures as {@link YuvPicture}. */
    static List<Picture> sequence(int width, int height, int frames, long seed, int depth) {
        var pictures = new ArrayList<Picture>();
        for (var f = 0; f < frames; f++) {
            pictures.add(picture(width, height, f, seed, depth));
        }
        return pictures;
    }

    /** Returns one picture of a synthetic sequence. */
    static Picture picture(int width, int height, int f, long seed, int depth) {
        var random = new Random(seed * 1_000_003L + f);
        var max = (1 << depth) - 1;
        var scale = 1 << (depth - 8);
        var luma = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = 110 + 50 * Math.sin((x + 3 * f) / 9.0) * Math.cos((y - f) / 13.0)
                        + 20 * Math.sin((x * 3 + y * 2) / 5.0) + random.nextInt(7) - 3;
                if (((x + f * 4 + (int) (seed & 7)) / 12 + (y + f) / 12) % 4 == 0) {
                    value += 50;
                }
                luma[y * width + x] = Math.clamp((int) Math.round(value * scale), 0, max);
            }
        }
        var cw = (width + 1) / 2;
        var ch = (height + 1) / 2;
        var cb = new int[cw * ch];
        var cr = new int[cw * ch];
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                cb[y * cw + x] = Math.clamp((int) Math.round((128 + 40 * Math.sin((x + f) / 6.0)) * scale), 0, max);
                cr[y * cw + x] = Math.clamp((int) Math.round((128 + 35 * Math.cos((y + 2 * f) / 7.0)
                        + random.nextInt(3) - 1) * scale), 0, max);
            }
        }
        if (depth == 8) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    picture.y()[y * picture.yStride() + x] = (byte) luma[y * width + x];
                }
            }
            for (var y = 0; y < ch; y++) {
                for (var x = 0; x < cw; x++) {
                    picture.u()[y * picture.uvStride() + x] = (byte) cb[y * cw + x];
                    picture.v()[y * picture.uvStride() + x] = (byte) cr[y * cw + x];
                }
            }
            return picture;
        }
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, depth, false, ColorInfo.BT709_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (short) luma[y * width + x];
            }
        }
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                picture.u()[y * picture.uvStride() + x] = (short) cb[y * cw + x];
                picture.v()[y * picture.uvStride() + x] = (short) cr[y * cw + x];
            }
        }
        return picture;
    }

    /** Returns a 4:2:0 picture of planar samples: the luma plane, then the two chroma planes one after the other. */
    static Picture planar(int width, int height, int depth, int[] luma, int[] chroma) {
        var cw = width / 2;
        var ch = height / 2;
        if (depth == 8) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    picture.y()[y * picture.yStride() + x] = (byte) luma[y * width + x];
                }
            }
            for (var y = 0; y < ch; y++) {
                for (var x = 0; x < cw; x++) {
                    picture.u()[y * picture.uvStride() + x] = (byte) chroma[y * cw + x];
                    picture.v()[y * picture.uvStride() + x] = (byte) chroma[cw * ch + y * cw + x];
                }
            }
            return picture;
        }
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV420, depth, false, ColorInfo.BT709_LIMITED);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                picture.y()[y * picture.yStride() + x] = (short) luma[y * width + x];
            }
        }
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                picture.u()[y * picture.uvStride() + x] = (short) chroma[y * cw + x];
                picture.v()[y * picture.uvStride() + x] = (short) chroma[cw * ch + y * cw + x];
            }
        }
        return picture;
    }

    /** Encodes pictures stamped at 30 frames per second in the 90 kHz time base. */
    static Encoded encode(int width, int height, HevcEncoderOptions options, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new HevcEncoder(width, height, options)) {
            var pts = 0L;
            for (var picture : pictures) {
                encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                pts += 3000;
            }
            encoder.flush(packets::add);
            return new Encoded(packets, encoder.codecConfig().orElse(null), options);
        }
    }

    /** Decodes a sequence with Smalt's decoder, verifying the decoded picture hashes it carries. */
    static List<VideoFrame> decode(Encoded encoded) {
        return decode(encoded, 1);
    }

    /**
     * Decodes a sequence with Smalt's decoder on a number of threads, verifying the decoded picture hashes it
     * carries; above one thread the decoder starts the wavefront substreams at the slice header's entry points.
     */
    static List<VideoFrame> decode(Encoded encoded, int threads) {
        var track = VideoTrack.builder().id(1).codec(Codec.HEVC).timeBase(new Rational(1, 90_000));
        if (encoded.config() != null) {
            track.codecConfig(encoded.config());
        }
        var frames = new ArrayList<VideoFrame>();
        var options = HevcDecoderOptions.builder().hashVerification(true).highBitDepth(true).threads(threads)
                .build();
        // the decoder lends its pictures only for the call, so each is copied
        Consumer<VideoFrame> sink = frame -> frames.add(new VideoFrame(copy(frame.picture()), frame.pts(),
                frame.duration(), frame.keyframe(), frame.sideData()));
        try (var decoder = new HevcDecoder(track.build(), options)) {
            for (var packet : encoded.packets()) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    /** Returns a copy of a 4:2:0 picture that the decoder no longer writes. */
    static Picture copy(Picture picture) {
        switch (picture) {
            case YuvPicture p -> {
                var copy = YuvPicture.allocate(p.width(), p.height(), p.chromaFormat(), false, p.color());
                for (var y = 0; y < p.height(); y++) {
                    System.arraycopy(p.y(), y * p.yStride(), copy.y(), y * copy.yStride(), p.width());
                }
                for (var y = 0; y < p.chromaHeight(); y++) {
                    System.arraycopy(p.u(), y * p.uvStride(), copy.u(), y * copy.uvStride(), p.chromaWidth());
                    System.arraycopy(p.v(), y * p.uvStride(), copy.v(), y * copy.uvStride(), p.chromaWidth());
                }
                return copy;
            }
            case Yuv16Picture p -> {
                var copy = Yuv16Picture.allocate(p.width(), p.height(), p.chromaFormat(), p.bitDepth(), false,
                        p.color());
                for (var y = 0; y < p.height(); y++) {
                    System.arraycopy(p.y(), y * p.yStride(), copy.y(), y * copy.yStride(), p.width());
                }
                for (var y = 0; y < p.chromaHeight(); y++) {
                    System.arraycopy(p.u(), y * p.uvStride(), copy.u(), y * copy.uvStride(), p.chromaWidth());
                    System.arraycopy(p.v(), y * p.uvStride(), copy.v(), y * copy.uvStride(), p.chromaWidth());
                }
                return copy;
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        }
    }

    /** Returns the raw planes of a picture as ffmpeg's rawvideo writes them (16-bit little-endian above 8 bits). */
    static byte[] raw(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case YuvPicture p -> {
                for (var y = 0; y < p.height(); y++) {
                    out.write(p.y(), y * p.yStride(), p.width());
                }
                for (var y = 0; y < p.chromaHeight(); y++) {
                    out.write(p.u(), y * p.uvStride(), p.chromaWidth());
                }
                for (var y = 0; y < p.chromaHeight(); y++) {
                    out.write(p.v(), y * p.uvStride(), p.chromaWidth());
                }
            }
            case Yuv16Picture p -> {
                shorts(out, p.y(), p.yStride(), p.width(), p.height());
                shorts(out, p.u(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
                shorts(out, p.v(), p.uvStride(), p.chromaWidth(), p.chromaHeight());
            }
            default -> throw new IllegalStateException("unexpected picture " + picture);
        }
        return out.toByteArray();
    }

    private static void shorts(ByteArrayOutputStream out, short[] plane, int stride, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = plane[y * stride + x];
                out.write(value);
                out.write(value >> 8);
            }
        }
    }

    /** Returns the raw output of every frame of a decode, in output order. */
    static byte[] raw(List<VideoFrame> frames) {
        var out = new ByteArrayOutputStream();
        for (var frame : frames) {
            out.writeBytes(raw(frame.picture()));
        }
        return out.toByteArray();
    }

    /** Returns the luma PSNR of a decoded picture against its source, at the source's depth. */
    static double psnr(Picture decoded, Picture source) {
        var width = source.width();
        var height = source.height();
        double sse = 0;
        int max;
        if (source instanceof YuvPicture s && decoded instanceof YuvPicture d) {
            max = 255;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var e = (s.y()[y * s.yStride() + x] & 0xFF) - (d.y()[y * d.yStride() + x] & 0xFF);
                    sse += e * e;
                }
            }
        } else if (source instanceof Yuv16Picture s && decoded instanceof Yuv16Picture d) {
            max = (1 << s.bitDepth()) - 1;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var e = (s.y()[y * s.yStride() + x] & 0xFFFF) - (d.y()[y * d.yStride() + x] & 0xFFFF);
                    sse += e * e;
                }
            }
        } else {
            throw new IllegalStateException("pictures of different kinds: " + source + " and " + decoded);
        }
        return 10 * Math.log10((double) max * max * width * height / Math.max(sse, 1e-9));
    }

    /** Writes a file of the work directory. */
    static Path write(String name, byte[] data) {
        try {
            Files.createDirectories(WORK);
            var file = WORK.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Decodes an Annex B stream with ffmpeg's HEVC decoder, which checks the MD5 decoded picture hashes, and fails
     * on any decoder message.
     */
    static byte[] ffmpeg(String name, byte[] annexB, String pixelFormat) {
        return ffmpeg(name, annexB, pixelFormat, List.of());
    }

    /**
     * Decodes an Annex B stream with ffmpeg's HEVC decoder in slice threading mode, which decodes the wavefront
     * substreams of a slice in parallel from the slice header's entry points, checks the MD5 decoded picture
     * hashes, and fails on any decoder message.
     */
    static byte[] ffmpegWavefront(String name, byte[] annexB, String pixelFormat) {
        return ffmpeg(name, annexB, pixelFormat, List.of("-threads", "4", "-thread_type", "slice"));
    }

    private static byte[] ffmpeg(String name, byte[] annexB, String pixelFormat, List<String> decoderOptions) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = write(name + ".hevc", annexB);
        var arguments = new ArrayList<>(List.of("-v", "error", "-err_detect", "crccheck+explode"));
        arguments.addAll(decoderOptions);
        arguments.addAll(List.of("-c:v", "hevc", "-f", "hevc", "-i", file.toString(), "-fps_mode", "passthrough",
                "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        var result = Oracle.execute(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), null, arguments);
        assertEquals(0, result.exitCode(), name + ": ffmpeg failed: " + result.stderr());
        assertEquals("", result.stderr().strip(), name + ": ffmpeg reported problems");
        return result.stdout();
    }
}
