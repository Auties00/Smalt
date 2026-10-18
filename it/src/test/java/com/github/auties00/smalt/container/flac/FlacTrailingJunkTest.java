package com.github.auties00.smalt.container.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.flac.FlacDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that bytes trailing the last FLAC frame that are not a recognized tag do not reject the
 * frame, while damage inside the frame still does.
 */
class FlacTrailingJunkTest {
    static float[] decode(byte[] data) {
        try (var demuxer = new FlacDemuxer(MediaSource.of(data))) {
            return AudioPipeline.decode(demuxer, FlacDecoder::new).interleaved();
        }
    }

    @ParameterizedTest(name = "{0} junk bytes")
    @ValueSource(ints = {1, 2, 17, 300, 5000})
    @DisplayName("decodes a file followed by junk exactly like the clean file")
    void junk(int size) {
        var clean = Fixtures.read(Fixtures.tone("flac", 440, 44100, 2, 0.5, List.of("-c:a", "flac")));
        var random = new SplittableRandom(size);
        var dirty = java.util.Arrays.copyOf(clean, clean.length + size);
        for (var i = clean.length; i < dirty.length; i++) {
            dirty[i] = (byte) random.nextInt(256);
        }
        assertArrayEquals(decode(clean), decode(dirty));
    }

    @ParameterizedTest(name = "flip at end - {0}")
    @ValueSource(ints = {3, 40, 200})
    @DisplayName("still rejects a last frame whose own bytes are damaged")
    void damage(int fromEnd) {
        var data = Fixtures.read(Fixtures.tone("flac", 440, 44100, 2, 0.5, List.of("-c:a", "flac")));
        data[data.length - fromEnd] ^= 0x5A;
        assertThrows(MediaException.Malformed.class, () -> decode(data));
    }
}
