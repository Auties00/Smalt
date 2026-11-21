package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the picture memory of the VVC decoding engine: the pictures it keeps for reuse across streams of different
 * formats and the allocation limit.
 */
@DisplayName("VVC decoding engine memory")
class VvcDecoderEngineTest {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    private static byte[] stream(String name) {
        return Fixtures.read(VECTORS.resolve(name + ".vvc"));
    }

    private static void decode(VvcDecoderEngine engine, byte[] stream) {
        engine.beginPacket(Packet.NO_TIMESTAMP, 0);
        NalUnits.forEachAnnexB(stream, engine::decodeNal);
        engine.flush();
    }

    @Test
    @DisplayName("releases the pooled pictures of an earlier format instead of keeping them for the whole stream")
    void poolFollowsFormat() {
        // 4:2:0 8-bit, 4:0:0 10-bit and 4:2:0 10-bit pictures never share a pooled picture
        var names = new String[]{"ld-420-8bit", "intra-400-10bit", "ra-420-10bit"};
        var alone = new int[names.length];
        for (var i = 0; i < names.length; i++) {
            var engine = new VvcDecoderEngine(MediaLimits.defaults(), true, 1, picture -> {
            });
            decode(engine, stream(names[i]));
            alone[i] = engine.pooledPictures();
            assertTrue(alone[i] > 0, names[i] + " leaves no picture to reuse");
        }
        var engine = new VvcDecoderEngine(MediaLimits.defaults(), true, 1, picture -> {
        });
        for (var round = 0; round < 3; round++) {
            for (var i = 0; i < names.length; i++) {
                decode(engine, stream(names[i]));
                assertEquals(alone[i], engine.pooledPictures(), "round " + round + ": " + names[i]);
            }
        }
    }

    @Test
    @DisplayName("rejects a picture whose planes exceed the allocation limit before allocating it")
    void allocationLimit() {
        var limits = MediaLimits.builder().maxBytes(1024).build();
        var engine = new VvcDecoderEngine(limits, false, 1, picture -> {
        });
        engine.beginPacket(Packet.NO_TIMESTAMP, 0);
        assertThrows(MediaException.LimitExceeded.class,
                () -> NalUnits.forEachAnnexB(stream("ld-420-8bit"), engine::decodeNal));
    }
}
