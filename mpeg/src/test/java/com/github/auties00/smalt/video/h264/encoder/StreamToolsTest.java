package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the stream tools of the 8-bit encoder: multiple slices and slice size limits, periodic
 * intra refresh, temporal layers, the video usability options and the color matrices. Every stream
 * is decoded by Smalt and, where installed, by ffmpeg, and compared with the encoder's own
 * reconstruction sample for sample.
 */
@DisplayName("H264Encoder stream tools")
class StreamToolsTest {
    private static final Rational FPS = new Rational(30, 1);

    private static final VideoTrack TRACK = VideoTrack.builder().id(1).codec(Codec.H264)
            .timeBase(new Rational(1, 90_000)).build();

    /** The packets of a sequence and the reconstruction of every picture in coding order. */
    record Run(List<Packet> packets, List<byte[]> reconstructions, List<Integer> layers) {
        byte[] stream() {
            var out = new ByteArrayOutputStream();
            packets.forEach(packet -> out.writeBytes(packet.data()));
            return out.toByteArray();
        }
    }

    static H264EncoderOptions.Builder annexB(H264EncoderOptions base) {
        return base.toBuilder().packetFormat(H264PacketFormat.ANNEX_B).lookahead(0).bFrames(0);
    }

    static Run run(int width, int height, H264EncoderOptions options, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        var reconstructions = new ArrayList<byte[]>();
        var layers = new ArrayList<Integer>();
        try (var encoder = new H264Encoder(width, height, options)) {
            encoder.core().setReconstructUnreferenced(true);
            long pts = 0;
            for (var picture : pictures) {
                var before = packets.size();
                encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                pts += 3000;
                assertEquals(before + 1, packets.size(), "one packet per frame");
                reconstructions.add(encoder.core().lastReconstructionI420());
                layers.add(encoder.stats().lastTemporalLayer());
            }
        }
        return new Run(packets, reconstructions, layers);
    }

    /** Returns the NAL units of an Annex B access unit, without their start codes. */
    static List<byte[]> units(byte[] accessUnit) {
        var starts = new ArrayList<Integer>();
        for (var i = 0; i + 3 < accessUnit.length; i++) {
            if (accessUnit[i] == 0 && accessUnit[i + 1] == 0 && accessUnit[i + 2] == 0 && accessUnit[i + 3] == 1) {
                starts.add(i + 4);
                i += 3;
            }
        }
        var units = new ArrayList<byte[]>();
        for (var k = 0; k < starts.size(); k++) {
            var end = k + 1 < starts.size() ? starts.get(k + 1) - 4 : accessUnit.length;
            units.add(Arrays.copyOfRange(accessUnit, starts.get(k), end));
        }
        return units;
    }

    /** Returns the first_mb_in_slice of a slice NAL unit (the first Exp-Golomb code after the header). */
    static int firstMb(byte[] unit) {
        var bit = 8;
        var zeros = 0;
        while (((unit[bit >> 3] >> (7 - (bit & 7))) & 1) == 0) {
            zeros++;
            bit++;
        }
        bit++;
        var value = 0;
        for (var i = 0; i < zeros; i++) {
            value = (value << 1) | ((unit[bit >> 3] >> (7 - (bit & 7))) & 1);
            bit++;
        }
        return (1 << zeros) - 1 + value;
    }

    /** Decodes packets with Smalt, returning every output frame as planar 4:2:0 bytes. */
    static List<byte[]> decodeSmalt(List<Packet> packets, H264DecoderOptions options) {
        var frames = new ArrayList<byte[]>();
        try (var decoder = new H264Decoder(TRACK, options)) {
            for (var packet : packets) {
                decoder.decode(packet.withTrackId(1), (frame, damaged) -> frames.add(i420(frame.picture())));
            }
            decoder.flush((frame, damaged) -> frames.add(i420(frame.picture())));
        }
        return frames;
    }

    static byte[] i420(Picture picture) {
        var yuv = (YuvPicture) picture;
        var w = yuv.width();
        var h = yuv.height();
        var cw = yuv.chromaWidth();
        var ch = yuv.chromaHeight();
        var out = new byte[w * h + 2 * cw * ch];
        for (var y = 0; y < h; y++) {
            System.arraycopy(yuv.y(), y * yuv.yStride(), out, y * w, w);
        }
        for (var y = 0; y < ch; y++) {
            System.arraycopy(yuv.u(), y * yuv.uvStride(), out, w * h + y * cw, cw);
            System.arraycopy(yuv.v(), y * yuv.uvStride(), out, w * h + cw * ch + y * cw, cw);
        }
        return out;
    }

    static void assertFrames(List<byte[]> expected, List<byte[]> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": frame count");
        for (var i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i), what + ": frame " + i);
        }
    }

    /** Decodes an Annex B stream with ffmpeg, strictly, into planar 4:2:0 frames. */
    static List<byte[]> decodeFfmpeg(String name, byte[] stream, int frameSize) {
        var decoded = EncoderHarness.decodeStrict(EncoderHarness.write(name + ".264", stream));
        var frames = new ArrayList<byte[]>();
        for (var offset = 0; offset + frameSize <= decoded.length; offset += frameSize) {
            frames.add(Arrays.copyOfRange(decoded, offset, offset + frameSize));
        }
        assertEquals(0, decoded.length % frameSize, name + ": whole frames");
        return frames;
    }

    /** Decodes an Annex B stream with ffmpeg, accepting decoder warnings, into planar 4:2:0 frames. */
    static List<byte[]> decodeFfmpegLenient(String name, byte[] stream, int frameSize) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = EncoderHarness.write(name + ".264", stream);
        var executable = Oracle.find(Oracle.Tool.FFMPEG).orElseThrow();
        var command = List.of(executable.toString(), "-v", "quiet", "-f", "h264", "-i", file.toString(),
                "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-");
        try {
            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            var output = process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "ffmpeg finished");
            var frames = new ArrayList<byte[]>();
            for (var offset = 0; offset + frameSize <= output.length; offset += frameSize) {
                frames.add(Arrays.copyOfRange(output, offset, offset + frameSize));
            }
            return frames;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    static List<YuvPicture> pictures(int width, int height, int count, long seed) {
        return EncoderHarness.sequence(width, height, count, seed, 3.0);
    }

    @Nested
    @DisplayName("slices")
    class Slices {
        static Stream<Arguments> fixed() {
            return Stream.of(
                    Arguments.of(H264Profile.CONSTRAINED_BASELINE, 4, 1),
                    Arguments.of(H264Profile.MAIN, 3, 1),
                    Arguments.of(H264Profile.HIGH, 5, 4),
                    Arguments.of(H264Profile.HIGH, 40, 3),
                    Arguments.of(H264Profile.CONSTRAINED_BASELINE, 2, 4));
        }

        @ParameterizedTest(name = "{0}, {1} slices, {2} threads")
        @MethodSource("fixed")
        @DisplayName("a fixed slice count divides each picture into whole rows, decoded exactly by Smalt and ffmpeg")
        void fixedCount(H264Profile profile, int slices, int threads) {
            var width = 176;
            var height = 144;
            var options = annexB(H264EncoderOptions.forRealtime()).profile(profile).slices(slices).threads(threads)
                    .keyframeInterval(5).build();
            var run = run(width, height, options, pictures(width, height, 12, 7));
            var rows = 9;
            var expected = Math.min(slices, rows);
            for (var packet : run.packets()) {
                var firsts = new ArrayList<Integer>();
                for (var unit : units(packet.data())) {
                    var type = unit[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        firsts.add(firstMb(unit));
                    }
                }
                assertEquals(expected, firsts.size(), "slices per picture");
                for (var k = 0; k < expected; k++) {
                    assertEquals((int) ((long) k * rows / expected) * 11, firsts.get(k), "slice " + k + " start");
                }
            }
            assertFrames(run.reconstructions(), decodeSmalt(run.packets(), H264DecoderOptions.defaults()), "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertFrames(run.reconstructions(), decodeFfmpeg("slices-" + profile + "-" + slices, run.stream(),
                        run.reconstructions().getFirst().length), "ffmpeg");
            }
        }

        @Test
        @DisplayName("the stream does not depend on the thread count")
        void threadInvariant() {
            var options = annexB(H264EncoderOptions.forRealtime()).profile(H264Profile.HIGH).slices(3).build();
            var one = run(320, 240, options, pictures(320, 240, 8, 3));
            var four = run(320, 240, options.toBuilder().threads(4).build(), pictures(320, 240, 8, 3));
            for (var i = 0; i < one.packets().size(); i++) {
                assertArrayEquals(one.packets().get(i).data(), four.packets().get(i).data(), "packet " + i);
            }
        }

        static Stream<Arguments> limited() {
            return Stream.of(
                    Arguments.of(H264Profile.CONSTRAINED_BASELINE, 600, 1),
                    Arguments.of(H264Profile.CONSTRAINED_BASELINE, 1200, 1),
                    Arguments.of(H264Profile.MAIN, 500, 1),
                    Arguments.of(H264Profile.HIGH, 900, 2),
                    Arguments.of(H264Profile.HIGH, 300, 4));
        }

        @ParameterizedTest(name = "{0}, at most {1} bytes, {2} slices")
        @MethodSource("limited")
        @DisplayName("every slice NAL unit of more than one macroblock fits the size limit, and the stream decodes exactly")
        void maxSize(H264Profile profile, int limit, int slices) {
            var width = 320;
            var height = 240;
            var options = annexB(H264EncoderOptions.forRealtime()).profile(profile).maxSliceBytes(limit)
                    .slices(slices).bitrate(1_500_000).keyframeInterval(6).build();
            var run = run(width, height, options, pictures(width, height, 12, 11));
            var mbCount = 20 * 15;
            var total = 0L;
            var count = 0;
            for (var packet : run.packets()) {
                var firsts = new ArrayList<Integer>();
                var sizes = new ArrayList<Integer>();
                for (var unit : units(packet.data())) {
                    var type = unit[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        firsts.add(firstMb(unit));
                        sizes.add(unit.length);
                    }
                }
                firsts.add(mbCount);
                for (var k = 0; k < sizes.size(); k++) {
                    var macroblocks = firsts.get(k + 1) - firsts.get(k);
                    assertTrue(macroblocks > 0, "slices in raster order");
                    if (macroblocks > 1) {
                        assertTrue(sizes.get(k) <= limit, "slice of " + macroblocks + " macroblocks has "
                                + sizes.get(k) + " bytes");
                    }
                    if (k + 1 < sizes.size()) {
                        total += sizes.get(k);
                        count++;
                    }
                }
            }
            assertTrue(count > 10, "the limit divided the pictures");
            // A slice closes only when the next macroblock does not fit, so closed slices are well filled.
            assertTrue(total > 0.6 * limit * count, "mean slice size " + total / count + " of " + limit);
            assertFrames(run.reconstructions(), decodeSmalt(run.packets(), H264DecoderOptions.defaults()), "Smalt");
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertFrames(run.reconstructions(), decodeFfmpeg("slicebytes-" + profile + "-" + limit, run.stream(),
                        run.reconstructions().getFirst().length), "ffmpeg");
            }
        }

        @Test
        @DisplayName("slices and a size limit in a stream with B pictures and look-ahead decode alike in Smalt and ffmpeg")
        void withBFrames() {
            var width = 320;
            var height = 240;
            var options = H264EncoderOptions.forFile().toBuilder().packetFormat(H264PacketFormat.ANNEX_B).slices(3)
                    .maxSliceBytes(900).bitrate(1_200_000).threads(2).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new H264Encoder(width, height, options)) {
                var pts = 0L;
                for (var picture : pictures(width, height, 30, 19)) {
                    encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                    pts += 3000;
                }
                encoder.flush(packets::add);
            }
            var bPictures = 0;
            for (var packet : packets) {
                var firsts = new ArrayList<Integer>();
                var sizes = new ArrayList<Integer>();
                for (var unit : units(packet.data())) {
                    var type = unit[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        firsts.add(firstMb(unit));
                        sizes.add(unit.length);
                        bPictures += (unit[0] & 0x60) == 0 ? 1 : 0;
                    }
                }
                firsts.add(20 * 15);
                for (var k = 0; k < sizes.size(); k++) {
                    if (firsts.get(k + 1) - firsts.get(k) > 1) {
                        assertTrue(sizes.get(k) <= 900, "slice of " + sizes.get(k) + " bytes");
                    }
                }
            }
            assertTrue(bPictures > 0, "the stream has unreferenced B pictures");
            var smalt = decodeSmalt(packets, H264DecoderOptions.defaults());
            assertEquals(30, smalt.size());
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var stream = new ByteArrayOutputStream();
                packets.forEach(packet -> stream.writeBytes(packet.data()));
                assertFrames(smalt, decodeFfmpeg("slices-bframes", stream.toByteArray(), smalt.getFirst().length),
                        "ffmpeg");
            }
        }

        @Test
        @DisplayName("a limit below the size of one macroblock gives one-macroblock slices that still decode")
        void tinyLimit() {
            var options = annexB(H264EncoderOptions.forRealtime()).maxSliceBytes(64).quality(10)
                    .rateControl(H264RateControl.CONSTANT_QUALITY).build();
            var run = run(64, 48, options, pictures(64, 48, 3, 5));
            assertFrames(run.reconstructions(), decodeSmalt(run.packets(), H264DecoderOptions.defaults()), "Smalt");
        }
    }

    @Nested
    @DisplayName("periodic intra refresh")
    class IntraRefresh {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CONSTRAINED_BASELINE", "HIGH"})
        @DisplayName("a decoder that starts at a refresh cycle outputs exact pictures from the cycle's end on")
        void recovers(String profileName) {
            var profile = H264Profile.valueOf(profileName);
            var width = 256;
            var height = 128;
            var period = 6;
            var options = annexB(H264EncoderOptions.forRealtime()).profile(profile).intraRefresh(period)
                    .bitrate(600_000).keyframeInterval(5).build();
            var run = run(width, height, options, pictures(width, height, 40, 13));
            var cycleStarts = new ArrayList<Integer>();
            for (var i = 0; i < run.packets().size(); i++) {
                var packet = run.packets().get(i);
                assertEquals(i == 0, packet.keyframe(), "only the first picture is an IDR picture");
                for (var unit : units(packet.data())) {
                    if ((unit[0] & 0x1F) == 6 && (unit[1] & 0xFF) == 6) {
                        cycleStarts.add(i);
                    }
                }
            }
            // 16 columns refreshed over 6 frames: bands of 3 columns, cycles of 6 pictures after the IDR picture.
            assertEquals(List.of(1, 7, 13, 19, 25, 31, 37), cycleStarts);
            var full = decodeSmalt(run.packets(), H264DecoderOptions.defaults());
            assertFrames(run.reconstructions(), full, "Smalt");
            for (var start : List.of(13, 25)) {
                var tail = new ArrayList<>(run.packets().subList(start, run.packets().size()));
                var sps = units(run.packets().getFirst().data()).subList(0, 2);
                var head = new ByteArrayOutputStream();
                for (var unit : sps) {
                    head.writeBytes(new byte[]{0, 0, 0, 1});
                    head.writeBytes(unit);
                }
                head.writeBytes(tail.getFirst().data());
                tail.set(0, tail.getFirst().withData(head.toByteArray()));
                var joined = decodeSmalt(tail, H264DecoderOptions.defaults());
                var recovered = period - 1;
                assertEquals(run.packets().size() - start, joined.size(), "frames output from the recovery point");
                var report = new StringBuilder();
                for (var i = 0; i < joined.size() && i <= recovered + 1; i++) {
                    report.append("frame ").append(i).append(':').append(differingBlocks(full.get(start + i),
                            joined.get(i), width, height)).append('\n');
                }
                for (var i = recovered; i < joined.size(); i++) {
                    assertArrayEquals(full.get(start + i), joined.get(i), "Smalt joined at " + start + ", frame " + i
                            + "\n" + report);
                }
                assertFalse(Arrays.equals(full.get(start), joined.getFirst()),
                        "the first picture of the cycle shows the missing references");
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var stream = new ByteArrayOutputStream();
                    tail.forEach(packet -> stream.writeBytes(packet.data()));
                    var size = run.reconstructions().getFirst().length;
                    var ffmpeg = decodeFfmpegLenient("refresh-" + profile + "-" + start, stream.toByteArray(), size);
                    // ffmpeg starts output once the recovery point is reached
                    var offset = joined.size() - ffmpeg.size();
                    assertTrue(offset >= 0 && offset <= recovered, "ffmpeg output starts by the recovery point");
                    for (var i = Math.max(recovered - offset, 0); i < ffmpeg.size(); i++) {
                        assertArrayEquals(full.get(start + offset + i), ffmpeg.get(i), "ffmpeg joined at " + start);
                    }
                }
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertFrames(run.reconstructions(), decodeFfmpeg("refresh-full-" + profile, run.stream(),
                        run.reconstructions().getFirst().length), "ffmpeg");
            }
        }

        /** Lists the 16x16 luma blocks, as column/row, whose samples differ between two 4:2:0 frames. */
        static String differingBlocks(byte[] a, byte[] b, int width, int height) {
            var out = new StringBuilder();
            for (var by = 0; by < height / 16; by++) {
                for (var bx = 0; bx < width / 16; bx++) {
                    var differs = false;
                    for (var y = 0; y < 16 && !differs; y++) {
                        for (var x = 0; x < 16; x++) {
                            var i = (by * 16 + y) * width + bx * 16 + x;
                            if (a[i] != b[i]) {
                                differs = true;
                                break;
                            }
                        }
                    }
                    if (differs) {
                        out.append(' ').append(bx).append('/').append(by);
                    }
                }
            }
            return out.toString();
        }

        @Test
        @DisplayName("refreshed pictures stay close to the size of a P picture, while an IDR picture is much larger")
        void evenSizes() {
            var width = 320;
            var height = 240;
            var options = annexB(H264EncoderOptions.forRealtime()).intraRefresh(10).bitrate(500_000).build();
            var run = run(width, height, options, pictures(width, height, 40, 21));
            var largest = run.packets().stream().skip(5).mapToInt(Packet::size).max().orElseThrow();
            var mean = run.packets().stream().skip(5).mapToInt(Packet::size).average().orElseThrow();
            assertTrue(largest < 3 * mean, "largest refreshed picture " + largest + " against a mean of " + mean);
            assertTrue(run.packets().getFirst().size() > largest, "the IDR picture is the largest");
        }

        @Test
        @DisplayName("in file mode with look-ahead, a scene cut and the key frame interval produce no IDR picture, and the stream decodes")
        void fileMode() {
            var width = 160;
            var height = 96;
            var options = H264EncoderOptions.forFile().toBuilder().packetFormat(H264PacketFormat.ANNEX_B)
                    .intraRefresh(5).keyframeInterval(8).bitrate(500_000).build();
            var pictures = new ArrayList<Picture>();
            for (var i = 0; i < 30; i++) {
                // a scene cut at frame 15: another seed and a checkerboard
                pictures.add(i < 15 ? EncoderHarness.synthetic(width, height, i, 3, 2.0)
                        : EncoderHarness.checkerboard(width, height, i));
            }
            var packets = new ArrayList<Packet>();
            try (var encoder = new H264Encoder(width, height, options)) {
                var pts = 0L;
                for (var picture : pictures) {
                    encoder.encode(new VideoFrame(picture, pts, 3000, false), packets::add);
                    pts += 3000;
                }
                encoder.flush(packets::add);
            }
            assertEquals(30, packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(i == 0, packets.get(i).keyframe(), "IDR picture at " + i);
                assertEquals(packets.get(i).pts(), packets.get(i).dts(), "no reordering, packet " + i);
            }
            var smalt = decodeSmalt(packets, H264DecoderOptions.defaults());
            assertEquals(30, smalt.size());
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var stream = new ByteArrayOutputStream();
                packets.forEach(packet -> stream.writeBytes(packet.data()));
                assertFrames(smalt, decodeFfmpeg("refresh-file", stream.toByteArray(), smalt.getFirst().length),
                        "ffmpeg");
            }
        }

        @Test
        @DisplayName("a key frame request still yields an IDR picture, and refresh restarts after it")
        void keyframeRequest() {
            var options = annexB(H264EncoderOptions.forRealtime()).intraRefresh(4).build();
            var packets = new ArrayList<Packet>();
            try (var encoder = new H264Encoder(128, 64, options)) {
                for (var i = 0; i < 12; i++) {
                    if (i == 6) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(EncoderHarness.synthetic(128, 64, i, 2, 1.0), i * 3000L, 0, false),
                            packets::add);
                }
            }
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(i == 0 || i == 6, packets.get(i).keyframe(), "IDR picture at " + i);
            }
            var recoveryAt7 = units(packets.get(7).data()).stream()
                    .anyMatch(unit -> (unit[0] & 0x1F) == 6 && (unit[1] & 0xFF) == 6);
            assertTrue(recoveryAt7, "a cycle starts after the requested IDR picture");
        }
    }

    @Nested
    @DisplayName("temporal layers")
    class TemporalLayers {
        static Stream<Arguments> layouts() {
            return Stream.of(
                    Arguments.of(2, H264Profile.CONSTRAINED_BASELINE, List.of(0, 1)),
                    Arguments.of(3, H264Profile.MAIN, List.of(0, 2, 1, 2)),
                    Arguments.of(4, H264Profile.HIGH, List.of(0, 3, 2, 3, 1, 3, 2, 3)),
                    Arguments.of(3, H264Profile.CONSTRAINED_BASELINE, List.of(0, 2, 1, 2)));
        }

        @ParameterizedTest(name = "{0} layers, {1}")
        @MethodSource("layouts")
        @DisplayName("each layer and those below decode on their own to exactly the full stream's pictures")
        void layered(int layers, H264Profile profile, List<Integer> pattern) {
            var width = 192;
            var height = 128;
            var options = annexB(H264EncoderOptions.forRealtime()).profile(profile).temporalLayers(layers)
                    .keyframeInterval(20).build();
            var run = run(width, height, options, pictures(width, height, 34, 17));
            for (var i = 0; i < run.layers().size(); i++) {
                var sinceIdr = i % 20;
                assertEquals(pattern.get(sinceIdr % pattern.size()), run.layers().get(i), "layer of picture " + i);
                var packet = run.packets().get(i);
                var unitList = units(packet.data());
                for (var k = 0; k < unitList.size(); k++) {
                    var type = unitList.get(k)[0] & 0x1F;
                    if (type == 1 || type == 5) {
                        var prefix = unitList.get(k - 1);
                        assertEquals(14, prefix[0] & 0x1F, "a prefix NAL unit precedes every slice");
                        assertEquals(prefix[0] & 0x60, unitList.get(k)[0] & 0x60, "the prefix has the slice's nal_ref_idc");
                        assertEquals(run.layers().get(i), (prefix[3] & 0xFF) >> 5, "temporal_id");
                    }
                }
                assertEquals(run.layers().get(i) == layers - 1, packet.flags().contains(PacketFlag.DISCARDABLE),
                        "the highest layer is discardable");
            }
            var full = decodeSmalt(run.packets(), H264DecoderOptions.defaults());
            assertFrames(run.reconstructions(), full, "Smalt");
            var size = run.reconstructions().getFirst().length;
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertFrames(run.reconstructions(), decodeFfmpeg("layers-" + layers + "-" + profile, run.stream(), size),
                        "ffmpeg");
            }
            for (var top = 0; top < layers - 1; top++) {
                var kept = new ArrayList<Packet>();
                var expected = new ArrayList<byte[]>();
                for (var i = 0; i < run.packets().size(); i++) {
                    if (run.layers().get(i) <= top) {
                        kept.add(run.packets().get(i));
                        expected.add(full.get(i));
                    }
                }
                assertFrames(expected, decodeSmalt(kept, H264DecoderOptions.defaults()), "Smalt, layers to " + top);
                assertFrames(expected, decodeSmalt(run.packets(), H264DecoderOptions.builder().maxTemporalId(top)
                        .build()), "Smalt with maxTemporalId " + top);
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var stream = new ByteArrayOutputStream();
                    kept.forEach(packet -> stream.writeBytes(packet.data()));
                    assertFrames(expected, decodeFfmpeg("layers-" + layers + "-" + profile + "-to" + top,
                            stream.toByteArray(), size), "ffmpeg, layers to " + top);
                }
            }
        }

        @Test
        @DisplayName("frames of higher layers are coded coarser and smaller")
        void layerRates() {
            var options = annexB(H264EncoderOptions.forRealtime()).temporalLayers(3).bitrate(400_000).build();
            var run = run(320, 240, options, pictures(320, 240, 40, 23));
            var sums = new long[3];
            var counts = new int[3];
            for (var i = 4; i < run.packets().size(); i++) {
                sums[run.layers().get(i)] += run.packets().get(i).size();
                counts[run.layers().get(i)]++;
            }
            assertTrue(sums[0] / counts[0] > sums[2] / counts[2], "layer 0 frames are larger than layer 2 frames");
        }

        @Test
        @DisplayName("intra refresh cannot be combined with temporal layers, and a level too small for the layers is rejected")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> H264EncoderOptions.builder().temporalLayers(2)
                    .intraRefresh(10).build());
            assertThrows(IllegalArgumentException.class, () -> H264EncoderOptions.builder().temporalLayers(5).build());
            var options = annexB(H264EncoderOptions.forRealtime()).temporalLayers(4).level(H264Level.LEVEL_3).build();
            assertThrows(IllegalArgumentException.class, () -> new H264Encoder(1280, 720, options));
        }
    }

    @Nested
    @DisplayName("video usability information")
    class Vui {
        static Stream<Arguments> ratios() {
            return Stream.of(
                    Arguments.of(new Rational(1, 1), 1L, null, null),
                    Arguments.of(new Rational(12, 11), 2L, null, null),
                    Arguments.of(new Rational(4, 3), 14L, null, null),
                    Arguments.of(new Rational(32, 27), 255L, 32L, 27L),
                    Arguments.of(new Rational(64, 45), 255L, 64L, 45L),
                    Arguments.of(new Rational(2, 1), 16L, null, null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ratios")
        @DisplayName("the sample aspect ratio is written as its Table E-1 index or as Extended_SAR, and Smalt reads it back")
        void sampleAspectRatio(Rational ratio, Long idc, Long width, Long height) {
            var options = annexB(H264EncoderOptions.forRealtime()).sampleAspectRatio(ratio).build();
            var run = run(64, 64, options, pictures(64, 64, 2, 1));
            var info = H264StreamInfo.probe(run.packets().getFirst().data());
            assertEquals(ratio.reduce(), info.sampleAspectRatio().orElseThrow().reduce());
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var headers = EncoderHarness.traceHeaders(EncoderHarness.write("sar-" + ratio.numerator() + "-"
                        + ratio.denominator() + ".264", run.stream()));
                assertEquals(idc, headers.get("aspect_ratio_idc"));
                assertEquals(width, headers.get("sar_width"));
                assertEquals(height, headers.get("sar_height"));
            }
        }

        @Test
        @DisplayName("the default stream keeps square samples, no chroma location and a variable frame rate")
        void defaults() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var run = run(64, 64, annexB(H264EncoderOptions.forRealtime()).build(), pictures(64, 64, 2, 1));
            var headers = EncoderHarness.traceHeaders(EncoderHarness.write("vui-defaults.264", run.stream()));
            assertEquals(1L, headers.get("aspect_ratio_idc"));
            assertEquals(0L, headers.get("chroma_loc_info_present_flag"));
            assertEquals(0L, headers.get("fixed_frame_rate_flag"));
        }

        @Test
        @DisplayName("chroma siting, a fixed frame rate and HDR metadata reach the stream and Smalt's decoded frames")
        void colorExtras() {
            var display = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 1000, 0.005);
            var level = new ContentLightLevel(1000, 400);
            var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.PQ).withChromaSiting(ChromaSiting.TOP_LEFT)
                    .withMasteringDisplay(display).withContentLightLevel(level);
            var options = annexB(H264EncoderOptions.forRealtime()).color(color).fixedFrameRate(true)
                    .keyframeInterval(3).build();
            var run = run(64, 64, options, pictures(64, 64, 4, 1));
            for (var i = 0; i < run.packets().size(); i++) {
                var seiCount = units(run.packets().get(i).data()).stream().filter(unit -> (unit[0] & 0x1F) == 6)
                        .count();
                assertEquals(i % 3 == 0 ? 1 : 0, seiCount, "HDR SEI in every IDR access unit, packet " + i);
            }
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.defaults())) {
                for (var packet : run.packets()) {
                    decoder.decode(packet.withTrackId(1), (frame, damaged) -> frames.add(frame));
                }
                decoder.flush((frame, damaged) -> frames.add(frame));
            }
            var decoded = ((YuvPicture) frames.getFirst().picture()).color();
            assertEquals(ChromaSiting.TOP_LEFT, decoded.chromaSiting());
            assertEquals(MatrixCoefficients.BT2020_NCL, decoded.matrix());
            assertEquals(level, decoded.contentLightLevel().orElseThrow());
            var mastering = decoded.masteringDisplay().orElseThrow();
            assertEquals(1000, mastering.maxLuminance(), 1e-6);
            assertEquals(0.005, mastering.minLuminance(), 1e-6);
            assertEquals(0.708, mastering.red().x(), 0.00002);
            assertEquals(0.797, mastering.green().y(), 0.00002);
            assertEquals(0.3127, mastering.whitePoint().x(), 0.00002);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var headers = EncoderHarness.traceHeaders(EncoderHarness.write("vui-extras.264", run.stream()));
                assertEquals(1L, headers.get("chroma_loc_info_present_flag"));
                assertEquals(2L, headers.get("chroma_sample_loc_type_top_field"));
                assertEquals(1L, headers.get("fixed_frame_rate_flag"));
            }
        }

        @Test
        @DisplayName("sample aspect ratios outside 1 to 65535 are rejected")
        void invalid() {
            assertThrows(IllegalArgumentException.class,
                    () -> H264EncoderOptions.builder().sampleAspectRatio(new Rational(65536, 1)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> H264EncoderOptions.builder().sampleAspectRatio(new Rational(0, 1)).build());
        }
    }

    @Nested
    @DisplayName("color matrices")
    class Matrices {
        static Stream<Arguments> matrices() {
            return Stream.of(
                    Arguments.of(MatrixCoefficients.YCGCO, ColorRange.FULL, ColorPrimaries.BT709,
                            TransferCharacteristics.BT709),
                    Arguments.of(MatrixCoefficients.YCGCO, ColorRange.LIMITED, ColorPrimaries.BT709,
                            TransferCharacteristics.BT709),
                    Arguments.of(MatrixCoefficients.BT2020_CL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                            TransferCharacteristics.BT2020_10));
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("matrices")
        @DisplayName("RGB input converts with the matrix, the VUI names it, and the decoded frames convert back to the input")
        void roundTrip(MatrixCoefficients matrix, ColorRange range, ColorPrimaries primaries,
                       TransferCharacteristics transfer) {
            var color = new ColorInfo(matrix, range, primaries, transfer);
            var width = 96;
            var height = 64;
            var rgba = gradient(width, height);
            var options = annexB(H264EncoderOptions.forRealtime()).color(color).quality(4)
                    .rateControl(H264RateControl.CONSTANT_QUALITY).quantizers(0, 51).build();
            var run = run(width, height, options, List.of(rgba, rgba));
            var info = H264StreamInfo.probe(run.packets().getFirst().data());
            assertEquals(matrix, info.color().matrix());
            var frames = new ArrayList<VideoFrame>();
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.defaults())) {
                for (var packet : run.packets()) {
                    decoder.decode(packet.withTrackId(1), (frame, damaged) -> frames.add(frame));
                }
                decoder.flush((frame, damaged) -> frames.add(frame));
            }
            var decoded = (YuvPicture) frames.getLast().picture();
            assertEquals(matrix, decoded.color().matrix());
            var back = ImageOps.toRgba(decoded);
            var expected = ImageOps.toRgba(ImageOps.toYuv(rgba, ChromaFormat.YUV420, color));
            var error = 0L;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 3; c++) {
                        var d = (back.pixels()[y * back.stride() + 4 * x + c] & 0xFF)
                                - (expected.pixels()[y * expected.stride() + 4 * x + c] & 0xFF);
                        error += (long) d * d;
                    }
                }
            }
            var mse = (double) error / (3.0 * width * height);
            assertTrue(mse < 4, matrix + " mean squared error " + mse);
        }

        @Test
        @DisplayName("the identity matrix is rejected without 4:4:4")
        void identityNeeds444() {
            var identity = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            assertThrows(IllegalArgumentException.class, () -> H264EncoderOptions.builder().color(identity).build());
            assertTrue(Optional.of(H264EncoderOptions.builder().color(identity).profile(H264Profile.HIGH_444_PREDICTIVE)
                    .chromaFormat(ChromaFormat.YUV444).build()).isPresent());
        }

        static RgbaPicture gradient(int width, int height) {
            var picture = RgbaPicture.allocate(width, height, false);
            var pixels = picture.pixels();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var i = y * picture.stride() + 4 * x;
                    pixels[i] = (byte) (x * 255 / (width - 1));
                    pixels[i + 1] = (byte) (y * 255 / (height - 1));
                    pixels[i + 2] = (byte) ((x + y) * 2);
                    pixels[i + 3] = (byte) 255;
                }
            }
            return picture;
        }
    }
}
