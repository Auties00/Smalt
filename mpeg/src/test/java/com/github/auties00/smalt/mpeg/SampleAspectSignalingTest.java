package com.github.auties00.smalt.mpeg;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.SampleAspectMode;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h263.H263Encoder;
import com.github.auties00.smalt.video.h263.H263EncoderOptions;
import com.github.auties00.smalt.video.mpeg4.EncoderScenes;
import com.github.auties00.smalt.video.mpeg4.Mpeg4EncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sample aspect ratio the H.264, HEVC, MPEG-4 Part 2 and H.263 encoders signal in band from
 * {@link VideoEncoderOptions#sampleAspectRatio()}: each stream is written to its elementary stream container, which
 * records no ratio, and read back by Smalt's demuxer and by ffprobe, which must both find the requested ratio.
 */
@DisplayName("Sample aspect ratio signaled in band by the MPEG encoders")
class SampleAspectSignalingTest {
    /**
     * An encoder under test with the elementary stream container it is written to.
     */
    enum Target {
        H264(Codec.H264, ContainerFormat.H264, "h264"),
        HEVC(Codec.HEVC, ContainerFormat.HEVC, "hevc"),
        MPEG4(Codec.MPEG4_PART2, ContainerFormat.MPEG4_VIDEO, "m4v"),
        H263(Codec.H263, ContainerFormat.H263, "h263");

        final Codec codec;
        final ContainerFormat format;
        final String ffmpegFormat;

        Target(Codec codec, ContainerFormat format, String ffmpegFormat) {
            this.codec = codec;
            this.format = format;
            this.ffmpegFormat = ffmpegFormat;
        }
    }

    static final int WIDTH = 176;
    static final int HEIGHT = 144;
    static final int FRAMES = 4;

    /**
     * Encodes a short clip with an optional sample aspect ratio and writes it to the target's elementary stream
     * container through a track that declares no ratio.
     */
    static byte[] encode(Target target, Rational aspect) {
        var options = VideoEncoderOptions.of(WIDTH, HEIGHT, new Rational(25, 1), 400_000).toBuilder().threads(1);
        if (aspect != null) {
            options.sampleAspectRatio(aspect);
        }
        try (var encoder = VideoEncoders.create(target.codec, options.build())) {
            return mux(target, encoder);
        }
    }

    /**
     * Encodes a short clip with an encoder and writes it to the target's elementary stream container through a track
     * that declares no ratio.
     */
    static byte[] mux(Target target, VideoEncoder encoder) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < FRAMES; i++) {
            encoder.encode(new VideoFrame(EncoderScenes.picture(WIDTH, HEIGHT, i), i, 1, false), packets::add);
        }
        encoder.flush(packets::add);
        var track = VideoTrack.builder().id(1).codec(target.codec).timeBase(encoder.timeBase())
                .codecConfig(encoder.codecConfig().orElse(null)).width(WIDTH).height(HEIGHT)
                .frameRate(new Rational(25, 1)).build();
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(target.format, sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(),
                        packet.flags(), packet.sideData()));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static Optional<Rational> declared(byte[] stream) {
        try (var source = MediaSource.of(stream); var demuxer = Containers.openDemuxer(source)) {
            return ((VideoTrack) demuxer.tracks().getFirst()).sampleAspectRatio();
        }
    }

    /**
     * Demuxes a stream and decodes it with Smalt's decoder, returning the number of frames.
     */
    static int decodedFrames(byte[] stream) {
        try (var source = MediaSource.of(stream); var demuxer = Containers.openDemuxer(source);
             var decoder = VideoDecoders.create((VideoTrack) demuxer.tracks().getFirst())) {
            var frames = new int[1];
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                decoder.decode(packet, frame -> frames[0]++);
            }
            decoder.flush(frame -> frames[0]++);
            return frames[0];
        }
    }

    /**
     * Returns a copy of a stream with the bits from {@code from} to {@code to} (exclusive) of the first occurrence of
     * a pattern, a string of {@code 0} and {@code 1}, cleared.
     */
    static byte[] clearBits(byte[] stream, String pattern, int from, int to) {
        var bits = stream.length * 8;
        for (var start = 0; start + pattern.length() <= bits; start++) {
            var match = true;
            for (var i = 0; i < pattern.length() && match; i++) {
                var bit = (stream[(start + i) >> 3] >> (7 - ((start + i) & 7))) & 1;
                match = bit == pattern.charAt(i) - '0';
            }
            if (match) {
                var copy = stream.clone();
                for (var i = start + from; i < start + to; i++) {
                    copy[i >> 3] &= (byte) ~(0x80 >> (i & 7));
                }
                return copy;
            }
        }
        throw new AssertionError("pattern not found: " + pattern);
    }

    static String binary(int value, int bits) {
        var text = Integer.toBinaryString(value);
        return "0".repeat(bits - text.length()) + text;
    }

    static Optional<Rational> declared(Path file) {
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            return demuxer.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast)
                    .findFirst().orElseThrow().sampleAspectRatio();
        }
    }

    static Path write(String name, byte[] data) {
        try {
            var file = Scratch.directory("sample-aspect-signaling").resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns ffprobe's {@code sample_aspect_ratio} of the first video stream, read with an explicit input format
     * where one is given.
     */
    static String probe(Path file, String format) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var arguments = new ArrayList<String>(List.of("-v", "error"));
        if (format != null) {
            arguments.addAll(List.of("-f", format));
        }
        arguments.addAll(List.of("-select_streams", "v:0", "-show_entries", "stream=sample_aspect_ratio", "-of",
                "default=noprint_wrappers=1:nokey=1", file.toString()));
        // A transport stream lists the stream under its program too, so the first line is the stream's.
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), arguments), StandardCharsets.UTF_8)
                .strip().lines().findFirst().orElse("").strip();
    }

    static String text(Rational ratio) {
        return ratio.numerator() + ":" + ratio.denominator();
    }

    static Stream<Arguments> ratios() {
        var cases = new ArrayList<Arguments>();
        for (var target : Target.values()) {
            cases.add(Arguments.of(target, new Rational(4, 3), new Rational(4, 3)));
            cases.add(Arguments.of(target, new Rational(40, 33), new Rational(40, 33)));
            cases.add(Arguments.of(target, new Rational(64, 45), new Rational(64, 45)));
            cases.add(Arguments.of(target, new Rational(24, 22), new Rational(12, 11)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("ratios")
    @DisplayName("signals the requested ratio, in lowest terms, to Smalt's demuxer and to ffprobe")
    void signaled(Target target, Rational requested, Rational expected) {
        var stream = encode(target, requested);
        assertEquals(Optional.of(expected), declared(stream));
        assertEquals(FRAMES, decodedFrames(stream));
        if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
            var file = write(target.name() + "-" + expected.numerator() + "x" + expected.denominator() + "."
                    + target.ffmpegFormat, stream);
            assertEquals(text(expected), probe(file, target.ffmpegFormat));
            Oracle.assertFfmpegDecodes(file);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target.class)
    @DisplayName("signals square samples on request, H.263 keeping the baseline header of its standard formats")
    void square(Target target) {
        var stream = encode(target, new Rational(1, 1));
        if (target == Target.HEVC) {
            // A square ratio leaves aspect_ratio_info_present_flag clear, which reads as unspecified.
            assertEquals(Optional.empty(), declared(stream));
        } else if (target == Target.H263) {
            assertEquals(Optional.of(new Rational(12, 11)), declared(stream));
        } else {
            assertEquals(Optional.of(new Rational(1, 1)), declared(stream));
        }
        if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
            var probed = probe(write(target.name() + "-square.es", stream), target.ffmpegFormat);
            if (target == Target.HEVC) {
                assertTrue(probed.equals("1:1") || probed.equals("N/A") || probed.equals("0:1"), probed);
            } else if (target == Target.H263) {
                assertEquals("12:11", probed);
            } else {
                assertEquals("1:1", probed);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target.class)
    @DisplayName("writes the same bytes without a ratio as with the ratio the encoder signals by default")
    void defaultUnchanged(Target target) {
        var implied = target == Target.H263 ? new Rational(12, 11) : new Rational(1, 1);
        var plain = encode(target, null);
        assertArrayEquals(plain, encode(target, implied));
        assertArrayEquals(plain, encode(target, new Rational(1, 1)));
        assertFalse(Arrays.equals(plain, encode(target, new Rational(4, 3))));
        var read = declared(plain);
        if (target == Target.HEVC) {
            assertEquals(Optional.empty(), read);
        } else {
            assertEquals(Optional.of(implied), read);
        }
    }

    @Test
    @DisplayName("keeps the baseline picture header of H.263 for the 12:11 its standard formats imply")
    void h263Baseline() {
        assertFalse(extendedType(WIDTH, HEIGHT, H263EncoderOptions.builder().sampleAspectRatio(new Rational(12, 11))
                .build()));
        assertFalse(extendedType(WIDTH, HEIGHT, H263EncoderOptions.defaults()));
        assertTrue(extendedType(WIDTH, HEIGHT, H263EncoderOptions.builder().sampleAspectRatio(new Rational(1, 1))
                .build()));
        assertTrue(extendedType(160, 120, H263EncoderOptions.defaults()));
    }

    @Test
    @DisplayName("signals square samples of an H.263 standard format in a custom format when its options ask for them")
    void h263SquareCustomFormat() {
        var options = H263EncoderOptions.builder().frameRate(new Rational(25, 1)).sampleAspectRatio(new Rational(1, 1))
                .build();
        byte[] stream;
        try (var encoder = new H263Encoder(WIDTH, HEIGHT, options)) {
            assertTrue(encoder.extendedType());
            stream = mux(Target.H263, encoder);
        }
        assertEquals(Optional.of(new Rational(1, 1)), declared(stream));
        assertEquals(FRAMES, decodedFrames(stream));
        if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
            assertEquals("1:1", probe(write("H263-square-custom.h263", stream), "h263"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Target.class, names = {"MPEG4", "H263"})
    @DisplayName("declares no ratio for an extended pixel aspect ratio with a zero term, and still decodes")
    void zeroTerm(Target target) {
        var stream = encode(target, new Rational(64, 45));
        var epar = binary(64, 8) + binary(45, 8);
        // MPEG-4: aspect_ratio_info 15, par_width, par_height. H.263: CPFMT (PAR 15, PWI, a one bit, PHI), EPAR.
        var pattern = target == Target.MPEG4 ? "1111" + epar
                : "1111" + binary(WIDTH / 4 - 1, 9) + "1" + binary(HEIGHT / 4, 9) + epar;
        var head = pattern.length() - 16;
        var zeroWidth = clearBits(stream, pattern, head, head + 8);
        var zeroHeight = clearBits(stream, pattern, head + 8, head + 16);
        assertEquals(Optional.of(new Rational(64, 45)), declared(stream));
        assertEquals(Optional.empty(), declared(zeroWidth));
        assertEquals(Optional.empty(), declared(zeroHeight));
        assertEquals(FRAMES, decodedFrames(zeroWidth));
        assertEquals(FRAMES, decodedFrames(zeroHeight));
    }

    static boolean extendedType(int width, int height, H263EncoderOptions options) {
        try (var encoder = new H263Encoder(width, height, options)) {
            return encoder.extendedType();
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("oversized")
    @DisplayName("signals the closest ratio whose terms fit the field when the requested terms are too large")
    void oversized(Target target, Rational requested) {
        var max = target == Target.MPEG4 || target == Target.H263 ? 255 : 65535;
        var read = declared(encode(target, requested))
                .orElseThrow(() -> new AssertionError(target + " " + requested + " declares no ratio"));
        assertTrue(read.numerator() <= max && read.denominator() <= max, read.toString());
        var exact = (double) requested.numerator() / requested.denominator();
        var error = Math.abs((double) read.numerator() / read.denominator() - exact);
        // No fraction with terms up to max is closer than the one signaled.
        for (long q = 1; q <= Math.min(max, 4096); q++) {
            var p = Math.max(1, Math.min(max, Math.round(exact * q)));
            assertTrue(error <= Math.abs((double) p / q - exact) + 1e-15, p + "/" + q + " beats " + read);
        }
        if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
            var file = write(target.name() + "-oversized.es", encode(target, requested));
            assertEquals(text(read), probe(file, target.ffmpegFormat));
        }
    }

    static Stream<Arguments> oversized() {
        return Stream.of(Arguments.of(Target.MPEG4, new Rational(1000, 999)),
                Arguments.of(Target.H263, new Rational(355, 113)),
                Arguments.of(Target.MPEG4, new Rational(100_000, 1)),
                Arguments.of(Target.H264, new Rational(1_000_003, 700_001)),
                Arguments.of(Target.HEVC, new Rational(1_000_003, 700_001)));
    }

    @Nested
    @DisplayName("through a remux and a transcode")
    class Remux {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Target.class, names = {"H264", "HEVC", "MPEG4"})
        @DisplayName("keeps the ratio when an MP4 with a pasp box is copied into a container without one")
        void copy(Target target) {
            var aspect = new Rational(40, 33);
            var mp4 = Scratch.directory("sample-aspect-signaling").resolve(target.name() + "-pasp.mp4");
            try (var source = MediaSource.of(encode(target, aspect)); var sink = MediaSink.of(mp4)) {
                Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                        .video(VideoTranscodeOptions.copy()).build());
            }
            assertEquals(Optional.of(aspect), declared(mp4));
            var ts = Scratch.directory("sample-aspect-signaling").resolve(target.name() + "-copy.ts");
            try (var source = MediaSource.of(mp4); var sink = MediaSink.of(ts)) {
                Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MPEG_TS).toBuilder()
                        .video(VideoTranscodeOptions.copy()).build());
            }
            assertEquals(Optional.of(aspect), declared(ts));
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                assertEquals("40:33", probe(ts, null));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Target.class, names = {"H264", "HEVC", "MPEG4"})
        @DisplayName("signals the ratio a transcode keeps in a container that records none")
        void transcode(Target target) {
            var aspect = new Rational(16, 11);
            var input = Scratch.directory("sample-aspect-signaling").resolve(target.name() + "-input.mp4");
            try (var source = MediaSource.of(encode(Target.H264, aspect)); var sink = MediaSink.of(input)) {
                Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                        .video(VideoTranscodeOptions.copy()).build());
            }
            var output = Scratch.directory("sample-aspect-signaling").resolve(target.name() + "-encoded.ts");
            try (var source = MediaSource.of(input); var sink = MediaSink.of(output)) {
                Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MPEG_TS).toBuilder()
                        .video(VideoTranscodeOptions.encode(target.codec).toBuilder()
                                .sampleAspect(SampleAspectMode.PRESERVE).build())
                        .build());
            }
            assertEquals(Optional.of(aspect), declared(output));
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                assertEquals("16:11", probe(output, null));
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("MPEG-4 Part 2 keeps the ratio in lowest terms and rejects terms past 8 bits")
        void mpeg4() {
            assertEquals(new Rational(1, 1), Mpeg4EncoderOptions.defaults().sampleAspectRatio());
            var options = Mpeg4EncoderOptions.builder().sampleAspectRatio(new Rational(512, 256)).build();
            assertEquals(new Rational(2, 1), options.sampleAspectRatio());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(Mpeg4EncoderOptions.defaults(), options);
            assertTrue(options.toString().contains("sampleAspectRatio=2/1"), options.toString());
            assertThrows(IllegalArgumentException.class,
                    () -> Mpeg4EncoderOptions.builder().sampleAspectRatio(new Rational(256, 1)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> Mpeg4EncoderOptions.builder().sampleAspectRatio(new Rational(1, 257)).build());
            assertThrows(NullPointerException.class, () -> Mpeg4EncoderOptions.builder().sampleAspectRatio(null));
        }

        @Test
        @DisplayName("H.263 keeps the ratio in lowest terms, resets it, and rejects terms past 8 bits")
        void h263() {
            assertEquals(Optional.empty(), H263EncoderOptions.defaults().sampleAspectRatio());
            var options = H263EncoderOptions.builder().sampleAspectRatio(new Rational(80, 66)).build();
            assertEquals(Optional.of(new Rational(40, 33)), options.sampleAspectRatio());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(H263EncoderOptions.defaults(), options);
            assertEquals(H263EncoderOptions.defaults(), options.toBuilder().automaticSampleAspectRatio().build());
            assertThrows(IllegalArgumentException.class,
                    () -> H263EncoderOptions.builder().sampleAspectRatio(new Rational(300, 1)).build());
            assertThrows(NullPointerException.class, () -> H263EncoderOptions.builder().sampleAspectRatio(null));
        }
    }
}
