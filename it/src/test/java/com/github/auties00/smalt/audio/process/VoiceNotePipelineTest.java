package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.flac.FlacDecoder;
import com.github.auties00.smalt.audio.mp3.Mp3Decoder;
import com.github.auties00.smalt.audio.pcm.PcmDecoder;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.aiff.AiffDemuxer;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the voice-note preparation chain end to end on every input family of this workstream:
 * demux, decode, drop the gapless priming, mix to mono and resample to 16 kHz, then compares the
 * result with ffmpeg's {@code -ac 1 -ar 16000} conversion of the same file (sample count and SNR).
 */
class VoiceNotePipelineTest {
    static Stream<Arguments> inputs() {
        Function<MediaSource, Demuxer> wav = WavDemuxer::new;
        Function<MediaSource, Demuxer> aiff = AiffDemuxer::new;
        Function<MediaSource, Demuxer> flac = FlacDemuxer::new;
        Function<MediaSource, Demuxer> ogg = OggDemuxer::new;
        Function<MediaSource, Demuxer> mpeg = MpegAudioDemuxer::new;
        Function<AudioTrack, AudioDecoder> pcm = PcmDecoder::new;
        Function<AudioTrack, AudioDecoder> flacDecoder = FlacDecoder::new;
        Function<AudioTrack, AudioDecoder> mp3 = Mp3Decoder::new;
        return Stream.of(
                Arguments.of("wav 44.1k stereo", Fixtures.tone("wav", 1000, 44100, 2, 2.3, List.of("-c:a", "pcm_s16le")), wav, pcm),
                Arguments.of("wav 48k 5.1 float", Fixtures.tone("wav", 1000, 48000, 6, 1.1, List.of("-c:a", "pcm_f32le")), wav, pcm),
                Arguments.of("aiff 22.05k mono", Fixtures.tone("aiff", 1000, 22050, 1, 1.7, List.of("-c:a", "pcm_s16be")), aiff, pcm),
                Arguments.of("flac 96k stereo", Fixtures.tone("flac", 1000, 96000, 2, 1.2, List.of("-c:a", "flac")), flac, flacDecoder),
                Arguments.of("ogg flac 8k mono", Fixtures.tone("oga", 1000, 8000, 1, 1.4, List.of("-c:a", "flac")), ogg, flacDecoder),
                Arguments.of("mp3 44.1k stereo", Fixtures.tone("mp3", 1000, 44100, 2, 2.1, List.of("-c:a", "libmp3lame", "-b:a", "192k")), mpeg, mp3),
                Arguments.of("mp3 24k mono", Fixtures.tone("mp3", 1000, 24000, 1, 1.9, List.of("-c:a", "libmp3lame")), mpeg, mp3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("prepares 16 kHz mono audio matching ffmpeg's conversion")
    void voiceNote(String name, Path file, Function<MediaSource, Demuxer> demuxers,
                   Function<AudioTrack, AudioDecoder> decoders) {
        AudioPipeline.Decoded decoded;
        try (var demuxer = demuxers.apply(MediaSource.of(Fixtures.read(file)))) {
            decoded = AudioPipeline.decode(demuxer, decoders);
        }
        var interleaved = decoded.trimmed();
        var channels = decoded.channels();
        var frames = interleaved.length / channels;
        var planes = new float[channels][frames];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                planes[c][i] = interleaved[i * channels + c];
            }
        }
        var layout = decoded.track().layout().orElse(ChannelLayout.defaultFor(channels));
        var mono = new float[1][frames];
        new ChannelMixer(layout, ChannelLayout.MONO).process(planes, mono, frames);
        var resampler = new Resampler(decoded.sampleRate(), 16000, 1, Resampler.Quality.STANDARD);
        var output = new float[1][resampler.maxOutputFrames(frames) + resampler.maxOutputFrames(0)];
        var count = resampler.process(mono, frames, output, 0);
        count += resampler.flush(output, count);

        // ffmpeg applies the same downmix (BS.775 folded to mono) and a 32-tap windowed-sinc resampler
        var pan = switch (channels) {
            case 1 -> "c0=c0";
            case 2 -> "c0=0.5*c0+0.5*c1";
            default -> "c0=0.5*FL+0.5*FR+0.7071068*FC+0.3535534*BL+0.3535534*BR";
        };
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(),
                "-af", "pan=mono|" + pan + ",aresample=16000:filter_size=32:cutoff=0.97", "-f", "f32le", "-"));
        var expected = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected);
        assertEquals(expected.length, count, "16 kHz sample count");
        var snr = Quality.snr(expected, java.util.Arrays.copyOf(output[0], count));
        // the two resamplers treat the abrupt start and end of the tone differently; away from them they agree closely
        var edge = 256;
        var interior = Quality.snr(java.util.Arrays.copyOfRange(expected, edge, count - edge),
                java.util.Arrays.copyOfRange(output[0], edge, count - edge));
        System.out.printf("voice-note pipeline %s: %d samples at 16 kHz, SNR vs ffmpeg %.1f dB (%.1f dB away from the edges)%n",
                name, count, snr, interior);
        assertTrue(snr > 40, "SNR " + snr + " dB");
        assertTrue(interior > 60, "interior SNR " + interior + " dB");
    }
}
