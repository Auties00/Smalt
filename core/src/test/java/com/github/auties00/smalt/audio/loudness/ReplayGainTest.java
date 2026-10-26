package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers ReplayGain 2.0: the gains of EBU Tech 3341 programs in closed form, album gating against the
 * measurement of the concatenated album, the {@code REPLAYGAIN_*} and Opus {@code R128_*} tag forms,
 * playback gain selection, tagging files through {@link Loudness#writeReplayGain(List)}, and the
 * gain {@link AudioReader} applies.
 */
@DisplayName("ReplayGain")
class ReplayGainTest {
    private static final int RATE = 48_000;

    private static LoudnessMeter meter(float[][] planes) {
        return Signals.measure(planes, RATE, ChannelLayout.STEREO);
    }

    @Nested
    @DisplayName("computation")
    class Computation {
        @ParameterizedTest(name = "{0} dBFS sine")
        @CsvSource({"-23, 5", "-33, 15", "-18, 0", "-10, -8"})
        @DisplayName("the track gain brings a stereo sine to -18 LUFS")
        void track(double level, double gain) {
            var replayGain = ReplayGain.ofTrack(meter(Signals.tones(RATE, 2, 10, level)).stats());
            assertEquals(gain, replayGain.trackGainDb(), 0.1);
            assertEquals(Math.pow(10, level / 20), replayGain.trackPeak(), 0.002);
            assertTrue(Double.isNaN(replayGain.albumGainDb()));
        }

        @Test
        @DisplayName("the album gates the blocks of every track together, as their concatenation would")
        void album() {
            var first = Signals.tones(RATE, 2, 20, -23);
            var second = Signals.tones(RATE, 2, 20, -33);
            var third = Signals.tones(RATE, 2, 10, -60);
            var scanner = new ReplayGainScanner();
            scanner.addTrack(meter(first));
            scanner.addTrack(meter(second));
            scanner.addTrack(meter(third));
            var results = scanner.results();
            assertEquals(3, results.size());
            var concatenated = meter(Signals.concat(List.of(first, second, third))).integrated();
            // energy mean of -23 and -33 LUFS; the -60 track falls under the relative gate
            var expected = 10 * Math.log10((Math.pow(10, -2.3) + Math.pow(10, -3.3)) / 2);
            assertEquals(expected, concatenated, 0.1);
            for (var result : results) {
                assertEquals(-18 - concatenated, result.albumGainDb(), 0.05);
                assertEquals(Math.pow(10, -23 / 20.0), result.albumPeak(), 0.002);
            }
            assertEquals(5, results.get(0).trackGainDb(), 0.1);
            assertEquals(15, results.get(1).trackGainDb(), 0.1);
            assertEquals(results.get(0).albumGainDb(), scanner.albumGainDb());
            assertEquals(3, scanner.trackCount());
        }

        @Test
        @DisplayName("a silent track or an empty album has no gain")
        void silence() {
            var scanner = new ReplayGainScanner();
            assertTrue(scanner.results().isEmpty());
            assertTrue(Double.isNaN(scanner.albumGainDb()));
            var silent = scanner.addTrack(meter(new float[2][RATE * 2]));
            assertTrue(Double.isNaN(silent.trackGainDb()));
            assertTrue(Double.isNaN(scanner.results().getFirst().albumGainDb()));
        }
    }

    @Nested
    @DisplayName("tags")
    class Tags {
        private final ReplayGain gains = new ReplayGain(-6.4821, 0.98855312, 3.004, 1.25);

        @Test
        @DisplayName("writes the ReplayGain 2.0 fields and reads them back")
        void replayGainForm() {
            var base = MediaTags.builder().set(StandardTag.TITLE, "song").set("R128_TRACK_GAIN", "12").build();
            var tags = gains.toTags(base, ReplayGain.TagFormat.REPLAYGAIN);
            assertEquals("-6.48 dB", tags.get(ReplayGain.TRACK_GAIN).orElseThrow());
            assertEquals("0.988553", tags.get(ReplayGain.TRACK_PEAK).orElseThrow());
            assertEquals("3.00 dB", tags.get(ReplayGain.ALBUM_GAIN).orElseThrow());
            assertEquals("1.250000", tags.get(ReplayGain.ALBUM_PEAK).orElseThrow());
            assertEquals("-18.00 LUFS", tags.get(ReplayGain.REFERENCE_LOUDNESS).orElseThrow());
            assertTrue(tags.get(ReplayGain.R128_TRACK_GAIN).isEmpty(), "the other form is removed");
            assertEquals("song", tags.get(StandardTag.TITLE).orElseThrow());
            var read = ReplayGain.fromTags(tags);
            assertEquals(-6.48, read.trackGainDb(), 1e-12);
            assertEquals(0.988553, read.trackPeak(), 1e-12);
            assertEquals(3.0, read.albumGainDb(), 1e-12);
            assertEquals(1.25, read.albumPeak(), 1e-12);
        }

        @Test
        @DisplayName("writes the Opus R128 fields as Q7.8 integers relative to -23 LUFS")
        void r128Form() {
            var tags = gains.toTags(MediaTags.builder().set(ReplayGain.TRACK_GAIN, "1 dB").build(),
                    ReplayGain.TagFormat.R128);
            // -6.4821 dB at -18 LUFS is -11.4821 dB at -23 LUFS: -2939.4 in 1/256 dB
            assertEquals("-2939", tags.get(ReplayGain.R128_TRACK_GAIN).orElseThrow());
            assertEquals(Long.toString(Math.round((3.004 - 5) * 256)), tags.get(ReplayGain.R128_ALBUM_GAIN).orElseThrow());
            assertTrue(tags.get(ReplayGain.TRACK_GAIN).isEmpty());
            assertTrue(tags.get(ReplayGain.TRACK_PEAK).isEmpty());
            var read = ReplayGain.fromTags(tags);
            assertEquals(-2939 / 256.0 + 5, read.trackGainDb(), 1e-12);
            assertTrue(Double.isNaN(read.trackPeak()));
            var clamped = new ReplayGain(200, Double.NaN, -200, Double.NaN).toTags(MediaTags.empty(),
                    ReplayGain.TagFormat.R128);
            assertEquals("32767", clamped.get(ReplayGain.R128_TRACK_GAIN).orElseThrow());
            assertEquals("-32768", clamped.get(ReplayGain.R128_ALBUM_GAIN).orElseThrow());
        }

        @Test
        @DisplayName("parses loosely written fields, ignores case and drops values that do not parse")
        void parsing() {
            var tags = MediaTags.builder().set("replaygain_track_gain", "+2.5dB").set("REPLAYGAIN_TRACK_PEAK", " 0.5 ")
                    .set("REPLAYGAIN_ALBUM_GAIN", "loud").set("REPLAYGAIN_ALBUM_PEAK", "-1")
                    .set("R128_ALBUM_GAIN", "256").build();
            var read = ReplayGain.fromTags(tags);
            assertEquals(2.5, read.trackGainDb());
            assertEquals(0.5, read.trackPeak());
            assertEquals(6.0, read.albumGainDb(), "the R128 album gain replaces the unparsable one");
            assertTrue(Double.isNaN(read.albumPeak()));
            var odd = ReplayGain.fromTags(MediaTags.builder().set(ReplayGain.TRACK_GAIN, "250 dB")
                    .set(ReplayGain.R128_TRACK_GAIN, "1e3").set(ReplayGain.ALBUM_GAIN, "NaN").build());
            assertTrue(Double.isNaN(odd.trackGainDb()));
            assertTrue(Double.isNaN(odd.albumGainDb()));
            var empty = ReplayGain.fromTags(MediaTags.empty());
            assertTrue(Double.isNaN(empty.trackGainDb()) && Double.isNaN(empty.albumPeak()));
        }

        @Test
        @DisplayName("chooses the playback gain by mode, adds the preamp and prevents clipping")
        void playback() {
            assertEquals(-6.4821, gains.playbackGainDb(ReplayGain.Mode.TRACK, 0, false).orElseThrow(), 1e-12);
            assertEquals(3.004, gains.playbackGainDb(ReplayGain.Mode.ALBUM, 0, false).orElseThrow(), 1e-12);
            // the album peak of 1.25 allows at most -1.94 dB
            assertEquals(-20 * Math.log10(1.25), gains.playbackGainDb(ReplayGain.Mode.ALBUM, 0, true).orElseThrow(),
                    1e-12);
            assertEquals(-4.4821, gains.playbackGainDb(ReplayGain.Mode.TRACK, 2, true).orElseThrow(), 1e-12);
            var trackOnly = new ReplayGain(1, Double.NaN, Double.NaN, Double.NaN);
            assertEquals(1, trackOnly.playbackGainDb(ReplayGain.Mode.ALBUM, 0, true).orElseThrow());
            assertTrue(new ReplayGain(Double.NaN, 1, Double.NaN, 1).playbackGainDb(ReplayGain.Mode.TRACK, 0, true)
                    .isEmpty());
            assertThrows(IllegalArgumentException.class, () -> gains.playbackGainDb(ReplayGain.Mode.TRACK,
                    Double.POSITIVE_INFINITY, true));
        }
    }

    @Nested
    @DisplayName("files")
    class Files_ {
        @Test
        @DisplayName("tags an album of WAV files and the reader applies the tagged gain")
        void album(@TempDir Path directory) throws IOException {
            var first = Files.write(directory.resolve("1.wav"), Signals.wav(Signals.tones(RATE, 2, 6, -23), RATE));
            var second = Files.write(directory.resolve("2.wav"), Signals.wav(Signals.tones(RATE, 2, 6, -33), RATE));
            var gains = Loudness.writeReplayGain(List.of(first, second));
            assertEquals(gains, Loudness.replayGain(List.of(first, second)), "tags do not change the audio");
            var tags = TagEditor.read(first);
            var read = ReplayGain.fromTags(tags);
            assertEquals(gains.getFirst().trackGainDb(), read.trackGainDb(), 0.005);
            assertEquals(gains.getFirst().albumGainDb(), read.albumGainDb(), 0.005);
            assertEquals(5, read.trackGainDb(), 0.1);
            try (var source = MediaSource.of(second);
                 var other = MediaSource.of(second);
                 var plain = AudioReader.open(source);
                 var track = AudioReader.open(other, AudioReaderOptions.builder()
                         .replayGain(ReplayGain.Mode.TRACK).build())) {
                assertEquals(ReplayGain.fromTags(TagEditor.read(second)).trackGainDb(), track.replayGainDb());
                var factor = Math.pow(10, track.replayGainDb() / 20);
                var reference = plain.read().plane(0).clone();
                var scaled = track.read().plane(0);
                for (var i = 0; i < 4096; i++) {
                    assertEquals(reference[i] * factor, scaled[i], 1e-6);
                }
            }
            var silent = Files.write(directory.resolve("plain.wav"), Signals.wav(Signals.tones(RATE, 2, 1, -20), RATE));
            try (var source = MediaSource.of(silent); var reader = AudioReader.open(source, AudioReaderOptions.builder()
                    .replayGain(ReplayGain.Mode.ALBUM).build())) {
                assertEquals(0, reader.replayGainDb(), "an untagged file is unchanged");
            }
        }

        @Test
        @DisplayName("the reader applies the preamp and clip prevention, to double output too")
        void preampAndClipping(@TempDir Path directory) throws IOException {
            var file = Files.write(directory.resolve("tagged.wav"), Signals.wav(Signals.tones(RATE, 2, 2, -20), RATE));
            TagEditor.write(file, new ReplayGain(6, 0.9, Double.NaN, Double.NaN)
                    .toTags(TagEditor.read(file), ReplayGain.TagFormat.REPLAYGAIN));
            var clipFree = -20 * Math.log10(0.9);
            try (var plainSource = MediaSource.of(file); var source = MediaSource.of(file);
                 var plain = AudioReader.open(plainSource, AudioReaderOptions.builder()
                         .sampleFormat(SampleFormat.DOUBLE).build());
                 var reader = AudioReader.open(source, AudioReaderOptions.builder().sampleFormat(SampleFormat.DOUBLE)
                         .replayGain(ReplayGain.Mode.ALBUM).build())) {
                assertEquals(clipFree, reader.replayGainDb(), 1e-6, "album mode falls back to the track gain");
                var factor = Math.pow(10, reader.replayGainDb() / 20);
                var reference = plain.readDoubles().plane(1).clone();
                var scaled = reader.readDoubles().plane(1);
                for (var i = 0; i < reference.length; i++) {
                    assertEquals(reference[i] * factor, scaled[i], 1e-12);
                }
            }
            try (var source = MediaSource.of(file); var reader = AudioReader.open(source, AudioReaderOptions.builder()
                    .replayGain(ReplayGain.Mode.TRACK).replayGainPreampDb(-2).replayGainClipPrevention(false).build())) {
                assertEquals(4, reader.replayGainDb(), 1e-9);
            }
        }

        @Test
        @DisplayName("the reader mixes with a caller's matrix, which needs float output")
        void mixing(@TempDir Path directory) throws IOException {
            var planes = Signals.tones(RATE, 2, 1, -20);
            for (var i = 0; i < planes[1].length; i++) {
                planes[1][i] *= 0.25f;
            }
            var file = Files.write(directory.resolve("mix.wav"), Signals.wav(planes, RATE));
            var swap = com.github.auties00.smalt.audio.process.ChannelMixerOptions.ofMatrix(
                    new double[][]{{0, 1}, {1, 0}});
            try (var plainSource = MediaSource.of(file); var source = MediaSource.of(file);
                 var plain = AudioReader.open(plainSource);
                 var reader = AudioReader.open(source, AudioReaderOptions.builder().mixing(swap).build())) {
                var reference = plain.read();
                var left = reference.plane(0).clone();
                var right = reference.plane(1).clone();
                var swapped = reader.read();
                assertArrayEquals(Arrays.copyOf(right, swapped.frames()), Arrays.copyOf(swapped.plane(0), swapped.frames()));
                assertArrayEquals(Arrays.copyOf(left, swapped.frames()), Arrays.copyOf(swapped.plane(1), swapped.frames()));
            }
            assertThrows(IllegalArgumentException.class, () -> AudioReaderOptions.builder().mixing(swap)
                    .sampleFormat(SampleFormat.DOUBLE).build());
            assertThrows(IllegalArgumentException.class, () -> com.github.auties00.smalt.audio.AudioStreamDecoderOptions
                    .builder().mixing(swap).sampleFormat(SampleFormat.INTEGER).build());
            var options = AudioReaderOptions.builder().mixing(swap).build();
            assertEquals(options, options.toBuilder().build());
            assertNotEquals(options, AudioReaderOptions.defaults());
        }

        @Test
        @DisplayName("the reader rejects ReplayGain with integer output")
        void integerOutput() {
            assertThrows(IllegalArgumentException.class, () -> AudioReaderOptions.builder()
                    .replayGain(ReplayGain.Mode.TRACK).sampleFormat(SampleFormat.INTEGER).build());
            assertThrows(IllegalArgumentException.class, () -> AudioReaderOptions.builder()
                    .replayGainPreampDb(Double.NaN).build());
            var options = AudioReaderOptions.builder().replayGain(ReplayGain.Mode.ALBUM).replayGainPreampDb(-3)
                    .replayGainClipPrevention(false).build();
            assertEquals(options, options.toBuilder().build());
            assertTrue(options.toBuilder().noReplayGain().build().replayGain().isEmpty());
        }
    }
}
