package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the key-frame flags of H.263 elementary streams, on picture headers written bit by bit
 * (ITU-T H.263 clause 5.1).
 */
@DisplayName("Mpeg4ElementaryStream")
class Mpeg4ElementaryStreamTest {
    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int count;

        Bits put(int value, int width) {
            for (var bit = width - 1; bit >= 0; bit--) {
                current = current << 1 | (value >>> bit) & 1;
                if (++count == 8) {
                    out.write(current);
                    current = 0;
                    count = 0;
                }
            }
            return this;
        }

        byte[] picture() {
            while (count != 0) {
                put(1, 1);
            }
            // Non-zero filler so that no start code appears inside the picture.
            for (var i = 0; i < 16; i++) {
                out.write(0xA5);
            }
            var bytes = out.toByteArray();
            out.reset();
            return bytes;
        }
    }

    private static byte[] baseline(int reference, boolean intra) {
        return new Bits().put(0x20, 22).put(reference, 8)
                .put(0b10, 2).put(0, 3).put(0b010, 3)
                .put(intra ? 0 : 1, 1).put(0, 4).put(8, 5).put(0, 1)
                .picture();
    }

    private static byte[] extended(int reference, boolean fullUpdate, boolean intra) {
        var bits = new Bits().put(0x20, 22).put(reference, 8)
                .put(0b10, 2).put(0, 3).put(0b111, 3)
                .put(fullUpdate ? 1 : 0, 3);
        if (fullUpdate) {
            bits.put(0b010, 3).put(0, 1).put(0, 10).put(1, 1).put(0, 3);
        }
        return bits.put(intra ? 0 : 1, 3).put(0, 3).put(0, 2).put(1, 1)
                .put(0, 1).put(8, 5).put(0, 1)
                .picture();
    }

    private static List<Boolean> keyFlags(byte[]... pictures) {
        var stream = new ByteArrayOutputStream();
        for (var picture : pictures) {
            stream.writeBytes(picture);
        }
        var flags = new ArrayList<Boolean>();
        try (var source = MediaSource.of(stream.toByteArray());
             var elementary = Mpeg4ElementaryStream.open(source)) {
            for (Packet packet; (packet = elementary.read()) != null; ) {
                flags.add(packet.keyframe());
            }
        }
        return flags;
    }

    @Test
    @DisplayName("flags baseline intra pictures from PTYPE")
    void baselineKeyFrames() {
        assertEquals(List.of(true, false, true),
                keyFlags(baseline(0, true), baseline(1, false), baseline(2, true)));
    }

    @Test
    @DisplayName("flags PLUSPTYPE intra pictures from the MPPTYPE picture type, with and without OPPTYPE")
    void plusPtypeKeyFrames() {
        assertEquals(List.of(true, false, true, false),
                keyFlags(extended(0, true, true), extended(1, false, false),
                        extended(2, false, true), extended(3, true, false)));
    }
}
