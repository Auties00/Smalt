package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that transcoding streams: the live heap, sampled after a collection at every tenth of
 * the progress, stays within a fixed bound while long inputs are transcoded, far below what
 * buffering the decoded media would take.
 */
class TranscodeMemoryTest {
    /**
     * The largest live-heap growth allowed during a transcode, in bytes.
     */
    private static final long BOUND = 64L << 20;

    @Test
    @DisplayName("transcodes four minutes of audio within a fixed heap bound")
    void longAudio(@TempDir Path directory) {
        // 4 min of 48 kHz stereo decodes to 92 MB of float samples
        var input = Fixtures.tone("wav", 440, 48_000, 2, 240, List.of("-c:a", "pcm_s16le"));
        var output = directory.resolve("out.opus");
        var growth = measure(input, output, TranscodeOptions.of(OutputContainer.OGG));
        Oracle.assertDecodesCleanly(output);
        assertTrue(growth < BOUND, "heap grew by " + (growth >> 20) + " MiB");
    }

    @Test
    @DisplayName("transcodes a minute of video within a fixed heap bound")
    void longVideo(@TempDir Path directory) {
        // 1800 frames of 320x240 decode to 207 MB of pictures
        var input = Fixtures.testVideo("mp4", 320, 240, 60, List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt",
                "yuv420p", "-c:a", "aac", "-shortest"));
        var output = directory.resolve("out.mp4");
        var growth = measure(input, output, TranscodeOptions.of(OutputContainer.MP4)
                .toBuilder().video(VideoTranscodeOptions.builder().mode(StreamMode.ENCODE).build()).build());
        assertEquals(1800, MediaProbe.of(output).video().decoded());
        assertTrue(growth < BOUND, "heap grew by " + (growth >> 20) + " MiB");
    }

    /**
     * Runs a transcode and returns the largest live-heap growth over the heap before it.
     */
    private static long measure(Path input, Path output, TranscodeOptions options) {
        var baseline = liveHeap();
        var peak = new long[]{baseline};
        var next = new double[]{0.1};
        var sampled = options.toBuilder().listener(progress -> {
            var fraction = progress.fraction().orElse(0);
            if (fraction >= next[0]) {
                next[0] += 0.1;
                peak[0] = Math.max(peak[0], liveHeap());
            }
        }).build();
        try (var in = MediaSource.of(input); var out = MediaSink.of(output)) {
            Transcoder.transcode(in, out, sampled);
        }
        assertTrue(next[0] > 0.8, "sampled through the run");
        return peak[0] - baseline;
    }

    /**
     * Returns the live heap after a collection.
     */
    private static long liveHeap() {
        var runtime = Runtime.getRuntime();
        for (var round = 0; round < 2; round++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
