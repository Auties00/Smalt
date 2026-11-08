package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes, and checks, the DTS stream that smalt-aot's DTS training workload decodes: one synthetic 5.1 core frame of
 * every case of {@link DtsSyntheticDifferentialTest} that codes five channels, deterministic from fixed seeds. With
 * {@code -Dsmalt.dts.writeTraining=true} the test rewrites the resource; otherwise it checks that the committed
 * resource equals the generator's output and decodes.
 */
final class DtsTrainingStreamTest {
    private static final Path RESOURCE = Path.of("..", "aot", "src", "main", "resources", "com", "github",
            "auties00", "smalt", "aot", "internal", "dts", "training.dts");

    static byte[] stream() {
        var out = new ByteArrayOutputStream();
        DtsSyntheticDifferentialTest.cases().filter(test -> test.amode() == 9).forEach(test -> {
            var frame = DtsSyntheticDifferentialTest.frame(test, new SplittableRandom(test.name().hashCode()));
            out.writeBytes(CoreStreamWriter.write(frame));
        });
        return out.toByteArray();
    }

    @Test
    @DisplayName("the committed training stream is the generator's output and decodes")
    void training() throws IOException {
        var stream = stream();
        if (Boolean.getBoolean("smalt.dts.writeTraining")) {
            Files.createDirectories(RESOURCE.getParent());
            Files.write(RESOURCE, stream);
            return;
        }
        assertArrayEquals(stream, Files.readAllBytes(RESOURCE));
        var decoder = new DtsDecoder(AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000))
                .sampleRate(48000).build());
        var buffers = new int[1];
        decoder.decode(new Packet(0, stream, 0, 0, 0, true), buffer -> buffers[0]++);
        assertTrue(buffers[0] >= 5, "decoded " + buffers[0] + " frames");
    }
}
