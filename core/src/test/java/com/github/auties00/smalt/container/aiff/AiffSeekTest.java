package com.github.auties00.smalt.container.aiff;

import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AiffDemuxer seeking")
class AiffSeekTest {
    @Test
    @DisplayName("restarts at the sample frame displayed at the requested time and ends past the end")
    void seeks() {
        var frames = 16_000;
        var sound = ByteBuffer.allocate(frames * 2);
        for (var i = 0; i < frames; i++) {
            sound.putShort((short) i);
        }
        var bytes = AiffDemuxerTest.aiff(false, null, 1, frames, 16, AiffDemuxerTest.extended(16_000), sound.array(),
                8 + frames * 2L);
        try (var demuxer = new AiffDemuxer(MediaSource.of(bytes))) {
            assertTrue(demuxer.isSeekable());
            demuxer.seek(500_000);
            var packet = demuxer.read();
            assertEquals(8000, packet.pts());
            assertEquals(8000, ByteBuffer.wrap(packet.data()).getShort());
            demuxer.seek(1_000_000);
            assertNull(demuxer.read());
            demuxer.seek(0);
            assertEquals(0, demuxer.read().pts());
        }
    }
}
