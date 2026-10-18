package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Media#probe(Path)} and {@link Media#probe(Path, ProbeOptions)}, and the
 * {@code bitrate()} accessors of {@link MediaInfo} and {@link StreamInfo}.
 */
@DisplayName("Probe additions")
class ProbeApiTest {
    private static byte[] wav() {
        var pcm = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 48_000);
        return MuxSupport.toMemory(List.of(pcm.track()), pcm.packets(), WavMuxer::new);
    }

    @Test
    @DisplayName("probe(Path) describes the file as probe(MediaSource) does")
    void path(@TempDir Path directory) throws IOException {
        var data = wav();
        var file = Files.write(directory.resolve("tone.wav"), data);
        var expected = Media.probe(MediaSource.of(data));
        assertEquals(expected.toString(), Media.probe(file).toString());
        var options = ProbeOptions.builder().readFirstFrames(false).build();
        assertEquals(Media.probe(MediaSource.of(data), options).toString(), Media.probe(file, options).toString());
        Files.delete(file);
    }

    @Test
    @DisplayName("probe(Path) reports a missing file as an I/O failure")
    void missing(@TempDir Path directory) {
        assertThrows(MediaException.Io.class, () -> Media.probe(directory.resolve("missing.wav")));
    }

    @Test
    @DisplayName("bitrate() reports the file and stream bitrates")
    void bitrate() {
        var info = Media.probe(MediaSource.of(wav()));
        assertTrue(info.bitrate().isPresent());
        assertEquals(1_536_000, info.streams().getFirst().bitrate().orElseThrow());
    }
}
