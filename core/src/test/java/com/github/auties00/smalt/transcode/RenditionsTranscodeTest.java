package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static com.github.auties00.smalt.transcode.TranscodeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Writes several outputs from one read of an input, the renditions of an adaptive streaming ladder, and checks that
 * each output is byte for byte the file a transcode of its own writes, including from a source that can be read only
 * once, which a separate read of the input per output could not do.
 */
@DisplayName("Transcodes into several outputs")
class RenditionsTranscodeTest {
    static final Rational FPS = new Rational(10, 1);

    static byte[] clip() {
        return y4m(raw(0, 64, 32, FPS, 20, index -> gradient(64, 32, index)));
    }

    static List<TranscodeOptions> ladder() {
        var base = TranscodeOptions.of(OutputContainer.Y4M);
        return List.of(
                base.toBuilder().video(VideoTranscodeOptions.encode(Codec.RAW_VIDEO)).build(),
                base.toBuilder().video(VideoTranscodeOptions.encode(Codec.RAW_VIDEO).toBuilder().maxSize(32, 16).build())
                        .build(),
                base.toBuilder().video(VideoTranscodeOptions.encode(Codec.RAW_VIDEO).toBuilder().maxSize(16, 8)
                        .frameRate(new Rational(5, 1)).build()).build(),
                base.toBuilder().video(VideoTranscodeOptions.copy()).build());
    }

    static List<byte[]> run(MediaSource input, List<TranscodeOptions> options) {
        var sinks = new ArrayList<com.github.auties00.smalt.io.ByteArrayMediaSink>();
        var outputs = new ArrayList<TranscodeOutput>();
        for (var option : options) {
            var sink = MediaSink.memory();
            sinks.add(sink);
            outputs.add(new TranscodeOutput(sink, option));
        }
        var results = Transcoder.transcode(input, outputs);
        assertEquals(options.size(), results.size());
        var bytes = new ArrayList<byte[]>();
        for (var sink : sinks) {
            bytes.add(sink.toByteArray());
        }
        return bytes;
    }

    @Test
    @DisplayName("writes every rendition as a transcode of its own would, sharing one decode")
    void ladderEqualsSeparate() {
        var input = clip();
        var together = run(MediaSource.of(input), ladder());
        for (var index = 0; index < ladder().size(); index++) {
            assertArrayEquals(transcode(input, ladder().get(index)), together.get(index), "rendition " + index);
        }
        assertEquals(32, video(together.get(1)).getFirst().picture().width());
        assertEquals(10, video(together.get(2)).size());
    }

    @Test
    @DisplayName("reads a source that can be read once a single time for every rendition")
    void forwardOnly() {
        var input = clip();
        var stream = MediaSource.of(new ByteArrayInputStream(input), input.length);
        var together = run(stream, ladder());
        for (var index = 0; index < ladder().size(); index++) {
            assertArrayEquals(transcode(input, ladder().get(index)), together.get(index), "rendition " + index);
        }
    }

    @Test
    @DisplayName("writes audio renditions at several rates from one read of the input")
    void audio() {
        var rate = 48_000;
        var input = wav(rate, new float[][]{sine(440, rate, rate), sine(660, rate, rate)});
        var base = TranscodeOptions.of(OutputContainer.WAV);
        var options = List.of(base, base.toBuilder().audio(AudioTranscodeOptions.builder().sampleRate(16_000)
                .maxChannels(1).build()).build(), base.toBuilder().audio(AudioTranscodeOptions.builder()
                .codec(Codec.PCM_S16LE).fades(List.of(Fade.out(200_000))).build()).build());
        var together = run(MediaSource.of(input), options);
        for (var index = 0; index < options.size(); index++) {
            assertArrayEquals(transcode(input, options.get(index)), together.get(index), "rendition " + index);
        }
    }

    @Test
    @DisplayName("requires the outputs to share the input side of their options, and runAll for several outputs")
    void contract() {
        var input = clip();
        var first = TranscodeOptions.of(OutputContainer.Y4M);
        var outputs = List.of(new TranscodeOutput(MediaSink.memory(), first),
                new TranscodeOutput(MediaSink.memory(), first.toBuilder().range(0, 500_000).build()));
        assertThrows(IllegalArgumentException.class, () -> Transcoder.transcode(MediaSource.of(input), outputs));
        var same = List.of(new TranscodeOutput(MediaSink.memory(), first), new TranscodeOutput(MediaSink.memory(), first));
        var job = Transcoder.job(List.of(TranscodeInput.of(MediaSource.of(input))), same);
        assertThrows(IllegalStateException.class, job::run);
        assertEquals(2, job.runAll().size());
        assertThrows(IllegalStateException.class, job::runAll);
        var canceled = Transcoder.job(List.of(TranscodeInput.of(MediaSource.of(input))), same);
        canceled.cancel();
        assertThrows(CancellationException.class, canceled::runAll);
        assertThrows(IllegalArgumentException.class, () -> Transcoder.job(List.of(), same));
        assertThrows(IllegalArgumentException.class, () -> Transcoder.job(List.of(TranscodeInput.of(MediaSource.of(input))),
                List.of()));
        assertThrows(NullPointerException.class, () -> new TranscodeOutput(null, first));
    }
}
