package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessMeter;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.audio.process.ChannelMixerOptions;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transcodes WAV audio through the mixing and loudness settings of {@link AudioTranscodeOptions}: a
 * caller's mix matrix, the Lt/Rt downmix, single-pass loudness normalization and two-pass normalization
 * with a true-peak limiter, comparing the output with the mixer applied directly and with the loudness
 * meter.
 */
@DisplayName("audio processing in transcodes")
class AudioProcessingTranscodeTest {
    private static final int RATE = 48_000;

    private static byte[] floatWav(float[][] planes) {
        var channels = planes.length;
        var frames = planes[0].length;
        var data = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var frame = 0; frame < frames; frame++) {
            for (var plane : planes) {
                data.putFloat(plane[frame]);
            }
        }
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + data.capacity()).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 3).putShort((short) channels).putInt(RATE)
                .putInt(RATE * channels * 4).putShort((short) (channels * 4)).putShort((short) 32);
        header.put("data".getBytes()).putInt(data.capacity());
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(data.array());
        return out.toByteArray();
    }

    private static AudioTranscodeOptions.Builder pcm() {
        return AudioTranscodeOptions.builder().codec(Codec.PCM_F32LE);
    }

    private static float[][] transcode(byte[] input, AudioTranscodeOptions audio) {
        return PcmTranscodeTest.samples(PcmTranscodeTest.transcode(input, TranscodeOptions.of(OutputContainer.WAV)
                .toBuilder().audio(audio).build()));
    }

    /**
     * A -20 dBFS stereo tone with a short near-full-scale click every two seconds, whose peaks are far
     * above its loudness and whose energy is negligible.
     */
    private static float[][] peaky(int seconds) {
        var planes = new float[2][RATE * seconds];
        for (var i = 0; i < planes[0].length; i++) {
            planes[0][i] = (float) (0.1 * Math.sin(2 * Math.PI * 1000 * i / RATE));
            planes[1][i] = (float) (0.1 * Math.sin(2 * Math.PI * 1250 * i / RATE));
        }
        for (var i = RATE / 2; i < planes[0].length; i += 2 * RATE) {
            for (var j = 0; j < 24; j++) {
                planes[0][i + j] = (float) (0.99 * Math.sin(2 * Math.PI * j / 24));
                planes[1][i + j] = planes[0][i + j];
            }
        }
        return planes;
    }

    private static LoudnessMeter measure(float[][] planes) {
        var meter = new LoudnessMeter(ChannelLayout.STEREO, RATE);
        meter.accept(planes, 0, planes[0].length);
        return meter;
    }

    @Test
    @DisplayName("applies a caller's matrix even between equal layouts")
    void matrix() {
        var input = PcmTranscodeTest.wav();
        var source = PcmTranscodeTest.samples(input);
        var output = transcode(input, pcm().mixing(ChannelMixerOptions.ofMatrix(new double[][]{{0, 0.5}, {0.25, 0}}))
                .build());
        assertEquals(source[0].length, output[0].length);
        for (var i = 0; i < source[0].length; i++) {
            assertEquals(0.5f * source[1][i], output[0][i], 1e-7f);
            assertEquals(0.25f * source[0][i], output[1][i], 1e-7f);
        }
    }

    @Test
    @DisplayName("downmixes 5.1 to Lt/Rt exactly as the mixer does")
    void ltRt() {
        var tone = PcmPackets.tone(Codec.PCM_F32LE, RATE, ChannelLayout.SURROUND_5_1, RATE);
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink)) {
            muxer.addTrack(tone.track());
            tone.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        var input = sink.toByteArray();
        // normalized, so that the float output is not clipped
        var options = ChannelMixerOptions.builder().stereoDownmix(ChannelMixerOptions.StereoDownmix.LT_RT)
                .normalize(true).build();
        var output = transcode(input, pcm().layout(ChannelLayout.STEREO).mixing(options).build());
        float[][] expected;
        try (var reader = AudioReader.open(MediaSource.of(input), AudioReaderOptions.builder()
                .layout(ChannelLayout.STEREO).mixing(options).build())) {
            var block = reader.read();
            expected = new float[][]{block.plane(0).clone(), block.plane(1).clone()};
        }
        var source = PcmTranscodeTest.samples(input);
        var mixer = new ChannelMixer(ChannelLayout.SURROUND_5_1, ChannelLayout.STEREO, options);
        var direct = new float[2][source[0].length];
        mixer.process(source, direct, source[0].length);
        for (var i = 0; i < 4096; i++) {
            assertEquals(direct[0][i], output[0][i]);
            assertEquals(direct[1][i], output[1][i]);
            assertEquals(direct[0][i], expected[0][i]);
        }
    }

    @Test
    @DisplayName("single-pass normalization reaches the target within 0.5 LU under the ceiling")
    void singlePass() {
        var input = floatWav(peaky(12));
        var normalization = LoudnessNormalization.forStreaming();
        var output = transcode(input, pcm().loudnessNormalization(normalization).singlePassNormalization(true).build());
        assertEquals(RATE * 12, output[0].length);
        var stats = measure(output).stats();
        assertEquals(-14, stats.integrated(), 0.5);
        assertTrue(stats.truePeakDb() <= -1 + 1e-3, "true peak " + stats.truePeakDb());
    }

    @Test
    @DisplayName("two-pass normalization with peak limiting reaches a target the ceiling would deny")
    void peakLimiting() {
        var input = floatWav(peaky(12));
        var plain = measure(transcode(input, pcm().loudnessNormalization(LoudnessNormalization.forStreaming())
                .build())).stats();
        assertTrue(plain.integrated() < -18, "the peaks hold the plain normalization below the target");
        var limiting = LoudnessNormalization.forStreaming().toBuilder().peakLimiting(true).build();
        var output = transcode(input, pcm().loudnessNormalization(limiting).build());
        assertEquals(RATE * 12, output[0].length);
        var stats = measure(output).stats();
        assertEquals(-14, stats.integrated(), 0.5);
        assertTrue(stats.truePeakDb() <= -1 + 1e-3, "true peak " + stats.truePeakDb());
    }

    @Test
    @DisplayName("the new settings take part in equality and in deciding whether a stream is encoded")
    void options() {
        var options = AudioTranscodeOptions.builder().singlePassNormalization(true)
                .mixing(ChannelMixerOptions.ofAc3MixLevels(1, 1)).build();
        assertEquals(options, options.toBuilder().build());
        assertNotEquals(options, AudioTranscodeOptions.defaults());
        assertFalse(options.changesSamples(RATE, ChannelLayout.STEREO), "levels alone do not touch stereo");
        var matrix = AudioTranscodeOptions.builder().mixing(ChannelMixerOptions.ofMatrix(new double[][]{{1, 0}, {0, 1}}))
                .build();
        assertTrue(matrix.changesSamples(RATE, ChannelLayout.STEREO));
    }
}
