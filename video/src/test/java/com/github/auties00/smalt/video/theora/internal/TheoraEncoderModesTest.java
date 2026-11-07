package com.github.auties00.smalt.video.theora.internal;

import com.github.auties00.smalt.image.ColorInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the macro block modes and coded block flags of the pinned encoder streams ({@code smalt-*.packets}, see
 * {@code TheoraEncoderReferenceTest}) back from {@link TheoraFrameDecoder}, so that a change that stops the encoder
 * from using a mode, or from leaving unchanged blocks uncoded, fails here.
 */
@DisplayName("Theora encoder macro block modes")
class TheoraEncoderModesTest {
    private static final List<String> STREAMS = List.of("420", "420-odd", "422", "444");

    private static List<byte[]> packets(String name) {
        try (var in = TheoraEncoderModesTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/video/theora/smalt-" + name + ".packets")) {
            if (in == null) {
                throw new IllegalStateException("missing stream " + name);
            }
            var buffer = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            var packets = new ArrayList<byte[]>();
            while (buffer.hasRemaining()) {
                var packet = new byte[buffer.getInt()];
                buffer.get(packet);
                packets.add(packet);
            }
            return packets;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    @DisplayName("use every macro block mode, partially coded frames and both reference frames")
    void modes() {
        var counts = new long[8];
        var partialFrames = 0;
        for (var name : STREAMS) {
            var packets = packets(name);
            var setup = new TheoraSetup(packets.get(0), packets.get(2));
            var decoder = new TheoraFrameDecoder(setup, ColorInfo.BT601_LIMITED);
            for (var packet : packets.subList(3, packets.size())) {
                decoder.advance(packet);
                if (decoder.keyframe() || decoder.codedTotal == 0) {
                    continue;
                }
                if (decoder.codedTotal < decoder.fragCount) {
                    partialFrames++;
                }
                for (var mbi = 0; mbi < decoder.mbCount; mbi++) {
                    var mode = decoder.mbModes[mbi];
                    if (mode >= 0) {
                        counts[mode]++;
                    }
                }
            }
        }
        var histogram = Arrays.toString(counts);
        for (var mode = 0; mode < 8; mode++) {
            assertTrue(counts[mode] > 0, "mode " + mode + " unused: " + histogram);
        }
        assertTrue(partialFrames > 0, "every inter frame codes every fragment");
    }
}
