package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.sei.SeiWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the SEI messages the H.264 decoder renders or reports: film grain against ffmpeg's renderer (which follows
 * SMPTE RDD 5 for 8-bit 4:2:0 frames) on x264 streams with injected film grain characteristics messages, and the
 * picture timing, frame packing, display orientation and alternative transfer characteristics messages of x264
 * and ffmpeg's h264_metadata filter, and of streams of I_PCM pictures written here.
 */
@DisplayName("H.264 SEI messages")
class H264SeiTest {
    static final List<List<SeiWriter.Interval>> GRAIN = List.of(
            List.of(new SeiWriter.Interval(0, 120, 60, 8, 6), new SeiWriter.Interval(121, 255, 100, 4, 10)),
            List.of(new SeiWriter.Interval(0, 127, 40, 6, 6), new SeiWriter.Interval(128, 255, 90, 12, 3)),
            List.of(new SeiWriter.Interval(0, 127, 80, 3, 12), new SeiWriter.Interval(128, 255, 30, 10, 10)));

    static Path x264(String size, int frames, List<String> options) {
        var arguments = new ArrayList<>(List.of("-vf", "noise=alls=30:allf=t", "-frames:v", Integer.toString(frames),
                "-c:v", "libx264"));
        arguments.addAll(options);
        arguments.addAll(List.of("-f", "h264"));
        return Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=25"), arguments);
    }

    static Path write(String name, byte[] data) {
        try {
            return Files.write(Scratch.directory("h264-sei").resolve(name), data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] grainSei(int persistence) {
        return SeiWriter.nal(false, 19, SeiWriter.filmGrain(false, 3, persistence, GRAIN));
    }

    static byte[] ffmpegDecode(Path stream) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i", stream.toString(),
                "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
    }

    static List<VideoFrame> decode(byte[] stream, H264DecoderOptions options, List<byte[]> samples) {
        var frames = new ArrayList<VideoFrame>();
        var track = H264TestStreams.track(Optional.empty(), Optional.empty());
        try (var decoder = new H264Decoder(track, options)) {
            java.util.function.Consumer<VideoFrame> sink = frame -> {
                frames.add(frame);
                samples.add(H264TestStreams.serialize((YuvPicture) frame.picture()));
            };
            for (var packet : H264DifferentialTest.annexBPackets(stream)) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return frames;
    }

    static void assertSamples(byte[] reference, List<byte[]> samples, String name) {
        assertFalse(samples.isEmpty(), name + ": no frames");
        var size = samples.getFirst().length;
        assertEquals(reference.length / size, samples.size(), name + ": frame count");
        for (var i = 0; i < samples.size(); i++) {
            assertArrayEquals(Arrays.copyOfRange(reference, i * size, (i + 1) * size), samples.get(i),
                    name + ": frame " + i);
        }
    }

    record GrainCase(String name, String size, int frames, List<String> options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<GrainCase> grainCases() {
        return Stream.of(
                new GrainCase("B frames and several IDR pictures", "176x144", 20, List.of("-bf", "3", "-g", "7")),
                new GrainCase("open GOP with non-IDR I frames", "176x144", 18,
                        List.of("-x264-params", "open-gop=1:keyint=6:min-keyint=6:bframes=2:scenecut=0")),
                new GrainCase("frame cropping", "176x120", 6, List.of("-bf", "0")),
                new GrainCase("MBAFF frames", "176x144", 6, List.of("-bf", "1", "-flags", "+ildct+ilme",
                        "-x264-params", "tff=1")),
                new GrainCase("CAVLC baseline", "128x96", 8, List.of("-profile:v", "baseline")));
    }

    @Nested
    @DisplayName("film grain")
    class FilmGrain {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264SeiTest#grainCases")
        @DisplayName("matches ffmpeg's RDD 5 renderer")
        void ffmpeg(GrainCase c) {
            var clean = Fixtures.read(x264(c.size(), c.frames(), c.options()));
            var stream = SeiWriter.insert(clean, false, false, picture -> List.of(grainSei(0)));
            var reference = ffmpegDecode(write("grain-" + c.name().hashCode() + ".264", stream));
            for (var threads : new int[]{1, 4}) {
                var samples = new ArrayList<byte[]>();
                decode(stream, H264DecoderOptions.builder().threads(threads).build(), samples);
                assertSamples(reference, samples, c + " with " + threads + " threads");
            }
            var plain = new ArrayList<byte[]>();
            decode(clean, H264DecoderOptions.defaults(), plain);
            assertFalse(Arrays.equals(plain.getFirst(), Arrays.copyOf(reference, plain.getFirst().length)),
                    "the grain changes the first frame");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"yuv420p10le", "yuv422p", "yuv422p10le", "yuv444p", "yuv444p10le", "gray", "gray10le"})
        @DisplayName("renders the other chroma formats and bit depths on each frame with its picture order offset")
        void formats(String pixelFormat) {
            var clean = Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=25"),
                    List.of("-vf", "noise=alls=30:allf=t,format=" + pixelFormat, "-frames:v", "4", "-c:v", "libx264",
                            "-bf", "0", "-g", "100", "-f", "h264")));
            var stream = SeiWriter.insert(clean, false, false, picture -> List.of(grainSei(0)));
            var params = SeiWriter.parseFilmGrain(SeiWriter.filmGrain(false, 3, 0, GRAIN), false);
            var options = H264DecoderOptions.builder().highBitDepth(true).build();
            var plain = new ArrayList<VideoFrame>();
            var expected = new ArrayList<byte[]>();
            var track = H264TestStreams.track(Optional.empty(), Optional.empty());
            try (var decoder = new H264Decoder(track, options.toBuilder().filmGrain(false).build())) {
                // one IDR picture and P pictures in output order, picture order counts 0, 2, 4 and 6
                java.util.function.Consumer<VideoFrame> sink = frame -> expected.add(
                        SeiWriter.grained(frame.picture(), params, 2 * plain.size()));
                java.util.function.Consumer<VideoFrame> counting = frame -> {
                    sink.accept(frame);
                    plain.add(frame);
                };
                for (var packet : H264DifferentialTest.annexBPackets(clean)) {
                    decoder.decode(packet, counting);
                }
                decoder.flush(counting);
            }
            var samples = new ArrayList<byte[]>();
            var track2 = H264TestStreams.track(Optional.empty(), Optional.empty());
            try (var decoder = new H264Decoder(track2, options)) {
                java.util.function.Consumer<VideoFrame> sink = frame -> samples.add(SeiWriter.raw(frame.picture()));
                for (var packet : H264DifferentialTest.annexBPackets(stream)) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
            assertEquals(4, samples.size());
            for (var i = 0; i < samples.size(); i++) {
                assertArrayEquals(expected.get(i), samples.get(i), pixelFormat + " frame " + i);
            }
        }

        @Test
        @DisplayName("the option is on by default and survives toBuilder, equals, hashCode and toString")
        void option() {
            var defaults = H264DecoderOptions.defaults();
            assertTrue(defaults.filmGrain());
            assertTrue(H264DecoderOptions.forRealtime().filmGrain());
            var off = defaults.toBuilder().filmGrain(false).build();
            assertFalse(off.filmGrain());
            assertEquals(off, off.toBuilder().build());
            assertEquals(off.hashCode(), off.toBuilder().build().hashCode());
            assertNotEquals(defaults, off);
            assertTrue(off.toString().contains("filmGrain=false"), off.toString());
        }

        @Test
        @DisplayName("is left out when the option is clear")
        void disabled() {
            var clean = Fixtures.read(x264("176x144", 6, List.of("-bf", "2")));
            var stream = SeiWriter.insert(clean, false, false, picture -> List.of(grainSei(0)));
            var reference = ffmpegDecode(write("grain-plain.264", clean));
            var samples = new ArrayList<byte[]>();
            decode(stream, H264DecoderOptions.builder().filmGrain(false).build(), samples);
            assertSamples(reference, samples, "film grain off");
        }

        @Test
        @DisplayName("a message with a repetition period persists in output order until a cancel or an IDR picture")
        void persistence() {
            var clean = Fixtures.read(x264("176x144", 16, List.of("-bf", "2", "-g", "12", "-sc_threshold", "0")));
            // a lasting message on picture 0 (in decoding order) ends at the cancel on picture 5; the IDR picture 12
            // starts a sequence whose picture 13 carries a lasting message
            IntFunction<List<byte[]>> persisting = picture -> switch (picture) {
                case 0, 13 -> List.of(grainSei(1));
                case 5 -> List.of(SeiWriter.nal(false, 19, SeiWriter.filmGrainCancel()));
                default -> List.of();
            };
            var samples = new ArrayList<byte[]>();
            decode(SeiWriter.insert(clean, false, false, persisting), H264DecoderOptions.defaults(), samples);
            // ffmpeg renders only messages for the current picture, so the reference gives every picture the
            // persistence covers in output order its own message
            var order = displayOrder(clean);
            IntFunction<List<byte[]>> explicit = picture -> {
                var rank = order.indexOf(picture);
                var covered = rank >= order.indexOf(0) && rank < order.indexOf(5) || rank >= order.indexOf(13);
                return covered ? List.of(grainSei(0)) : List.of();
            };
            var reference = ffmpegDecode(write("grain-persist.264", SeiWriter.insert(clean, false, false, explicit)));
            assertSamples(reference, samples, "persistence in output order " + order);
        }
    }

    /** Returns the pictures of a stream, numbered in decoding order, in their output order according to ffprobe. */
    static List<Integer> displayOrder(byte[] stream) {
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_frames", "-show_entries", "frame=pkt_pos", "-of", "csv=p=0",
                write("order.264", stream).toString())), StandardCharsets.UTF_8);
        var positions = probe.lines().filter(line -> !line.isBlank()).map(line -> Long.parseLong(line.split(",")[0].trim()))
                .toList();
        var sorted = positions.stream().sorted().toList();
        var order = new ArrayList<Integer>();
        for (var position : positions) {
            order.add(sorted.indexOf(position));
        }
        return order;
    }

    @Nested
    @DisplayName("display information")
    class Display {
        @ParameterizedTest(name = "frame packing type {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
        @DisplayName("x264's frame packing arrangement reaches every frame")
        void framePacking(int type) {
            var stream = Fixtures.read(x264("96x96", 6, List.of("-bf", "2", "-x264-params", "frame-packing=" + type)));
            var frames = decode(stream, H264DecoderOptions.defaults(), new ArrayList<>());
            assertEquals(6, frames.size());
            var frame0 = new ArrayList<Boolean>();
            for (var frame : frames) {
                var packing = frame.sideData().framePacking().orElseThrow();
                assertEquals(FramePacking.Arrangement.ofCode(type), packing.arrangement());
                assertEquals(type == 0, packing.quincunxSampling());
                frame0.add(packing.currentFrameIsFrame0());
            }
            if (type == 5) {
                for (var i = 1; i < frame0.size(); i++) {
                    assertNotEquals(frame0.get(i - 1), frame0.get(i), "frame alternation alternates " + frame0);
                }
            } else {
                assertFalse(frame0.contains(true));
            }
        }

        @Test
        @DisplayName("h264_metadata's display orientation reaches every frame")
        void displayOrientation() {
            var source = x264("96x64", 5, List.of("-bf", "2"));
            var arguments = List.of("-i", source.toString(), "-c", "copy", "-bsf:v",
                    "h264_metadata=display_orientation=insert:rotate=90:flip=horizontal", "-f", "h264");
            var stream = Fixtures.read(Fixtures.ffmpeg("h264", List.of(), arguments));
            var frames = decode(stream, H264DecoderOptions.defaults(), new ArrayList<>());
            assertEquals(5, frames.size());
            for (var frame : frames) {
                // a horizontal flip then a quarter turn counterclockwise is the transposition
                assertEquals(Orientation.TRANSPOSE, frame.sideData().orientation().orElseThrow());
            }
            var plain = decode(Fixtures.read(source), H264DecoderOptions.defaults(), new ArrayList<>());
            assertTrue(plain.getFirst().sideData().isEmpty());
        }

        @Test
        @DisplayName("an alternative transfer characteristics message replaces the VUI transfer")
        void alternativeTransfer() {
            var options = "colorprim=bt2020:transfer=bt2020-10:colormatrix=bt2020nc";
            var hlg = decode(Fixtures.read(x264("64x64", 3, List.of("-x264-params",
                    options + ":alternative-transfer=arib-std-b67"))), H264DecoderOptions.defaults(), new ArrayList<>());
            var plain = decode(Fixtures.read(x264("64x64", 3, List.of("-x264-params", options))),
                    H264DecoderOptions.defaults(), new ArrayList<>());
            for (var frame : hlg) {
                assertEquals(TransferCharacteristics.HLG, ((YuvPicture) frame.picture()).color().transfer());
            }
            assertEquals(TransferCharacteristics.BT2020_10, ((YuvPicture) plain.getFirst().picture()).color().transfer());
        }

        @Test
        @DisplayName("the preferred transfer lasts until an IDR picture whose access unit does not repeat it")
        void alternativeTransferSequence() {
            var options = "colorprim=bt2020:transfer=bt2020-10:colormatrix=bt2020nc";
            var hlg = Fixtures.read(x264("64x64", 3, List.of("-bf", "0", "-x264-params",
                    options + ":alternative-transfer=arib-std-b67")));
            var plain = Fixtures.read(x264("64x64", 3, List.of("-bf", "0", "-x264-params", options)));
            var sequences = new java.io.ByteArrayOutputStream();
            sequences.writeBytes(hlg);
            sequences.writeBytes(plain);
            sequences.writeBytes(hlg);
            var frames = decode(sequences.toByteArray(), H264DecoderOptions.defaults(), new ArrayList<>());
            assertEquals(9, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var expected = i / 3 == 1 ? TransferCharacteristics.BT2020_10 : TransferCharacteristics.HLG;
                assertEquals(expected, ((YuvPicture) frames.get(i).picture()).color().transfer(), "frame " + i);
            }
        }

        @Test
        @DisplayName("x264's picture timing of interlaced frames gives the scan of the pic_struct ffmpeg traces")
        void interlaced() {
            // without B frames the output order is the decoding order the trace lists the messages in; x264 writes
            // pic_struct 3 (top field first) while its field order counts put the bottom field first, and
            // pic_struct, which states the display, wins
            var path = x264("176x144", 6, List.of("-bf", "0", "-flags", "+ildct+ilme", "-x264-params",
                    "pic-struct=1:tff=1"));
            var frames = decode(Fixtures.read(path), H264DecoderOptions.defaults(), new ArrayList<>());
            var picStructs = tracedPicStructs(path);
            assertEquals(picStructs.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertEquals(DisplayExpectations.fieldOrder(picStructs.get(i)),
                        frames.get(i).sideData().fieldOrder().orElseThrow(), "frame " + i);
            }
        }
    }

    /** Returns the pic_struct values of the picture timing messages of a stream, as ffmpeg's trace_headers lists them. */
    static List<Integer> tracedPicStructs(Path stream) {
        var trace = Oracle.runMerged(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "info", "-i",
                stream.toString(), "-c", "copy", "-bsf:v", "trace_headers", "-f", "null", "-"));
        var result = new ArrayList<Integer>();
        var matcher = java.util.regex.Pattern.compile("\\bpic_struct\\s+[01]+ = (\\d+)").matcher(trace);
        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return result;
    }

    /** The scans the picture timing semantics give each pic_struct. */
    static final class DisplayExpectations {
        static FieldOrder fieldOrder(int picStruct) {
            return switch (picStruct) {
                case 3, 5 -> FieldOrder.TOP_FIRST;
                case 4, 6 -> FieldOrder.BOTTOM_FIRST;
                default -> FieldOrder.PROGRESSIVE;
            };
        }
    }

    @Nested
    @DisplayName("picture timing")
    class PictureTiming {
        /**
         * Writes a stream of 32x32 IDR pictures of I_PCM macroblocks whose VUI signals pic_struct, with 29.97 Hz
         * timing and, when {@code delayLength} is above 0, NAL HRD parameters whose delays precede pic_struct.
         */
        static byte[] stream(int[] picStructs, int delayLength) {
            var out = new java.io.ByteArrayOutputStream();
            var sps = new H264BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2).ue(1).bit(0).ue(1).ue(1)
                    .bit(1).bit(1).bit(0).bit(1);
            sps.bit(0).bit(0).bit(0).bit(0).bit(1).bits(1001, 32).bits(60000, 32).bit(1);
            sps.bit(delayLength > 0 ? 1 : 0);
            if (delayLength > 0) {
                sps.ue(0).bits(0, 4).bits(0, 4).ue(1000).ue(1000).bit(0).bits(23, 5).bits(delayLength - 1, 5)
                        .bits(delayLength - 1, 5).bits(24, 5);
            }
            sps.bit(0);
            if (delayLength > 0) {
                sps.bit(0);
            }
            sps.bit(1).bit(0);
            for (var unit : List.of(H264BitWriter.nal(3, 7, sps.trailing()), H264BitWriter.pps())) {
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(unit);
            }
            int[] clockTimestamps = {1, 1, 1, 2, 2, 3, 3, 2, 3};
            for (var i = 0; i < picStructs.length; i++) {
                var timing = new SeiWriter.Bits();
                if (delayLength > 0) {
                    timing.u(delayLength, (1L << delayLength) - 1).u(delayLength, i);
                }
                timing.u(4, picStructs[i]);
                for (var c = 0; c < clockTimestamps[picStructs[i]]; c++) {
                    timing.flag(false);
                }
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(SeiWriter.nal(false, 1, timing.bytes()));
                var slice = new H264BitWriter().ue(0).ue(7).ue(0).bits(0, 4).ue(i % 2).bit(0).bit(0).se(0).ue(1);
                for (var mb = 0; mb < 4; mb++) {
                    slice.ue(25);
                    while (!slice.aligned()) {
                        slice.bit(0);
                    }
                    for (var sample = 0; sample < 384; sample++) {
                        slice.bits((sample * 3 + mb * 40 + i * 9) & 0xFF, 8);
                    }
                }
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(H264BitWriter.nal(3, 5, slice.trailing()));
            }
            return out.toByteArray();
        }

        @ParameterizedTest(name = "delays of {0} bits")
        @ValueSource(ints = {0, 7, 24})
        @DisplayName("pic_struct gives the scan, the repeated fields and the duration ffprobe reports")
        void picStruct(int delayLength) {
            int[] picStructs = {0, 3, 4, 5, 6, 7, 8, 1, 2};
            var stream = stream(picStructs, delayLength);
            var frames = decode(stream, H264DecoderOptions.defaults(), new ArrayList<>());
            assertEquals(picStructs.length, frames.size());
            var path = write("timing-" + delayLength + ".264", stream);
            var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                    "-show_frames", "-show_entries", "frame=repeat_pict", "-of", "csv=p=0", path.toString())),
                    StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).map(line -> line.split(",")[0].trim())
                    .toList();
            FieldOrder[] orders = {FieldOrder.PROGRESSIVE, FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST,
                    FieldOrder.TOP_FIRST, FieldOrder.BOTTOM_FIRST, FieldOrder.PROGRESSIVE, FieldOrder.PROGRESSIVE,
                    null, null};
            // the VUI frame period is 2 * 1001 / 60000 seconds, 3003 ticks of the 90 kHz time base
            long[] durations = {3003, 3003, 3003, 4504, 4504, 6006, 9009, 3003, 3003};
            for (var i = 0; i < frames.size(); i++) {
                var sideData = frames.get(i).sideData();
                assertEquals(Optional.ofNullable(orders[i]), sideData.fieldOrder(), "pic_struct " + picStructs[i]);
                assertEquals(Integer.parseInt(probe.get(i)), sideData.repeatedFields(), "pic_struct " + picStructs[i]);
                assertEquals(durations[i], frames.get(i).duration(), "pic_struct " + picStructs[i]);
            }
        }

        @Test
        @DisplayName("a packet duration is kept whatever pic_struct says")
        void packetDuration() {
            var stream = stream(new int[]{7, 8}, 0);
            var track = H264TestStreams.track(Optional.empty(), Optional.of(new Rational(25, 1)));
            var frames = new ArrayList<VideoFrame>();
            java.util.function.Consumer<VideoFrame> sink = frames::add;
            try (var decoder = new H264Decoder(track)) {
                var payloads = new ArrayList<byte[]>();
                for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(stream))) {
                    payloads.add(H264TestStreams.annexB(unit));
                }
                for (var i = 0; i < payloads.size(); i++) {
                    decoder.decode(new Packet(1, payloads.get(i), i * 5000L, i * 5000L, 1234, true), sink);
                }
                decoder.flush(sink);
            }
            assertEquals(2, frames.size());
            for (var frame : frames) {
                assertEquals(1234, frame.duration());
            }
            assertEquals(2, frames.get(0).sideData().repeatedFields());
        }
    }

    @Test
    @DisplayName("the SEI messages of every frame packing and display orientation stream decode the same frames")
    void samplesUnchanged() {
        var clean = Fixtures.read(x264("96x64", 4, List.of("-bf", "1")));
        var annotated = SeiWriter.insert(clean, false, false, picture -> List.of(
                SeiWriter.nal(false, 45, SeiWriter.framePacking(false, 1, 4, false, 1, false, false, false, false,
                        true, true, new int[]{0, 0, 0, 0}, 1, false)),
                SeiWriter.nal(false, 47, SeiWriter.displayOrientation(false, false, true, 32768, 0))));
        var plain = new ArrayList<byte[]>();
        decode(clean, H264DecoderOptions.defaults(), plain);
        var samples = new ArrayList<byte[]>();
        var frames = decode(annotated, H264DecoderOptions.defaults(), samples);
        for (var i = 0; i < samples.size(); i++) {
            assertArrayEquals(plain.get(i), samples.get(i));
            assertEquals(Orientation.FLIP_HORIZONTAL, frames.get(i).sideData().orientation().orElseThrow());
            assertEquals(FramePacking.Arrangement.TOP_BOTTOM,
                    frames.get(i).sideData().framePacking().orElseThrow().arrangement());
        }
    }
}
