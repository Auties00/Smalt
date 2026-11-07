package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares decoding with ffmpeg's FLAC decoder, bit for bit, over files ffmpeg encodes with every
 * compression level, sample size, channel count, stereo mode, block size, sample rate and
 * predictor option; ffmpeg outputs every sample left-justified in 32 bits.
 */
class FlacDifferentialTest {
    /** FLAC is lossless, so the decode equals ffmpeg's sample for sample. */
    private static final Tolerance TOLERANCE = Tolerance.exact();

    /** One encoder configuration. */
    record Spec(String name, int rate, int channels, int bits, List<String> options, String signal) {
        @Override
        public String toString() {
            return name;
        }

        Path fixture() {
            var output = new ArrayList<String>();
            output.addAll(List.of("-c:a", "flac"));
            switch (bits) {
                case 16 -> output.addAll(List.of("-sample_fmt", "s16"));
                case 24 -> output.addAll(List.of("-sample_fmt", "s32", "-bits_per_raw_sample", "24"));
                default -> output.addAll(List.of("-sample_fmt", "s32", "-strict", "experimental"));
            }
            output.addAll(options);
            return Fixtures.ffmpeg("flac", List.of("-f", "lavfi", "-i", source()), output);
        }

        private String source() {
            var exprs = new ArrayList<String>();
            for (var c = 0; c < channels; c++) {
                exprs.add(switch (signal) {
                    case "silence" -> "0";
                    case "square" -> "0.999*sgn(sin(2*PI*" + (100 + 37 * c) + "*t))";
                    case "coarse" -> "floor((0.6*sin(2*PI*" + (220 + 110 * c) + "*t)+0.2*random(" + c + "))*128)/128";
                    default -> "0.6*sin(2*PI*" + (220 + 110 * c) + "*t)+0.3*(random(" + c + ")-0.5)";
                });
            }
            return "aevalsrc=exprs=" + String.join("|", exprs) + ":s=" + rate + ":d=1.5";
        }
    }

    static Stream<Spec> specs() {
        var specs = new ArrayList<Spec>();
        for (var level = 0; level <= 12; level++) {
            specs.add(new Spec("level " + level, 44100, 2, 16, List.of("-compression_level", "" + level), "noise"));
        }
        for (var level : new int[]{0, 5, 8, 12}) {
            specs.add(new Spec("24-bit level " + level, 48000, 2, 24, List.of("-compression_level", "" + level), "noise"));
        }
        for (var mode = 0; mode <= 3; mode++) {
            specs.add(new Spec("16-bit ch_mode " + mode, 44100, 2, 16, List.of("-ch_mode", "" + mode), "noise"));
            specs.add(new Spec("24-bit ch_mode " + mode, 48000, 2, 24, List.of("-ch_mode", "" + mode), "noise"));
            specs.add(new Spec("32-bit ch_mode " + mode, 48000, 2, 32, List.of("-ch_mode", "" + mode), "noise"));
        }
        specs.add(new Spec("32-bit level 12", 48000, 2, 32, List.of("-compression_level", "12"), "noise"));
        specs.add(new Spec("32-bit full-scale square", 48000, 2, 32, List.of(), "square"));
        for (var channels = 1; channels <= 8; channels++) {
            specs.add(new Spec(channels + " channels", 48000, channels, 16, List.of(), "noise"));
        }
        specs.add(new Spec("6 channels 24-bit 96 kHz", 96000, 6, 24, List.of(), "noise"));
        // ffmpeg's own FLAC parser cannot demux the 16384-sample frames its encoder picks at 192 kHz
        specs.add(new Spec("8 channels 24-bit 192 kHz", 192000, 8, 24, List.of("-compression_level", "8",
                "-frame_size", "4096"), "noise"));
        for (var size : new int[]{16, 192, 576, 1152, 4608, 8192, 16384, 32768, 65535}) {
            specs.add(new Spec("frame size " + size, 44100, 2, 16, List.of("-frame_size", "" + size), "noise"));
        }
        for (var rate : new int[]{8000, 11025, 12000, 16000, 22050, 24000, 32000, 37800, 44056, 64000, 88200, 176400,
                192000, 352800}) {
            specs.add(new Spec(rate + " Hz", rate, 1, 16, List.of(), "noise"));
        }
        for (var type = 0; type <= 3; type++) {
            specs.add(new Spec("lpc_type " + type, 44100, 2, 16, List.of("-lpc_type", "" + type), "noise"));
        }
        for (var method = 0; method <= 5; method++) {
            specs.add(new Spec("prediction_order_method " + method, 44100, 1, 16,
                    List.of("-prediction_order_method", "" + method, "-max_prediction_order", "12"), "noise"));
        }
        specs.add(new Spec("order 32, precision 15", 44100, 2, 24, List.of("-lpc_type", "2", "-min_prediction_order", "32",
                "-max_prediction_order", "32", "-lpc_coeff_precision", "15"), "noise"));
        specs.add(new Spec("partition order 8", 44100, 2, 16, List.of("-min_partition_order", "8", "-max_partition_order", "8"), "noise"));
        specs.add(new Spec("exact rice parameters", 44100, 2, 16, List.of("-exact_rice_parameters", "1"), "noise"));
        specs.add(new Spec("multi-dimensional quantization", 44100, 2, 16, List.of("-multi_dim_quant", "1"), "noise"));
        specs.add(new Spec("wasted bits", 44100, 2, 16, List.of(), "coarse"));
        specs.add(new Spec("silence", 44100, 2, 16, List.of(), "silence"));
        specs.add(new Spec("full-scale square", 44100, 2, 16, List.of(), "square"));
        return specs.stream();
    }

    /** Decodes ffmpeg's view of a file as left-justified 32-bit samples. */
    static int[] ffmpeg(Path file, String... inputOptions) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(List.of(inputOptions));
        arguments.addAll(List.of("-i", file.toString(), "-f", "s32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var samples = new int[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(samples);
        return samples;
    }

    /** One run of frames with the same format, as our decoder reports it. */
    record Segment(int rate, int channels, int bits, ByteArrayOutputStream frames, IntList samples) {
    }

    /** Decodes a file with our demuxer and decoder into left-justified 32-bit segments. */
    static List<Segment> decode(Path file) {
        var segments = new ArrayList<Segment>();
        try (var source = MediaSource.of(file); var demuxer = new FlacDemuxer(source);
             var decoder = new FlacDecoder((AudioTrack) demuxer.tracks().getFirst())) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                var rate = new int[1];
                decoder.decode(packet, buffer -> rate[0] = buffer.sampleRate());
                var bits = decoder.lastBitsPerSample();
                var channels = decoder.lastChannels();
                var last = segments.isEmpty() ? null : segments.getLast();
                if (last == null || last.rate() != rate[0] || last.channels() != channels || last.bits() != bits) {
                    last = new Segment(rate[0], channels, bits, new ByteArrayOutputStream(), new IntList());
                    segments.add(last);
                }
                last.frames().writeBytes(packet.data());
                var planes = decoder.lastSamples();
                for (var i = 0; i < decoder.lastBlockSize(); i++) {
                    for (var c = 0; c < channels; c++) {
                        last.samples().add(planes[c][i] << (32 - bits));
                    }
                }
            }
        }
        return segments;
    }

    static void assertSamples(int[] expected, int[] actual, String label) {
        TOLERANCE.assertWithin(label, expected, actual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    @DisplayName("decodes ffmpeg-encoded FLAC bit-exactly")
    void matchesFfmpeg(Spec spec) {
        var file = spec.fixture();
        var segments = decode(file);
        assertEquals(1, segments.size(), "format changed mid-stream");
        var segment = segments.getFirst();
        var result = assertDoesNotThrow(() -> FlacConformanceTest.decode(file));
        var streamInfo = com.github.auties00.smalt.internal.header.FlacStreamInfo.parse(
                result.track().codecConfig().orElseThrow(), 8);
        assertTrue(streamInfo.hasMd5(), "ffmpeg writes the MD5 signature");
        assertArrayEquals(streamInfo.md5(), result.md5(), "STREAMINFO MD5");
        assertEquals(spec.rate(), segment.rate());
        assertEquals(spec.channels(), segment.channels());
        assertEquals(spec.bits(), segment.bits());
        assertSamples(ffmpeg(file), segment.samples().toArray(), spec.name());
    }

    @ParameterizedTest(name = "uncommon/{0}")
    @ValueSource(strings = {"01", "02", "03", "04", "10", "11"})
    @DisplayName("matches ffmpeg on the corpus files without an MD5, one constant-format segment at a time")
    void corpusSegments(String number, @TempDir Path directory) throws IOException {
        var vector = FlacConformanceCorpus.all().stream()
                .filter(candidate -> candidate.group().equals("uncommon") && candidate.number().equals(number))
                .findFirst().orElseThrow();
        var segments = decode(vector.fetch());
        if (number.compareTo("04") <= 0) {
            assertTrue(segments.size() > 1, "expected a mid-stream format change");
        }
        for (var index = 0; index < segments.size(); index++) {
            var segment = segments.get(index);
            var file = directory.resolve("segment-" + index + ".flac");
            Files.write(file, segment.frames().toByteArray());
            assertSamples(ffmpeg(file, "-f", "flac"), segment.samples().toArray(),
                    "segment " + index + " (" + segment.rate() + " Hz, " + segment.channels() + " ch, " + segment.bits() + " bit)");
        }
    }

    /** A growable int array. */
    static final class IntList {
        private int[] data = new int[1 << 16];
        private int size;

        void add(int value) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = value;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
