package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes PCM audio with only this module's codecs, comparing the output samples with the
 * input samples exactly: copies, sample-exact trims with and without seeking, gain, mixing, the
 * job controls, and the failures that name a missing artifact.
 */
class PcmTranscodeTest {
    /**
     * The sampling rate of the input.
     */
    private static final int RATE = 44_100;

    /**
     * The length of the input in samples per channel.
     */
    private static final int FRAMES = RATE * 3;

    /**
     * Returns a 3-second stereo 16-bit WAV file.
     */
    static byte[] wav() {
        var tone = PcmPackets.tone(Codec.PCM_S16LE, RATE, ChannelLayout.STEREO, FRAMES);
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink)) {
            muxer.addTrack(tone.track());
            tone.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Decodes every sample of the first audio track, interleaved.
     */
    static float[][] samples(byte[] file) {
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            var channels = reader.layout().channels();
            var planes = new ArrayList<float[]>();
            var total = 0;
            for (var block = reader.read(); block != null; block = reader.read()) {
                var copy = new float[channels * block.frames()];
                for (var channel = 0; channel < channels; channel++) {
                    System.arraycopy(block.plane(channel), 0, copy, channel * block.frames(), block.frames());
                }
                planes.add(copy);
                total += block.frames();
            }
            var result = new float[channels][total];
            var offset = 0;
            for (var copy : planes) {
                var frames = copy.length / channels;
                for (var channel = 0; channel < channels; channel++) {
                    System.arraycopy(copy, channel * frames, result[channel], offset, frames);
                }
                offset += frames;
            }
            return result;
        }
    }

    static byte[] transcode(byte[] input, TranscodeOptions options) {
        var output = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(input), output, options);
        return output.toByteArray();
    }

    @Nested
    @DisplayName("copies and conversions")
    class Conversions {
        @Test
        @DisplayName("copies PCM into WAV unchanged")
        void copy() {
            var input = wav();
            var output = MediaSink.memory();
            var result = Transcoder.transcode(MediaSource.of(input), output, TranscodeOptions.of(OutputContainer.WAV));
            assertTrue(result.remuxed());
            assertEquals(3_000_000, result.durationMicros());
            assertArrayEquals(samples(input)[0], samples(output.toByteArray())[0]);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OutputContainer.class, names = {"WAV", "AIFF", "MATROSKA"})
        @DisplayName("writes the same samples into every PCM container")
        void containers(OutputContainer container) {
            var input = wav();
            var output = transcode(input, TranscodeOptions.of(container));
            assertEquals(container.format(), Containers.detect(MediaSource.of(output)).orElseThrow());
            var expected = samples(input);
            var actual = samples(output);
            assertArrayEquals(expected[0], actual[0]);
            assertArrayEquals(expected[1], actual[1]);
        }

        @Test
        @DisplayName("applies gain, mixes down and keeps the length when resampling")
        void processing() {
            var input = wav();
            var halved = samples(transcode(input, TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.builder().gainDb(20 * Math.log10(0.5))
                            .codec(Codec.PCM_F32LE).build()).build()));
            var source = samples(input);
            for (var index = 0; index < FRAMES; index += 97) {
                assertEquals(source[0][index] / 2, halved[0][index], 1e-6);
            }
            var mono = samples(transcode(input, TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.builder().maxChannels(1).build()).build()));
            assertEquals(1, mono.length);
            assertEquals(FRAMES, mono[0].length);
            var resampled = samples(transcode(input, TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.builder().sampleRate(16_000).build()).build()));
            assertEquals(48_000, resampled[0].length);
        }
    }

    @Nested
    @DisplayName("time ranges")
    class Ranges {
        @ParameterizedTest(name = "{0}..{1} us, seeking {2}")
        @CsvSource({"500000, 1250000, true", "500000, 1250000, false", "0, 1000000, true", "2999000, 3000000, false",
                "1234567, 2999999, true"})
        @DisplayName("keep exactly the samples of the range when encoding")
        void encoded(long start, long end, boolean seeking) {
            var input = wav();
            var options = TranscodeOptions.of(OutputContainer.WAV).toBuilder().range(start, end).seeking(seeking)
                    .audio(AudioTranscodeOptions.encode(Codec.PCM_S16LE)).build();
            var output = samples(transcode(input, options));
            var source = samples(input);
            var first = (int) Rational.MICROSECONDS.rescale(start, Rational.timeBaseOf(RATE));
            var count = (int) Rational.MICROSECONDS.rescale(end - start, Rational.timeBaseOf(RATE));
            assertEquals(count, output[0].length);
            assertArrayEquals(Arrays.copyOfRange(source[0], first, first + count), output[0]);
            assertArrayEquals(Arrays.copyOfRange(source[1], first, first + count), output[1]);
        }

        @Test
        @DisplayName("produce byte-identical output whether the demuxer seeks or not")
        void seeking() {
            var input = wav();
            var options = TranscodeOptions.of(OutputContainer.WAV).toBuilder().range(777_777, 2_222_222)
                    .audio(AudioTranscodeOptions.builder().gainDb(-1).build()).build();
            assertArrayEquals(transcode(input, options.toBuilder().seeking(false)
                    .build()), transcode(input, options.toBuilder().seeking(true).build()));
        }

        @Test
        @DisplayName("limit the duration from the start")
        void maxDuration() {
            var output = samples(transcode(wav(), TranscodeOptions.of(OutputContainer.WAV).toBuilder().start(2_000_000)
                    .maxDuration(500_000).audio(AudioTranscodeOptions.encode(Codec.PCM_S16LE)).build()));
            assertEquals(RATE / 2, output[0].length);
        }

        @Test
        @DisplayName("cut copied PCM to exactly the samples of the range")
        void copied() {
            var input = wav();
            var output = transcode(input, TranscodeOptions.remux(OutputContainer.WAV).toBuilder()
                    .range(1_000_000, 2_000_000).build());
            var source = samples(input);
            var copied = samples(output);
            assertArrayEquals(Arrays.copyOfRange(source[0], RATE, 2 * RATE), copied[0]);
            assertArrayEquals(Arrays.copyOfRange(source[1], RATE, 2 * RATE), copied[1]);
        }
    }

    @Nested
    @DisplayName("job controls")
    class Controls {
        @Test
        @DisplayName("report a monotonic progress ending at the output duration")
        void progress() {
            var reports = new ArrayList<TranscodeProgress>();
            var result = Transcoder.transcode(MediaSource.of(wav()), MediaSink.memory(), TranscodeOptions.of(OutputContainer.AIFF)
                    .toBuilder().listener(reports::add).build());
            assertFalse(reports.isEmpty());
            for (var index = 1; index < reports.size(); index++) {
                assertTrue(reports.get(index).positionMicros() >= reports.get(index - 1).positionMicros());
            }
            assertEquals(result.durationMicros(), reports.getLast().positionMicros());
            assertEquals(3_000_000, reports.getFirst().durationMicros());
        }

        @Test
        @DisplayName("stop on cancellation, mid-run or before running")
        void cancel() {
            var jobs = new TranscodeJob[1];
            jobs[0] = Transcoder.job(MediaSource.of(wav()), MediaSink.memory(), TranscodeOptions.of(OutputContainer.AIFF)
                    .toBuilder().listener(progress -> jobs[0].cancel()).build());
            assertThrows(CancellationException.class, jobs[0]::run);
            assertTrue(jobs[0].isCanceled());
            var output = MediaSink.memory();
            var job = Transcoder.job(MediaSource.of(wav()), output, TranscodeOptions.of(OutputContainer.AIFF));
            job.cancel();
            assertThrows(CancellationException.class, job::run);
            assertEquals(0, output.toByteArray().length);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {
        @Test
        @DisplayName("name the artifact of a missing encoder before writing anything")
        void missingEncoder() {
            var output = MediaSink.memory();
            var opus = assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(MediaSource.of(wav()), output,
                    TranscodeOptions.of(OutputContainer.OGG)));
            assertEquals("smalt-audio", opus.artifact().orElseThrow());
            var aac = assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(MediaSource.of(wav()), output,
                    TranscodeOptions.of(OutputContainer.ADTS)));
            assertEquals("smalt-mpeg", aac.artifact().orElseThrow());
            // M4A carries no PCM, so the default policy encodes AAC; MP4 carries it and copies it
            var m4a = assertThrows(MediaException.Unsupported.class,
                    () -> Transcoder.transcode(MediaSource.of(wav()), output,
                            TranscodeOptions.of(OutputContainer.M4A)));
            assertEquals("smalt-mpeg", m4a.artifact().orElseThrow());
            assertEquals(0, output.toByteArray().length);
            var mp4 = MediaSink.memory();
            var copied = Transcoder.transcode(MediaSource.of(wav()), mp4, TranscodeOptions.of(OutputContainer.MP4));
            assertTrue(copied.remuxed());
            assertEquals(Codec.PCM_S16LE, copied.streams().getFirst().track().codec());
        }

        @Test
        @DisplayName("encode PCM that a transport stream cannot carry and copy the PCM it can")
        void transportStream() {
            // the 44.1 kHz input has no transport stream carriage, so the default policy encodes AAC
            var output = MediaSink.memory();
            var aac = assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(MediaSource.of(wav()),
                    output, TranscodeOptions.of(OutputContainer.MPEG_TS)));
            assertEquals("smalt-mpeg", aac.artifact().orElseThrow());
            var tone = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 48_000);
            var sink = MediaSink.memory();
            try (var muxer = new WavMuxer(sink)) {
                muxer.addTrack(tone.track());
                tone.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            var input = sink.toByteArray();
            var ts = MediaSink.memory();
            var copied = Transcoder.transcode(MediaSource.of(input), ts, TranscodeOptions.of(OutputContainer.MPEG_TS));
            assertEquals(Codec.PCM_S16LE, copied.streams().getFirst().track().codec());
            var expected = samples(input);
            var actual = samples(ts.toByteArray());
            assertEquals(expected.length, actual.length);
            for (var channel = 0; channel < expected.length; channel++) {
                assertArrayEquals(expected[channel], actual[channel], "channel " + channel);
            }
        }

        @Test
        @DisplayName("reject inputs that are not media")
        void unknown() {
            assertThrows(MediaException.Unsupported.class, () -> Transcoder.transcode(MediaSource.of(new byte[64]),
                    MediaSink.memory(), TranscodeOptions.defaults()));
        }

        @Test
        @DisplayName("reject options that select nothing or the wrong track")
        void selection() {
            var input = wav();
            assertThrows(IllegalArgumentException.class, () -> transcode(input, TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().noAudio().build()));
            assertThrows(IllegalArgumentException.class, () -> transcode(input, TranscodeOptions.of(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.builder().trackId(7).build()).build()));
            assertThrows(MediaException.Unsupported.class, () -> transcode(input, TranscodeOptions.remux(OutputContainer.WAV)
                    .toBuilder().audio(AudioTranscodeOptions.copy().toBuilder().sampleRate(8_000).build()).build()));
        }
    }
}
