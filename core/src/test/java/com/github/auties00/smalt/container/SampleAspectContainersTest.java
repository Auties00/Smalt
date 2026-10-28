package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sample aspect ratio and the scan of video tracks through the MP4, Matroska and AVI
 * demuxers and muxers: ffmpeg's files (made with {@code -aspect} and {@code -field_order}, read by
 * ffprobe as the oracle), Smalt's own files read back by Smalt and by ffprobe, remux chains between
 * the three containers, and files edited by hand.
 */
@DisplayName("Sample aspect ratio and scan in containers")
class SampleAspectContainersTest {
    /**
     * A short H.264 clip; x264 signals square samples in its VUI, so ffmpeg's MP4 holds a 1:1
     * {@code pasp}.
     */
    static Path base() {
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.4"),
                List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
    }

    /**
     * A copy of the base clip in another container with ffmpeg's stream options.
     */
    static Path remuxed(String extension, String... options) {
        var output = new ArrayList<>(List.of("-c", "copy"));
        output.addAll(List.of(options));
        return Fixtures.ffmpeg(extension, List.of("-i", base().toString()), output);
    }

    record Demuxed(VideoTrack video, List<Packet> packets) {
    }

    static Demuxed demux(byte[] bytes) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes))) {
            var video = demuxer.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast)
                    .findFirst().orElseThrow();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == video.id()) {
                    packets.add(packet);
                }
            }
            return new Demuxed(video, packets);
        }
    }

    static Demuxed demux(Path file) {
        return demux(Fixtures.read(file));
    }

    /**
     * The base clip with a track that declares neither a sample aspect ratio nor a scan.
     */
    static Demuxed plain() {
        var base = demux(base());
        return new Demuxed(base.video().toBuilder().noSampleAspectRatio().noInterlacing().build(), base.packets());
    }

    /**
     * Returns the bytes of a Matroska file before its first {@code Cluster}, where the track entries are.
     */
    static byte[] header(byte[] matroska) {
        var cluster = indexOf(matroska, new byte[]{0x1F, 0x43, (byte) 0xB6, 0x75});
        return java.util.Arrays.copyOf(matroska, cluster > 0 ? cluster : matroska.length);
    }

    enum Target {
        MP4(sink -> new Mp4Muxer(sink), "mp4"),
        FRAGMENTED_MP4(sink -> new FragmentedMp4Muxer(sink), "mp4"),
        MATROSKA(sink -> new MatroskaMuxer(sink), "mkv"),
        AVI(sink -> new AviMuxer(sink), "avi");

        final Function<MediaSink, Muxer> factory;
        final String extension;

        Target(Function<MediaSink, Muxer> factory, String extension) {
            this.factory = factory;
            this.extension = extension;
        }
    }

    static byte[] mux(Target target, VideoTrack track, List<Packet> packets) {
        var sink = MediaSink.memory();
        try (var muxer = target.factory.apply(sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet.withTrackId(track.id()));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Returns ffprobe's {@code sample_aspect_ratio} and {@code field_order} of the first video stream.
     */
    static Map<String, String> probe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var text = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "stream=sample_aspect_ratio,field_order", "-of",
                "default=noprint_wrappers=1", file.toString())), StandardCharsets.UTF_8);
        var fields = new HashMap<String, String>();
        for (var line : text.split("\\R")) {
            var equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return fields;
    }

    static Path write(Path directory, String name, byte[] bytes) {
        try {
            return Files.write(directory.resolve(name), bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String ffprobeRatio(Rational ratio) {
        return ratio.numerator() + ":" + ratio.denominator();
    }

    static String ffprobeOrder(Interlacing interlacing) {
        return switch (interlacing) {
            case PROGRESSIVE -> "progressive";
            case TOP_FIELD_FIRST -> "tb";
            case BOTTOM_FIELD_FIRST -> "bt";
            case TOP_FIELD_FIRST_SEPARATED -> "tt";
            case BOTTOM_FIELD_FIRST_SEPARATED -> "bb";
            default -> null;
        };
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var index = 0; index + pattern.length <= data.length; index++) {
            for (var offset = 0; offset < pattern.length; offset++) {
                if (data[index + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    @Nested
    @DisplayName("ffmpeg's files")
    class FfmpegFiles {
        @ParameterizedTest(name = "{0} -aspect {1}")
        @CsvSource({"mp4, 16:9, 16/11", "mov, 16:9, 16/11", "mkv, 16:9, 16/11", "mkv, 4:3, 12/11", "avi, 16:9, 16/11",
                "avi, 4:3, 12/11", "mp4, 2.35, -", "mkv, 2.35, -", "avi, 2.35, -"})
        @DisplayName("reads the sample aspect ratio ffprobe reports")
        void aspect(String extension, String aspect, String expected) {
            var file = remuxed(extension, "-aspect", aspect);
            var video = demux(file).video();
            var ratio = video.sampleAspectRatio().orElseThrow();
            if (!expected.equals("-")) {
                var parts = expected.split("/");
                assertEquals(new Rational(Long.parseLong(parts[0]), Long.parseLong(parts[1])), ratio,
                        extension + " " + aspect);
            }
            assertEquals(ffprobeRatio(ratio), probe(file).get("sample_aspect_ratio"));
        }

        @Test
        @DisplayName("reads the square samples ffmpeg declares in MP4 and AVI, and none from Matroska without a display size")
        void square() {
            assertEquals(Optional.of(new Rational(1, 1)), demux(base()).video().sampleAspectRatio());
            assertEquals(Optional.of(new Rational(1, 1)), demux(remuxed("avi")).video().sampleAspectRatio());
            assertEquals(Optional.empty(), demux(remuxed("mkv")).video().sampleAspectRatio());
        }

        @ParameterizedTest(name = "mkv -field_order {0}")
        @CsvSource({"tt, TOP_FIELD_FIRST_SEPARATED", "bb, BOTTOM_FIELD_FIRST_SEPARATED", "tb, TOP_FIELD_FIRST",
                "bt, BOTTOM_FIELD_FIRST", "progressive, PROGRESSIVE"})
        @DisplayName("reads the Matroska field order ffprobe reports")
        void matroskaFieldOrder(String order, Interlacing expected) {
            var file = remuxed("mkv", "-field_order", order);
            assertEquals(Optional.of(expected), demux(file).video().interlacing());
            assertEquals(order, probe(file).get("field_order"));
        }

        @Test
        @DisplayName("reads the field count of the AVI video properties header")
        void aviFields() {
            assertEquals(Optional.of(Interlacing.INTERLACED), demux(remuxed("avi", "-field_order", "tb")).video()
                    .interlacing());
            assertEquals(Optional.of(Interlacing.PROGRESSIVE), demux(remuxed("avi")).video().interlacing());
            // ffmpeg's Matroska muxer always writes FlagInterlaced, its MP4 muxer writes no fiel for H.264
            assertEquals(Optional.of(Interlacing.PROGRESSIVE), demux(remuxed("mkv")).video().interlacing());
            assertEquals(Optional.empty(), demux(base()).video().interlacing());
        }

        @ParameterizedTest(name = "ProRes top {0}")
        @CsvSource({"1, tb, TOP_FIELD_FIRST", "0, bt, BOTTOM_FIELD_FIRST"})
        @DisplayName("reads the QuickTime fiel box of interlaced ProRes")
        void quickTimeFields(int top, String order, Interlacing expected) {
            var file = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                    List.of("-c:v", "prores_ks", "-flags", "+ildct", "-top", Integer.toString(top), "-field_order", order));
            var video = demux(file).video();
            assertEquals(Optional.of(expected), video.interlacing());
            assertEquals(Optional.of(new Rational(1, 1)), video.sampleAspectRatio());
            assertEquals(order, probe(file).get("field_order"));
        }

        @ParameterizedTest(name = "Theora -aspect {0}")
        @CsvSource({"16:9, 16/11", "4:3, 12/11", "11:9, -"})
        @DisplayName("reads the pixel aspect ratio of an Ogg Theora identification header")
        void theora(String aspect, String expected) {
            var file = Fixtures.ffmpeg("ogv", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                    List.of("-c:v", "libtheora", "-aspect", aspect));
            var ratio = demux(file).video().sampleAspectRatio();
            if (expected.equals("-")) {
                assertEquals(Optional.of(new Rational(1, 1)), ratio);
            } else {
                var parts = expected.split("/");
                assertEquals(Optional.of(new Rational(Long.parseLong(parts[0]), Long.parseLong(parts[1]))), ratio);
                assertEquals(ffprobeRatio(ratio.orElseThrow()), probe(file).get("sample_aspect_ratio"));
            }
        }

        @ParameterizedTest(name = "setsar {0}")
        @CsvSource({"10/11, 10, 11", "64/45, 64, 45", "1/1, 1, 1"})
        @DisplayName("reads the ratio the H.264 sequence parameter set signals in MPEG-TS")
        void transportStream(String sar, long numerator, long denominator) {
            var file = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.4"),
                    List.of("-vf", "setsar=" + sar, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
            var ratio = demux(file).video().sampleAspectRatio();
            assertEquals(Optional.of(new Rational(numerator, denominator)), ratio);
            assertEquals(ffprobeRatio(ratio.orElseThrow()), probe(file).get("sample_aspect_ratio"));
        }

        @Test
        @DisplayName("reads the pasp of an uncompressed QuickTime movie")
        void quickTimeAspect() {
            var file = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                    List.of("-c:v", "rawvideo", "-pix_fmt", "uyvy422", "-aspect", "4:3"));
            var video = demux(file).video();
            assertEquals(Optional.of(new Rational(12, 11)), video.sampleAspectRatio());
            assertEquals(Optional.of(Interlacing.PROGRESSIVE), video.interlacing());
        }
    }

    static Stream<Arguments> declarations() {
        var ratios = List.of(new Rational(16, 11), new Rational(40, 33), new Rational(8, 9), new Rational(1, 1),
                new Rational(64, 45), new Rational(4095, 4096));
        var arguments = new ArrayList<Arguments>();
        for (var target : Target.values()) {
            for (var ratio : ratios) {
                arguments.add(Arguments.of(target, ratio, null));
            }
            for (var scan : Interlacing.values()) {
                arguments.add(Arguments.of(target, null, scan));
            }
            arguments.add(Arguments.of(target, new Rational(10, 11), Interlacing.BOTTOM_FIELD_FIRST));
        }
        return arguments.stream();
    }

    @Nested
    @DisplayName("Smalt's files")
    class SmaltFiles {
        @ParameterizedTest(name = "{0} sar {1} scan {2}")
        @MethodSource("com.github.auties00.smalt.container.SampleAspectContainersTest#declarations")
        @DisplayName("write what the track declares, read back by Smalt and by ffprobe")
        void declared(Target target, Rational ratio, Interlacing scan, @TempDir Path directory) {
            var source = plain();
            var builder = source.video().toBuilder();
            if (ratio != null) {
                builder.sampleAspectRatio(ratio);
            }
            if (scan != null) {
                builder.interlacing(scan);
            }
            var bytes = mux(target, builder.build(), source.packets());
            var read = demux(bytes).video();
            // AVI keeps only the field count, and writes one field per frame when only the ratio is declared
            var expectedScan = target != Target.AVI ? scan : scan == null ? Interlacing.PROGRESSIVE
                    : scan.isInterlaced() ? Interlacing.INTERLACED : Interlacing.PROGRESSIVE;
            assertEquals(Optional.ofNullable(expectedScan), read.interlacing(), "scan");
            if (ratio != null) {
                assertEquals(Optional.of(ratio), read.sampleAspectRatio(), "sample aspect ratio");
            } else {
                assertEquals(Optional.empty(), read.sampleAspectRatio());
            }
            var file = write(directory, "out." + target.extension, bytes);
            Oracle.assertDecodesCleanly(file);
            var probed = probe(file);
            if (ratio != null) {
                assertEquals(ffprobeRatio(ratio), probed.get("sample_aspect_ratio"), "ffprobe sample aspect ratio");
            }
            if (scan != null && target != Target.AVI && ffprobeOrder(scan) != null) {
                assertEquals(ffprobeOrder(scan), probed.get("field_order"), "ffprobe field order");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Target.class)
        @DisplayName("write no aspect or field metadata for a track that declares none")
        void undeclared(Target target) {
            var source = plain();
            var bytes = mux(target, source.video(), source.packets());
            var read = demux(bytes).video();
            assertEquals(Optional.empty(), read.sampleAspectRatio());
            assertEquals(Optional.empty(), read.interlacing());
            switch (target) {
                case MP4, FRAGMENTED_MP4 -> {
                    assertEquals(-1, indexOf(bytes, "pasp".getBytes(StandardCharsets.US_ASCII)));
                    assertEquals(-1, indexOf(bytes, "fiel".getBytes(StandardCharsets.US_ASCII)));
                }
                case AVI -> assertEquals(-1, indexOf(bytes, "vprp".getBytes(StandardCharsets.US_ASCII)));
                case MATROSKA -> {
                    var header = header(bytes);
                    assertEquals(-1, indexOf(header, new byte[]{0x54, (byte) 0xB0}));
                    assertEquals(-1, indexOf(header, new byte[]{(byte) 0x9A, (byte) 0x81}));
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Target.class)
        @DisplayName("write a ratio declared on a track of unknown size, or reject the size as before")
        void unknownSize(Target target) {
            var source = plain();
            var track = source.video().toBuilder().width(0).height(0).sampleAspectRatio(new Rational(16, 11)).build();
            if (target == Target.MATROSKA) {
                // Matroska needs PixelWidth and PixelHeight whatever the ratio
                assertThrows(IllegalArgumentException.class, () -> mux(target, track, source.packets()));
                return;
            }
            var read = demux(mux(target, track, source.packets())).video();
            // AVI derives the ratio from the frame size, so it has none to store for an unknown size
            assertEquals(target == Target.AVI ? Optional.empty() : Optional.of(new Rational(16, 11)),
                    read.sampleAspectRatio());
        }

        @Test
        @DisplayName("store an inexact Matroska display width as a display aspect ratio and an exact one in pixels")
        void matroskaUnits() {
            var source = plain();
            var exact = header(mux(Target.MATROSKA, source.video().toBuilder().sampleAspectRatio(new Rational(12, 11))
                    .build(), source.packets()));
            var inexact = header(mux(Target.MATROSKA, source.video().toBuilder().sampleAspectRatio(new Rational(40, 33))
                    .build(), source.packets()));
            // DisplayWidth 192 (0x54B0, size 1, 0xC0) and no DisplayUnit for 176 * 12 / 11
            assertTrue(indexOf(exact, new byte[]{0x54, (byte) 0xB0, (byte) 0x81, (byte) 0xC0}) >= 0);
            assertEquals(-1, indexOf(exact, new byte[]{0x54, (byte) 0xB2}));
            // 176 * 40 / 33 is not whole: the display aspect ratio 40:27 with DisplayUnit 3
            assertTrue(indexOf(inexact, new byte[]{0x54, (byte) 0xB0, (byte) 0x81, 40, 0x54, (byte) 0xBA, (byte) 0x81, 27,
                    0x54, (byte) 0xB2, (byte) 0x81, 3}) >= 0);
        }

        @ParameterizedTest(name = "ProRes sar {0} scan {1}")
        @CsvSource({"16/11, BOTTOM_FIELD_FIRST", "8/9, TOP_FIELD_FIRST_SEPARATED", "-, -"})
        @DisplayName("write the declared ratio and scan into a QuickTime ProRes entry, else square progressive frames")
        void quickTime(String sar, String scan, @TempDir Path directory) {
            var prores = demux(Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=176x144:rate=25:duration=0.2"), List.of("-c:v", "prores_ks")));
            var builder = prores.video().toBuilder().noSampleAspectRatio().noInterlacing();
            if (!sar.equals("-")) {
                var parts = sar.split("/");
                builder.sampleAspectRatio(new Rational(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
            }
            if (!scan.equals("-")) {
                builder.interlacing(Interlacing.valueOf(scan));
            }
            var track = builder.build();
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink, com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.quickTime())) {
                muxer.addTrack(track);
                for (var packet : prores.packets()) {
                    muxer.writePacket(packet.withTrackId(track.id()));
                }
                muxer.finish();
            }
            var bytes = sink.toByteArray();
            var read = demux(bytes).video();
            var expectedRatio = track.sampleAspectRatio().orElse(new Rational(1, 1));
            var expectedScan = track.interlacing().orElse(Interlacing.PROGRESSIVE);
            assertEquals(Optional.of(expectedRatio), read.sampleAspectRatio());
            assertEquals(Optional.of(expectedScan), read.interlacing());
            var probed = probe(write(directory, "out.mov", bytes));
            assertEquals(ffprobeRatio(expectedRatio), probed.get("sample_aspect_ratio"));
            assertEquals(ffprobeOrder(expectedScan), probed.get("field_order"));
        }

        @Test
        @DisplayName("approximate a frame aspect ratio that does not fit 16-bit AVI terms")
        void aviApproximation(@TempDir Path directory) {
            var source = plain();
            var ratio = new Rational(65_521, 65_519);
            var bytes = mux(Target.AVI, source.video().toBuilder().sampleAspectRatio(ratio).build(), source.packets());
            var read = demux(bytes).video().sampleAspectRatio().orElseThrow();
            assertEquals(ratio.toDouble(), read.toDouble(), 1e-6);
            var probed = probe(write(directory, "out.avi", bytes)).get("sample_aspect_ratio").split(":");
            assertEquals(read, new Rational(Long.parseLong(probed[0]), Long.parseLong(probed[1])).reduce());
        }
    }

    @Nested
    @DisplayName("remux chains")
    class Chains {
        @Test
        @DisplayName("MP4 -> Matroska -> MP4 keeps the sample aspect ratio and the field order")
        void mp4Matroska() {
            var source = plain();
            var track = source.video().toBuilder().sampleAspectRatio(new Rational(40, 33))
                    .interlacing(Interlacing.BOTTOM_FIELD_FIRST_SEPARATED).build();
            var first = demux(mux(Target.MP4, track, source.packets()));
            var second = demux(mux(Target.MATROSKA, first.video(), first.packets()));
            var third = demux(mux(Target.MP4, second.video(), second.packets()));
            for (var step : List.of(first, second, third)) {
                assertEquals(track.sampleAspectRatio(), step.video().sampleAspectRatio());
                assertEquals(track.interlacing(), step.video().interlacing());
            }
        }

        @Test
        @DisplayName("Matroska -> AVI -> Matroska -> MP4 keeps the sample aspect ratio and that the frames are interlaced")
        void throughAvi() {
            // VP9 describes itself, so the AVI copy needs no configuration record to go on
            var encoded = Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.4"),
                    List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8"));
            var vp9 = Fixtures.ffmpeg("mkv", List.of("-i", encoded.toString()), List.of("-c", "copy", "-aspect", "16:9",
                    "-field_order", "bt"));
            var mkv = demux(vp9);
            assertEquals(Optional.of(Interlacing.BOTTOM_FIELD_FIRST), mkv.video().interlacing());
            var avi = demux(mux(Target.AVI, mkv.video(), mkv.packets()));
            var again = demux(mux(Target.MATROSKA, avi.video(), avi.packets()));
            var mp4 = demux(mux(Target.MP4, again.video(), again.packets()));
            for (var step : List.of(mkv, avi, again, mp4)) {
                assertEquals(Optional.of(new Rational(16, 11)), step.video().sampleAspectRatio());
            }
            for (var step : List.of(avi, again, mp4)) {
                assertEquals(Optional.of(Interlacing.INTERLACED), step.video().interlacing());
            }
        }
    }

    @Nested
    @DisplayName("edited files")
    class Edited {
        static byte[] patched(Path file, String box, int offset, byte... values) {
            var bytes = Fixtures.read(file).clone();
            var at = indexOf(bytes, box.getBytes(StandardCharsets.US_ASCII));
            assertTrue(at > 0, box);
            System.arraycopy(values, 0, bytes, at + offset, values.length);
            return bytes;
        }

        @Test
        @DisplayName("ignores a pasp box with a zero spacing or too short, and a fiel box with another field count")
        void mp4() {
            var aspect = remuxed("mp4", "-aspect", "16:9");
            assertEquals(Optional.empty(), demux(patched(aspect, "pasp", 4, new byte[4])).video().sampleAspectRatio());
            assertEquals(Optional.empty(), demux(patched(aspect, "pasp", 8, new byte[4])).video().sampleAspectRatio());
            assertEquals(Optional.of(new Rational(7, 3)), demux(patched(aspect, "pasp", 4, new byte[]{0, 0, 0, 7, 0, 0, 0, 3}))
                    .video().sampleAspectRatio());
            // shrinks the 16-byte pasp box to a 12-byte one whose spacings are cut short
            assertEquals(Optional.empty(), demux(patched(aspect, "pasp", -1, (byte) 12)).video().sampleAspectRatio());
            var fields = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                    List.of("-c:v", "prores_ks", "-flags", "+ildct", "-top", "1", "-field_order", "tb"));
            assertEquals(Optional.empty(), demux(patched(fields, "fiel", 4, (byte) 3)).video().interlacing());
            assertEquals(Optional.of(Interlacing.INTERLACED), demux(patched(fields, "fiel", 4, (byte) 2, (byte) 5)).video()
                    .interlacing());
            assertEquals(Optional.of(Interlacing.INTERLACED), demux(patched(fields, "fiel", 4, (byte) 2, (byte) 0)).video()
                    .interlacing());
            assertEquals(Optional.of(Interlacing.TOP_FIELD_FIRST_SEPARATED), demux(patched(fields, "fiel", 4, (byte) 2,
                    (byte) 1)).video().interlacing());
        }

        @Test
        @DisplayName("ignores a vprp with a zero aspect term or an unknown field count")
        void avi() {
            var file = remuxed("avi", "-aspect", "16:9");
            // dwFrameAspectRatio is at offset 20 of the body, nbFieldPerFrame at 32
            assertEquals(Optional.empty(), demux(patched(file, "vprp", 8 + 20, new byte[]{9, 0, 0, 0})).video()
                    .sampleAspectRatio());
            assertEquals(Optional.empty(), demux(patched(file, "vprp", 8 + 32, (byte) 3)).video().interlacing());
            // a frame aspect ratio of 44:27 over 176x144 is a sample aspect ratio of 4/3
            assertEquals(Optional.of(new Rational(4, 3)), demux(patched(file, "vprp", 8 + 20, new byte[]{27, 0, 44, 0}))
                    .video().sampleAspectRatio());
        }

        @Test
        @DisplayName("survive the ffprobe-checked files truncated at every 97th byte")
        void truncated() {
            for (var file : List.of(remuxed("mp4", "-aspect", "16:9"), remuxed("mkv", "-aspect", "16:9", "-field_order", "tt"),
                    remuxed("avi", "-aspect", "16:9"))) {
                var bytes = Fixtures.read(file);
                for (var length = 0; length < Math.min(bytes.length, 6000); length += 97) {
                    var cut = java.util.Arrays.copyOf(bytes, length);
                    try (var demuxer = Containers.openDemuxer(MediaSource.of(cut))) {
                        for (var track : demuxer.tracks()) {
                            if (track instanceof VideoTrack video) {
                                video.sampleAspectRatio().ifPresent(ratio -> assertTrue(ratio.numerator() > 0));
                            }
                        }
                    } catch (MediaException expected) {
                        // a truncated file may be rejected, but only with a MediaException
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("probe")
    class Probe {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"mkv", "mp4", "avi"})
        @DisplayName("reports the container's ratio and scan as ffprobe does")
        void probe(String extension) {
            var file = extension.equals("mkv") ? remuxed(extension, "-aspect", "16:9", "-field_order", "tb")
                    : remuxed(extension, "-aspect", "16:9");
            var video = Media.probe(file).videoStreams().getFirst();
            assertEquals(Optional.of(new Rational(16, 11)), video.sampleAspectRatio());
            assertEquals(256, video.displayWidth());
            if (extension.equals("mkv")) {
                assertEquals(Optional.of(Interlacing.TOP_FIELD_FIRST), video.interlacing());
            }
        }

        @Test
        @DisplayName("falls back to the ratio of the H.264 sequence parameter set when the container declares none")
        void bitstream(@TempDir Path directory) {
            var signaled = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                    List.of("-vf", "setsar=10/11", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
            var source = demux(signaled);
            assertEquals(Optional.of(new Rational(10, 11)), source.video().sampleAspectRatio());
            // the same stream in an MP4 without pasp: only the sequence parameter set signals the ratio
            var file = write(directory, "vui.mp4", mux(Target.MP4, source.video().toBuilder().noSampleAspectRatio().build(),
                    source.packets()));
            assertEquals(Optional.empty(), demux(file).video().sampleAspectRatio());
            assertEquals(Optional.of(new Rational(10, 11)), Media.probe(file).videoStreams().getFirst().sampleAspectRatio());
            assertEquals("10:11", SampleAspectContainersTest.probe(file).get("sample_aspect_ratio"));
        }
    }
}
