package com.github.auties00.smalt.image.hap;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HexFormat;
import java.util.Random;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HAP streams made by ffmpeg's encoder in every format, compressor and chunk count it writes and compares
 * them with ffmpeg's decode, encodes every HAP format with Smalt and checks the textures against the source and,
 * for the formats ffmpeg decodes, against ffmpeg's decode of the written files, and feeds damaged frames.
 */
@DisplayName("HAP against ffmpeg")
class HapDifferentialTest {
    // ffmpeg's S3TC decoder widens 5 and 6-bit endpoints by repeating bits and Smalt's rounds them, so the two
    // decodes of one texture differ by a few levels, more after the scaled YCoCg transform
    static final Tolerance DXT = Tolerance.maxAbs(4);

    static final Tolerance YCOCG = Tolerance.maxAbs(12);

    // ffmpeg's HAP encoder takes multiples of 4; Smalt's other sizes are checked by oddSize
    static final int WIDTH = 132;

    static final int HEIGHT = 76;

    record Stream(VideoTrack track, List<Packet> packets) {
    }

    static Stream demux(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst()
                    .orElseThrow();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            return new Stream(track, packets);
        }
    }

    static byte[] ffmpegDecode(Path file, String pixelFormat) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:v:0", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-fps_mode", "passthrough", "-"));
    }

    static byte[] rgba(RgbaPicture picture, boolean alpha) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out.write(picture.pixels(), y * picture.stride() + x * 4, alpha ? 4 : 3);
            }
        }
        return out.toByteArray();
    }

    /**
     * Returns a vector of tools/testdata/hap/make-vectors.sh, copied to a scratch file after checking its pinned
     * SHA-256.
     */
    static Path sample(String format, String compressor, int chunks, String container) {
        var name = format + "-" + compressor + "-" + chunks + "." + container;
        try (var in = HapDifferentialTest.class.getResourceAsStream("ffmpeg/" + name);
             var pins = HapDifferentialTest.class.getResourceAsStream("ffmpeg.sha256")) {
            assertNotNull(in, name);
            var data = in.readAllBytes();
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            assertTrue(new String(pins.readAllBytes(), StandardCharsets.US_ASCII).contains(digest + " *" + name),
                    name + " does not match its pinned SHA-256");
            var file = Scratch.directory("hap-vectors").resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest(name = "{0} {1} {2} chunks in {3}")
    @CsvSource({"hap,snappy,1,mov", "hap,none,1,mov", "hap,snappy,4,mov", "hap,none,3,avi", "hap_alpha,snappy,1,mov",
            "hap_alpha,snappy,8,mkv", "hap_q,snappy,1,mov", "hap_q,none,2,mov", "hap_q,snappy,5,avi"})
    @DisplayName("decodes ffmpeg's streams as ffmpeg does")
    void decodesFfmpeg(String format, String compressor, int chunks, String container) {
        var file = sample(format, compressor, chunks, container);
        var stream = demux(file);
        assertEquals(Codec.HAP, stream.track().codec());
        var decoder = VideoDecoders.create(stream.track(), VideoDecoderOptions.defaults());
        assertInstanceOf(HapDecoder.class, decoder);
        var alpha = format.equals("hap_alpha");
        var ours = new ByteArrayOutputStream();
        for (var packet : stream.packets()) {
            decoder.decode(packet, frame -> ours.writeBytes(rgba((RgbaPicture) frame.picture(), alpha)));
        }
        var expected = ffmpegDecode(file, alpha ? "rgba" : "rgb24");
        (format.equals("hap_q") ? YCOCG : DXT).check(format + " " + compressor + " " + chunks,
                Tolerance.Difference.of(expected, ours.toByteArray()));
    }

    static RgbaPicture source(long seed, boolean alpha) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(WIDTH, HEIGHT, alpha);
        var pixels = picture.pixels();
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var at = y * picture.stride() + x * 4;
                pixels[at] = (byte) (x * 255 / WIDTH);
                pixels[at + 1] = (byte) (y * 255 / HEIGHT + random.nextInt(3));
                pixels[at + 2] = (byte) (128 + (x - y) / 2);
                pixels[at + 3] = (byte) (alpha ? (x + y + seed) * 2 : 255);
            }
        }
        return picture;
    }

    static List<Packet> encode(HapEncoder encoder, List<? extends com.github.auties00.smalt.image.Picture> pictures) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            packets.add(encoder.encodeFrame(new VideoFrame(pictures.get(i), i * 3000L, 3000L, true)).withTrackId(1));
        }
        return packets;
    }

    static VideoTrack track(HapEncoder encoder) {
        return VideoTrack.builder().id(1).codec(Codec.HAP).timeBase(encoder.timeBase()).width(WIDTH).height(HEIGHT)
                .frameRate(new Rational(30, 1)).codecConfig(encoder.codecConfig().orElseThrow()).build();
    }

    /** Returns the PSNR of two 8-bit buffers. */
    static double psnr(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        var sum = 0.0;
        for (var i = 0; i < expected.length; i++) {
            var d = (expected[i] & 0xFF) - (actual[i] & 0xFF);
            sum += d * d;
        }
        return sum == 0 ? 99 : 10 * StrictMath.log10(255.0 * 255 * expected.length / sum);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = HapFormat.class, names = {"HAP", "HAP_ALPHA", "HAP_Q", "HAP_Q_ALPHA", "HAP_R"})
    @DisplayName("encodes RGB formats close to the source, decoded alike by Smalt and ffmpeg")
    void encodes(HapFormat format) throws IOException {
        var alpha = format.hasAlpha();
        var pictures = List.of(source(1, alpha), source(2, alpha));
        for (var chunks : new int[]{1, 7}) {
            for (var snappy : new boolean[]{true, false}) {
                var encoder = new HapEncoder(WIDTH, HEIGHT, HapEncoderOptions.builder().format(format).chunks(chunks)
                        .snappy(snappy).build());
                var packets = encode(encoder, pictures);
                var label = format + " chunks " + chunks + " snappy " + snappy;
                var decoder = new HapDecoder(track(encoder), VideoDecoderOptions.defaults());
                var ours = new ByteArrayOutputStream();
                var expected = new ByteArrayOutputStream();
                for (var i = 0; i < packets.size(); i++) {
                    var picture = (RgbaPicture) decoder.decodeFrame(packets.get(i).data(), 0,
                            packets.get(i).data().length, WIDTH, HEIGHT);
                    assertEquals(alpha, picture.hasAlpha(), label);
                    ours.writeBytes(rgba(picture, alpha));
                    expected.writeBytes(rgba(pictures.get(i), alpha));
                }
                var quality = psnr(expected.toByteArray(), ours.toByteArray());
                var floor = format == HapFormat.HAP_R ? 40 : format == HapFormat.HAP ? 33 : 34;
                assertTrue(quality > floor, label + ": PSNR " + quality);
                // ffmpeg decodes neither HAP R nor HAP Q Alpha
                if (format == HapFormat.HAP_R || format == HapFormat.HAP_Q_ALPHA) {
                    continue;
                }
                for (var container : new String[]{"mov", "avi", "mkv"}) {
                    var sink = MediaSink.memory();
                    switch (container) {
                        case "mov" -> {
                            var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder()
                                    .brand(Mp4MuxerOptions.Brand.QUICKTIME).build());
                            muxer.addTrack(track(encoder));
                            packets.forEach(muxer::writePacket);
                            muxer.finish();
                        }
                        case "avi" -> {
                            var muxer = new AviMuxer(sink);
                            muxer.addTrack(track(encoder));
                            packets.forEach(muxer::writePacket);
                            muxer.finish();
                        }
                        default -> {
                            var muxer = new MatroskaMuxer(sink);
                            muxer.addTrack(track(encoder));
                            packets.forEach(muxer::writePacket);
                            muxer.finish();
                        }
                    }
                    var file = Scratch.directory("hap").resolve(label.replace(' ', '-') + "." + container);
                    Files.write(file, sink.toByteArray());
                    var stream = demux(file);
                    assertEquals(Codec.HAP, stream.track().codec(), container);
                    assertArrayEquals(encoder.codecConfig().orElseThrow(), stream.track().codecConfig().orElseThrow(),
                            container);
                    for (var i = 0; i < packets.size(); i++) {
                        assertArrayEquals(packets.get(i).data(), stream.packets().get(i).data(), container);
                    }
                    var theirs = ffmpegDecode(file, alpha ? "rgba" : "rgb24");
                    (format == HapFormat.HAP || format == HapFormat.HAP_ALPHA ? DXT : YCOCG).check(label + " " + container,
                            Tolerance.Difference.of(ours.toByteArray(), theirs));
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HapFormat.class)
    @DisplayName("crops the edge blocks of a picture whose size is not a multiple of 4")
    void oddSize(HapFormat format) {
        var width = 37;
        var height = 23;
        var picture = RgbaPicture.allocate(width, height, true);
        for (var i = 0; i < picture.pixels().length; i++) {
            picture.pixels()[i] = (byte) (i % 4 == 3 ? 255 : 90 + i % 4 * 30);
        }
        var encoder = new HapEncoder(width, height, HapEncoderOptions.builder().format(format).build());
        var data = encoder.encodeFrame(new VideoFrame(picture, 0, 3000, true)).data();
        var decoded = new HapDecoder(VideoDecoderOptions.defaults()).decodeFrame(data, 0, data.length, width, height);
        assertEquals(width, decoded.width());
        assertEquals(height, decoded.height());
        if (decoded instanceof RgbaPicture rgba && format != HapFormat.HAP_ALPHA_ONLY) {
            for (var i = 0; i < rgba.pixels().length; i++) {
                assertTrue(Math.abs((rgba.pixels()[i] & 0xFF) - (picture.pixels()[i] & 0xFF)) <= 8,
                        format + " sample " + i);
            }
        }
    }

    @Test
    @DisplayName("encodes alpha only from alpha, or from luma for a picture without alpha")
    void alphaOnly() {
        var encoder = new HapEncoder(WIDTH, HEIGHT, HapEncoderOptions.builder().format(HapFormat.HAP_ALPHA_ONLY)
                .build());
        var withAlpha = source(3, true);
        var gray = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            gray.y()[i % WIDTH + i / WIDTH * gray.yStride()] = (byte) (i * 7 / 13);
        }
        var decoder = new HapDecoder(track(encoder), VideoDecoderOptions.defaults());
        var pts = 0L;
        for (var picture : List.of(withAlpha, gray)) {
            var data = encoder.encodeFrame(new VideoFrame(picture, pts, 3000, true)).data();
            pts += 3000;
            var decoded = (YuvPicture) decoder.decodeFrame(data, 0, data.length, WIDTH, HEIGHT);
            var expected = new byte[WIDTH * HEIGHT];
            var actual = new byte[WIDTH * HEIGHT];
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    expected[y * WIDTH + x] = picture == gray ? gray.y()[y * gray.yStride() + x]
                            : withAlpha.pixels()[y * withAlpha.stride() + x * 4 + 3];
                    actual[y * WIDTH + x] = decoded.y()[y * decoded.yStride() + x];
                }
            }
            assertTrue(psnr(expected, actual) > 38, "alpha-only PSNR " + psnr(expected, actual));
        }
    }

    @Test
    @DisplayName("encodes half-precision RGB as unsigned BC6H close to the source")
    void hdr() {
        var picture = ChannelFloatPicture.allocate(WIDTH, HEIGHT, PixelLayout.RGB);
        var samples = picture.pixels();
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var at = (y * WIDTH + x) * 3;
                samples[at] = x / 16f;
                samples[at + 1] = y / 40f + 0.01f;
                samples[at + 2] = (x + y) % 9 == 0 ? 4f : 0.5f;
            }
        }
        var encoder = new HapEncoder(WIDTH, HEIGHT, HapEncoderOptions.builder().format(HapFormat.HAP_HDR).chunks(3)
                .build());
        var data = encoder.encodeFrame(new VideoFrame(picture, 0, 3000, true)).data();
        var decoded = (ChannelFloatPicture) new HapDecoder(track(encoder), VideoDecoderOptions.defaults())
                .decodeFrame(data, 0, data.length, WIDTH, HEIGHT);
        var error = 0.0;
        var signal = 0.0;
        for (var i = 0; i < samples.length; i++) {
            var d = samples[i] - decoded.pixels()[i];
            error += d * d;
            signal += samples[i] * samples[i];
        }
        var snr = 10 * StrictMath.log10(signal / error);
        assertTrue(snr > 25, "BC6H SNR " + snr);
    }

    @Test
    @DisplayName("is reached through VideoEncoders, as DXT1 HAP")
    void registry() {
        var encoder = VideoEncoders.create(Codec.HAP, VideoEncoderOptions.builder().size(WIDTH, HEIGHT)
                .frameRate(new Rational(30, 1)).bitrate(10_000_000).build());
        assertInstanceOf(HapEncoder.class, encoder);
        assertArrayEquals("Hap1".getBytes(), encoder.codecConfig().orElseThrow());
    }

    @Nested
    @DisplayName("damaged input")
    class Damaged {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"hap,snappy,4", "hap_q,none,2", "hap_alpha,snappy,1"})
        @DisplayName("raises only Malformed for truncations and byte flips")
        void damaged(String format, String compressor, int chunks) {
            var stream = demux(sample(format, compressor, chunks, "mov"));
            var data = stream.packets().getFirst().data();
            var random = new Random(3);
            var decoder = new HapDecoder(stream.track(), VideoDecoderOptions.defaults());
            for (var length = 0; length < data.length; length += Math.max(1, data.length / 61)) {
                var cut = Arrays.copyOf(data, length);
                try {
                    decoder.decodeFrame(cut, 0, cut.length, WIDTH, HEIGHT);
                } catch (MediaException.Malformed | MediaException.LimitExceeded expected) {
                    // a truncated frame may fail; anything but Malformed fails the test
                }
            }
            for (var flip = 0; flip < 300; flip++) {
                var damaged = data.clone();
                damaged[random.nextInt(Math.min(damaged.length, 64))] ^= (byte) (1 << random.nextInt(8));
                try {
                    decoder.decodeFrame(damaged, 0, damaged.length, WIDTH, HEIGHT);
                } catch (MediaException.Malformed | MediaException.LimitExceeded expected) {
                    // a damaged frame may fail; anything but Malformed fails the test
                }
            }
        }
    }
}
