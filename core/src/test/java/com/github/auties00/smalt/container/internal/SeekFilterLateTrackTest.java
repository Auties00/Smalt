package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SeekFilter#addTrack}: a track added before its first packet restarts at its last key frame at or
 * before the target as a track given at construction does, one added after the margin resolved every track restarts
 * at its next key frame past the target, one added after the end passes unfiltered, and an identifier already
 * filtered is refused. Times are in milliseconds.
 */
@DisplayName("SeekFilter tracks added after construction")
class SeekFilterLateTrackTest {
    static final Rational MILLIS = new Rational(1, 1000);

    static AudioTrack track(int id) {
        return AudioTrack.builder().id(id).codec(Codec.OPUS).timeBase(MILLIS).sampleRate(48_000)
                .layout(ChannelLayout.defaultFor(1)).build();
    }

    static Packet packet(int trackId, long millis, boolean key) {
        return new Packet(trackId, new byte[]{1}, millis, millis, 100, key);
    }

    static List<String> drain(SeekFilter filter) {
        var released = new ArrayList<String>();
        for (var packet = filter.poll(); packet != null; packet = filter.poll()) {
            released.add(packet.trackId() + "@" + packet.pts());
        }
        return released;
    }

    @Test
    @DisplayName("restarts a track added before its first packet at its last key frame at or before the target")
    void beforeFirstPacket() {
        var filter = new SeekFilter(List.of(track(1)), 1_000_000);
        filter.offer(packet(1, 900, true));
        filter.addTrack(track(2));
        filter.offer(packet(2, 300, true));
        filter.offer(packet(2, 800, false));
        filter.offer(packet(2, 950, true));
        filter.offer(packet(1, 1000, true));
        filter.offer(packet(2, 1050, false));
        filter.offer(packet(1, 1100, true));
        filter.finish();
        var released = drain(filter);
        // 1@1000 is a later key frame at the target, so it replaces 1@900
        assertEquals(List.of("2@950", "1@1000", "2@1050", "1@1100"), released);
        assertTrue(filter.isTransparent());
    }

    @Test
    @DisplayName("restarts a track added after the margin resolved every track at its next key frame past the target")
    void afterMargin() {
        var filter = new SeekFilter(List.of(track(1)), 1_000_000);
        filter.offer(packet(1, 900, true));
        filter.offer(packet(1, 3_500, true));
        filter.addTrack(track(2));
        filter.offer(packet(2, 500, true));
        filter.offer(packet(2, 1_200, false));
        filter.offer(packet(2, 1_300, true));
        filter.finish();
        assertEquals(List.of("1@900", "1@3500", "2@1300"), drain(filter));
    }

    @Test
    @DisplayName("passes a track added after the end of the input and refuses an identifier it already filters")
    void afterFinishAndDuplicates() {
        var filter = new SeekFilter(List.of(track(1)), 1_000_000);
        assertThrows(IllegalArgumentException.class, () -> filter.addTrack(track(1)));
        filter.finish();
        filter.addTrack(track(2));
        filter.offer(packet(2, 10, false));
        assertEquals(List.of("2@10"), drain(filter));
    }
}
