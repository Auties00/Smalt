package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the failure path of {@link Transcoder#transcodeImage(Path, Path, ImageTranscodeOptions)}:
 * the output file is deleted when the transcode fails. The success path needs the image codecs and
 * is covered by the integration tests.
 */
@DisplayName("Transcoder.transcodeImage on files")
class TranscoderPathTest {
    @Test
    @DisplayName("deletes the output when the input is not an image")
    void deletesOutput(@TempDir Path directory) throws IOException {
        var pcm = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 800);
        var input = Files.write(directory.resolve("tone.wav"),
                MuxSupport.toMemory(List.of(pcm.track()), pcm.packets(), WavMuxer::new));
        var output = directory.resolve("out.png");
        assertThrows(MediaException.Unsupported.class,
                () -> Transcoder.transcodeImage(input, output, ImageTranscodeOptions.defaults()));
        assertFalse(Files.exists(output));
        Files.delete(input);
    }
}
