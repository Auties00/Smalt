package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.loudness.SilenceDetectorOptions;
import com.github.auties00.smalt.audio.process.Biquad;
import com.github.auties00.smalt.audio.process.BiquadFilter;
import com.github.auties00.smalt.audio.process.TimeStretcher;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.github.auties00.smalt.transcode.TranscodeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes 32-bit float WAV files through the audio steps and checks each against its definition on the decoded
 * samples: the filters equal a {@link BiquadFilter} run over the input, the fades equal the input times the curve,
 * a speed change keeps the pitch and divides the length exactly, a pitch change keeps the length, and the silence
 * trim cuts the input exactly where the silences are.
 */
@DisplayName("Audio effects in a transcode")
class AudioEffectsTranscodeTest {
    static final int RATE = 48_000;

    static TranscodeOptions.Builder wavOptions(AudioTranscodeOptions.Builder audio) {
        return TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(audio.codec(Codec.PCM_F32LE).build());
    }

    static float[] cosine(double frequency, int frames) {
        var samples = new float[frames];
        for (var index = 0; index < frames; index++) {
            samples[index] = (float) (0.5 * Math.cos(2 * Math.PI * frequency * index / RATE));
        }
        return samples;
    }

    static double peak(float[] samples, double low, double high) {
        var from = samples.length / 4;
        var to = samples.length - samples.length / 4;
        var best = low;
        var bestPower = -1.0;
        for (var frequency = low; frequency <= high; frequency += 1) {
            var coefficient = 2 * Math.cos(2 * Math.PI * frequency / RATE);
            double previous = 0, before = 0;
            for (var index = from; index < to; index++) {
                var current = samples[index] + coefficient * previous - before;
                before = previous;
                previous = current;
            }
            var power = previous * previous + before * before - coefficient * previous * before;
            if (power > bestPower) {
                bestPower = power;
                best = frequency;
            }
        }
        return best;
    }

    @Nested
    @DisplayName("filters")
    class Filters {
        @Test
        @DisplayName("equal the biquad cascade run over the decoded input, sample for sample")
        void cascade() {
            var low = sine(100, RATE, 2 * RATE);
            var high = sine(6_000, RATE, 2 * RATE);
            var mix = new float[low.length];
            for (var index = 0; index < mix.length; index++) {
                mix[index] = 0.6f * low[index] + 0.6f * high[index];
            }
            var input = wav(RATE, new float[][]{mix, mix.clone()});
            var cascade = List.of(Biquad.highPass(1_000), Biquad.peaking(6_000, 1, 6));
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder().filters(cascade)).build()));
            var expected = new float[][]{mix.clone(), mix.clone()};
            new BiquadFilter(cascade, 2, RATE).process(expected, 0, mix.length);
            assertArrayEquals(expected[0], output[0]);
            assertArrayEquals(expected[1], output[1]);
            var gain = rms(output[0], RATE, 2 * RATE) / rms(high, RATE, 2 * RATE) / 0.6;
            assertEquals(Math.pow(10, 6 / 20.0), gain, 0.05, "the 6 kHz band is raised by 6 dB, the 100 Hz tone cut");
        }

        @Test
        @DisplayName("make a copy encode and reject a frequency above half the output rate")
        void encodeAndReject() {
            var input = wav(RATE, new float[][]{sine(440, RATE, RATE)});
            var result = Transcoder.transcode(MediaSource.of(input), MediaSink.memory(), TranscodeOptions
                    .of(OutputContainer.WAV).toBuilder().audio(AudioTranscodeOptions.builder()
                            .filters(List.of(Biquad.lowPass(4_000))).build()).build());
            assertFalse(result.streams().getFirst().copied());
            var options = TranscodeOptions.of(OutputContainer.WAV).toBuilder().audio(AudioTranscodeOptions.builder()
                    .sampleRate(16_000).filters(List.of(Biquad.lowPass(9_000))).build()).build();
            assertThrows(IllegalArgumentException.class, () -> transcode(input, options));
        }
    }

    @Nested
    @DisplayName("fades")
    class Fades {
        @ParameterizedTest(name = "{0}")
        @EnumSource(FadeCurve.class)
        @DisplayName("multiply each sample by the curve at its output time")
        void curves(FadeCurve curve) {
            var frames = 3 * RATE;
            var level = new float[frames];
            java.util.Arrays.fill(level, 0.5f);
            var input = wav(RATE, new float[][]{level});
            var fades = List.of(Fade.in(1_000_000).withCurve(curve), Fade.out(500_000).withCurve(curve));
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder().fades(fades)).build()))[0];
            assertEquals(frames, output.length);
            for (var index = 0; index < frames; index += 37) {
                var time = index * 1_000_000.0 / RATE;
                var gain = (float) (curve.gain(time / 1_000_000) * curve.gain((3_000_000 - time) / 500_000));
                assertEquals(0.5f * gain, output[index], 1e-7f, "frame " + index);
            }
            assertEquals(0, output[0]);
            assertEquals(0.5f, output[frames / 2]);
        }

        @Test
        @DisplayName("give a rising and falling envelope, anchored to the end of the kept range")
        void envelope() {
            var input = wav(RATE, new float[][]{sine(1_000, RATE, 4 * RATE)});
            var options = wavOptions(AudioTranscodeOptions.builder().fades(List.of(Fade.in(250_000, 500_000), Fade.out(1_000_000))))
                    .range(500_000, 3_500_000).build();
            var output = audio(transcode(input, options))[0];
            assertEquals(3 * RATE, output.length);
            var window = RATE / 20;
            var previous = -1.0;
            for (var start = 0; start < 3 * RATE; start += window) {
                var envelope = rms(output, start, start + window) / (0.5 / Math.sqrt(2));
                var middle = (start + window / 2) * 1_000_000.0 / RATE;
                var expected = Math.clamp((middle - 250_000) / 500_000, 0, 1) * Math.clamp((3_000_000 - middle) / 1_000_000, 0, 1);
                assertEquals(expected, envelope, 0.03, "window at " + middle);
                if (middle < 750_000) {
                    assertTrue(envelope >= previous);
                }
                previous = envelope;
            }
        }

        @Test
        @DisplayName("need a known end for a fade-out without a start")
        void unknownEnd() {
            var fade = Fade.out(1_000_000);
            assertThrows(IllegalArgumentException.class, () -> fade.gain(0, -1));
            assertEquals(1, fade.gain(1_000_000, 3_000_000));
            assertEquals(0.5, fade.gain(2_500_000, 3_000_000), 1e-12);
            assertEquals(0, fade.gain(3_000_000, 3_000_000));
            assertThrows(IllegalArgumentException.class, () -> Fade.in(0));
            assertThrows(IllegalArgumentException.class, () -> Fade.out(-1, 5));
        }
    }

    @Nested
    @DisplayName("speed and pitch")
    class SpeedAndPitch {
        @Test
        @DisplayName("speed 1.5 keeps the pitch and divides the length exactly")
        void tempo() {
            var input = wav(RATE, new float[][]{sine(440, RATE, 3 * RATE)});
            var result = Transcoder.transcode(MediaSource.of(input), MediaSink.memory(), wavOptions(AudioTranscodeOptions
                    .builder()).speed(1.5).build());
            assertEquals(2_000_000, result.durationMicros());
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder()).speed(1.5).build()))[0];
            assertEquals(2 * RATE, output.length);
            assertEquals(440, peak(output, 340, 540), 2);
        }

        @Test
        @DisplayName("without pitch preservation plays faster and higher, as a tape does")
        void tape() {
            var input = wav(RATE, new float[][]{sine(440, RATE, 3 * RATE)});
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder().preservePitch(false)).speed(1.5)
                    .build()))[0];
            assertEquals(2 * RATE, output.length);
            assertEquals(660, peak(output, 560, 760), 2);
        }

        @Test
        @DisplayName("a pitch change keeps the length and multiplies the frequency")
        void pitch() {
            var input = wav(RATE, new float[][]{sine(440, RATE, 2 * RATE)});
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder().pitch(Math.pow(2, 7 / 12.0)))
                    .build()))[0];
            assertEquals(2 * RATE, output.length);
            assertEquals(440 * Math.pow(2, 7 / 12.0), peak(output, 560, 760), 2);
        }

        @Test
        @DisplayName("a speed change of a range keeps the range on the input timeline")
        void range() {
            var input = wav(RATE, new float[][]{sine(440, RATE, 3 * RATE)});
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder()).range(1_000_000, 2_500_000)
                    .speed(0.75).build()))[0];
            assertEquals(2 * RATE, output.length);
        }

        @Test
        @DisplayName("rejects speeds and pitches out of range")
        void limits() {
            assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.builder().speed(0.05).build());
            assertThrows(IllegalArgumentException.class, () -> TranscodeOptions.builder().speed(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> AudioTranscodeOptions.builder().pitch(20).build());
            assertEquals(TimeStretcher.MAX_FACTOR, TranscodeOptions.MAX_SPEED);
            // a tape-style speed of 8 on top of a pitch of 2 would raise the pitch 16 times
            var input = wav(RATE, new float[][]{sine(440, RATE, RATE)});
            assertThrows(IllegalArgumentException.class, () -> transcode(input, wavOptions(AudioTranscodeOptions
                    .builder().preservePitch(false).pitch(2)).speed(8).build()));
            assertEquals(RATE / 4, audio(transcode(input, wavOptions(AudioTranscodeOptions.builder()
                    .preservePitch(false).pitch(2)).speed(4).build()))[0].length);
        }
    }

    @Nested
    @DisplayName("silence trimming")
    class Silence {
        /**
         * 0.5 s of silence, a 1 s tone, 0.4 s of silence, a 1 s tone, 0.6 s of silence.
         */
        static float[] speech() {
            var samples = new float[(int) (3.5 * RATE)];
            var tone = cosine(500, RATE);
            System.arraycopy(tone, 0, samples, RATE / 2, RATE);
            System.arraycopy(tone, 0, samples, (int) (1.9 * RATE), RATE);
            return samples;
        }

        @Test
        @DisplayName("removes the silence at both ends, every stream following, and keeps the pause")
        void ends() {
            var samples = speech();
            var input = wav(RATE, new float[][]{samples});
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder()).silenceTrim(SilenceTrim.defaults())
                    .build()))[0];
            var expected = java.util.Arrays.copyOfRange(samples, RATE / 2, (int) (2.9 * RATE));
            assertArrayEquals(expected, output);
        }

        @Test
        @DisplayName("keeps one end when asked and leaves an input silent throughout whole")
        void options() {
            var samples = speech();
            var input = wav(RATE, new float[][]{samples});
            var leading = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder()).silenceTrim(SilenceTrim.builder()
                    .trailing(false).build()).build()))[0];
            assertArrayEquals(java.util.Arrays.copyOfRange(samples, RATE / 2, samples.length), leading);
            var silent = wav(RATE, new float[][]{new float[RATE]});
            assertEquals(RATE, audio(transcode(silent, wavOptions(AudioTranscodeOptions.builder())
                    .silenceTrim(SilenceTrim.defaults()).build()))[0].length);
        }

        @Test
        @DisplayName("shortens a longer pause to the limit by removing its middle")
        void pauses() {
            var samples = speech();
            var input = wav(RATE, new float[][]{samples});
            var output = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder())
                    .silenceTrim(SilenceTrim.shorteningPauses(100_000)).build()))[0];
            var tone = cosine(500, RATE);
            assertEquals(2 * RATE + RATE / 10, output.length);
            assertArrayEquals(tone, java.util.Arrays.copyOfRange(output, 0, RATE));
            for (var index = RATE; index < RATE + RATE / 10; index++) {
                assertEquals(0, output[index]);
            }
            assertArrayEquals(tone, java.util.Arrays.copyOfRange(output, RATE + RATE / 10, output.length));
        }

        @Test
        @DisplayName("measures a concatenation at the first file's rate, so the trailing silence of a later file goes")
        void concatenation() {
            var first = cosine(500, RATE);
            var second = new float[44_100];
            for (var index = 0; index < 22_050; index++) {
                second[index] = (float) (0.5 * Math.cos(2 * Math.PI * 500 * index / 44_100.0));
            }
            var output = audio(concatenate(first, second))[0];
            assertEquals(RATE + RATE / 2, output.length, 48, "the second file's half second of tone stays");
            assertArrayEquals(first, java.util.Arrays.copyOf(output, RATE), "the first file is untouched");
        }

        byte[] concatenate(float[] first, float[] second) {
            var sink = MediaSink.memory();
            Transcoder.concat(List.of(MediaSource.of(wav(RATE, new float[][]{first})),
                    MediaSource.of(wav(44_100, new float[][]{second}))), sink, wavOptions(AudioTranscodeOptions
                    .builder()).silenceTrim(SilenceTrim.defaults()).build());
            return sink.toByteArray();
        }

        @Test
        @DisplayName("trims with a threshold of its own and rejects jobs it cannot serve")
        void rejections() {
            var noisy = speech();
            for (var index = 0; index < RATE / 2; index++) {
                noisy[index] = 0.002f;
            }
            var input = wav(RATE, new float[][]{noisy});
            var quiet = audio(transcode(input, wavOptions(AudioTranscodeOptions.builder()).silenceTrim(SilenceTrim.builder()
                    .detection(SilenceDetectorOptions.of(-40, 50_000)).build()).build()))[0];
            assertEquals((int) (2.4 * RATE), quiet.length);
            var noAudio = TranscodeOptions.of(OutputContainer.WAV).toBuilder().noAudio().silenceTrim(SilenceTrim.defaults())
                    .build();
            assertThrows(IllegalArgumentException.class, () -> transcode(input, noAudio));
            var subtitles = TranscodeInput.of(MediaSource.of(srt("1\n00:00:00,200 --> 00:00:00,800\nHi\n")));
            var withText = List.of(TranscodeInput.of(MediaSource.of(input)), subtitles);
            var pauses = TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                    .silenceTrim(SilenceTrim.shorteningPauses(100_000)).build();
            assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(withText, MediaSink.memory(), pauses));
        }
    }

    @Test
    @DisplayName("the new settings have named resets and take part in equality")
    void resets() {
        var audio = AudioTranscodeOptions.builder().input(2).filters(List.of(Biquad.lowPass(100)))
                .fades(List.of(Fade.in(5))).pitch(2).preservePitch(false).build();
        assertEquals(OptionalInt.of(2), audio.input());
        assertNotEquals(AudioTranscodeOptions.defaults(), audio);
        assertEquals(audio, audio.toBuilder().build());
        assertEquals(AudioTranscodeOptions.defaults(), audio.toBuilder().anyInput().noFilters().noFades().pitch(1)
                .preservePitch(true).build());
        var video = VideoTranscodeOptions.builder().input(1).fades(List.of(Fade.out(5))).fadeColor(0xFF123456).build();
        assertEquals(0x123456, video.fadeColor());
        assertEquals(VideoTranscodeOptions.defaults(), video.toBuilder().anyInput().noFades().fadeColor(0).build());
        var job = TranscodeOptions.builder().speed(2).silenceTrim(SilenceTrim.shorteningPauses(10)).build();
        assertEquals(2, job.speed());
        assertEquals(OptionalLong.of(10), job.silenceTrim().orElseThrow().maxPauseMicros());
        assertEquals(TranscodeOptions.defaults(), job.toBuilder().speed(1).noSilenceTrim().build());
        assertEquals(SilenceTrim.defaults(), SilenceTrim.shorteningPauses(10).toBuilder().keepPauses().build());
        assertEquals(SubtitleTranscodeOptions.defaults(), SubtitleTranscodeOptions.builder().input(3).anyInput().build());
        assertThrows(IllegalArgumentException.class, () -> AudioTranscodeOptions.builder().input(-1));
    }
}
