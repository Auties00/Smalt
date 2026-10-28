package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DiscardedFrames}: the frames of discarded packets are dropped once, and a hidden frame that decodes
 * to nothing does not take the frame shown after it at the same timestamp.
 */
@DisplayName("DiscardedFrames")
class DiscardedFramesTest {
    private static Packet packet(long pts, boolean discard) {
        return new Packet(1, new byte[1], pts, pts, 1, discard ? Set.of(PacketFlag.DISCARD) : Set.of(),
                PacketSideData.NONE);
    }

    @Test
    @DisplayName("drops the frame of a discarded packet once")
    void dropsOnce() {
        var discarded = new DiscardedFrames();
        discarded.note(packet(-10, true));
        discarded.note(packet(0, false));
        assertTrue(discarded.drop(-10));
        assertFalse(discarded.drop(-10));
        assertFalse(discarded.drop(0));
    }

    @Test
    @DisplayName("a shown packet with the timestamp of a hidden one keeps its frame")
    void hiddenReference() {
        var discarded = new DiscardedFrames();
        discarded.note(packet(300, true));
        discarded.note(packet(300, false));
        assertFalse(discarded.drop(300));
        discarded.note(packet(600, true));
        discarded.note(packet(600, true));
        discarded.note(packet(600, false));
        assertTrue(discarded.drop(600));
        assertFalse(discarded.drop(600));
    }

    @Test
    @DisplayName("forgets every timestamp on clear and ignores packets without one")
    void clears() {
        var discarded = new DiscardedFrames();
        for (var index = 0; index < 20; index++) {
            discarded.note(packet(index, true));
        }
        discarded.note(packet(Packet.NO_TIMESTAMP, true));
        assertTrue(discarded.drop(19));
        discarded.clear();
        assertFalse(discarded.drop(3));
        assertFalse(discarded.drop(Packet.NO_TIMESTAMP));
    }
}
