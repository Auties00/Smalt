package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the DTS decoder with ffmpeg's, used as a black box, on the DTS-HD Master Audio samples of
 * samples.ffmpeg.org, which the full tier of {@code tools/testdata/vectors.tsv} lists: real Blu-ray streams of 2.0 to
 * 7.1 channels at 16 and 24 bits and 96 kHz, and one in an M2TS file, sample for sample over every frame. The samples
 * are cut at arbitrary bytes, so the last access unit, which the decoders may drop or output from its core alone, is
 * not compared.
 */
final class DtsMasterAudioCorpusTest {
    /**
     * The number of access units of the comparison of the first frame after a repeated first unit.
     */
    private static final int PRIMED_UNITS = 8;

    @ParameterizedTest(name = "{0}")
    @CsvSource({"dts-ma-2.0-16bit.dts, 48000, false", "dts-ma-5.0-96khz.dts, 96000, false",
            "dts-ma-5.1-16bit.dts, 48000, false", "dts-ma-5.1-24bit.dts, 48000, false",
            "dts-ma-6.1-24bit.dts, 48000, false", "dts-ma-7.1.dts, 48000, false",
            "dts-ma-7.1-24bit.dts, 48000, true", "dts-ma-bond.m2ts, 48000, false"})
    @DisplayName("Master Audio samples match ffmpeg's lossless output sample for sample, every frame")
    void lossless(String name, int rate, boolean firstFrameIsCore) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Conformance.fetchListed(name);
        var reference = Scratch.directory("dts-ma").resolve(name + "-" + Thread.currentThread().threadId() + ".s32");
        var arguments = new ArrayList<>(List.of("-cpuflags", "0", "-v", "error", "-y", "-i", file.toString()));
        if (name.endsWith(".m2ts")) {
            arguments.addAll(List.of("-map", "0:a:0"));
        }
        arguments.addAll(List.of("-f", "s32le", reference.toString()));
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), arguments);
        try (var expected = new DataInputStream(new BufferedInputStream(Files.newInputStream(reference), 1 << 16))) {
            var comparison = new Comparison(name, expected, rate, firstFrameIsCore);
            decode(file, comparison::accept);
            comparison.finish();
        } finally {
            Files.deleteIfExists(reference);
        }
        if (firstFrameIsCore) {
            assertFirstFrame(name, file);
        }
    }

    /**
     * Asserts that the first frame of a stream whose first frame ffmpeg outputs from the fixed-point core alone
     * decodes as ffmpeg decodes it after a frame with the extension, in the stream of the first units with the first
     * one repeated.
     */
    private static void assertFirstFrame(String name, Path file) {
        var units = DtsTestSupport.units(file).subList(0, PRIMED_UNITS);
        var primed = new ArrayList<byte[]>();
        primed.add(units.getFirst());
        primed.addAll(units);
        var reference = DtsTestSupport.ffmpegIntegers(DtsTestSupport.write("dts-ma-first", primed));
        var actual = DtsTestSupport.toInt32(DtsTestSupport.decodeUnits(primed, DtsDecoderOptions.defaults()).samples());
        assertEquals(reference.length, actual.length, name + " repeated sample count");
        var frame = actual.length / primed.size();
        Tolerance.exact().assertWithin(name + " with its first unit repeated",
                Arrays.copyOfRange(reference, frame, reference.length), Arrays.copyOfRange(actual, frame,
                        actual.length));
    }

    /**
     * Decodes the DTS track of a file, passing each buffer on.
     */
    private static void decode(Path file, java.util.function.Consumer<AudioBuffer> output) throws IOException {
        try (var source = MediaSource.of(file);
             var demuxer = file.toString().endsWith(".m2ts") ? new TsDemuxer(source) : new DtsDemuxer(source)) {
            var track = demuxer.tracks().stream().filter(candidate -> candidate instanceof AudioTrack audio
                    && audio.codec() == Codec.DTS).map(AudioTrack.class::cast).findFirst().orElseThrow();
            try (var decoder = new DtsDecoder(track)) {
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    if (packet.trackId() == track.id()) {
                        decoder.decode(packet, output);
                    }
                }
            }
        }
    }

    /**
     * Compares decoded buffers with ffmpeg's interleaved 32-bit output as it is read, one buffer behind, so that the
     * last buffer is left out.
     */
    private static final class Comparison {
        private final String name;
        private final InputStream expected;
        private final int rate;
        private final boolean skipFirst;
        private int[] pending = new int[0];
        private int pendingLength;
        private int pendingChannels;
        private int buffers;
        private long compared;

        Comparison(String name, InputStream expected, int rate, boolean skipFirst) {
            this.name = name;
            this.expected = expected;
            this.rate = rate;
            this.skipFirst = skipFirst;
        }

        void accept(AudioBuffer buffer) {
            assertEquals(rate, buffer.sampleRate(), name + " rate of buffer " + buffers);
            if (buffers > 0) {
                compare();
            }
            var length = buffer.frames() * buffer.channels();
            if (pending.length < length) {
                pending = new int[length];
            }
            var samples = DtsTestSupport.interleave(List.of(buffer)).samples();
            System.arraycopy(DtsTestSupport.toInt32(samples), 0, pending, 0, length);
            pendingLength = length;
            pendingChannels = buffer.channels();
            buffers++;
        }

        private void compare() {
            var reference = new int[pendingLength];
            try {
                for (var i = 0; i < pendingLength; i++) {
                    reference[i] = Integer.reverseBytes(((DataInputStream) expected).readInt());
                }
            } catch (EOFException exception) {
                throw new AssertionError(name + ": ffmpeg's output ends before buffer " + (buffers - 1), exception);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            if (buffers == 1 && skipFirst) {
                return;
            }
            Tolerance.exact().assertWithin(name + " buffer " + (buffers - 1) + " (" + pendingChannels + " channels)",
                    reference, Arrays.copyOf(pending, pendingLength));
            compared += pendingLength;
        }

        void finish() throws IOException {
            assertTrue(buffers > PRIMED_UNITS, name + " decodes " + buffers + " buffers");
            assertTrue(compared > 0, name + " compares samples");
            // what ffmpeg outputs beyond the compared buffers is at most the last unit, which may be cut
            var rest = expected.readAllBytes().length / 4;
            assertTrue(rest <= 2L * pendingLength + 8 * 4096, name + ": " + rest + " samples of ffmpeg's output left");
        }
    }
}
