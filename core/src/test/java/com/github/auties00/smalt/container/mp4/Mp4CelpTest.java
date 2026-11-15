package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers MPEG-4 CELP in ISO base media files: the {@code mp4a} sample entry whose {@code esds} names MPEG-4 Audio and
 * carries the CELP {@code AudioSpecificConfig}, written by the muxers and read back as {@link Codec#CELP}.
 */
@DisplayName("MP4 with CELP")
class Mp4CelpTest {
    /**
     * Returns the {@code AudioSpecificConfig} of an 8 kHz base layer, {@code MPE_Configuration} 0.
     */
    private static byte[] config() {
        var writer = new MsbBitWriter();
        writer.write(8, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(0, 3);
        writer.write(0, 5);
        writer.write(0, 3);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private static AudioTrack track(byte[] config) {
        var builder = AudioTrack.builder().id(1).codec(Codec.CELP).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                .layout(ChannelLayout.MONO);
        return config == null ? builder.build() : builder.codecConfig(config).build();
    }

    private void roundTrip(Function<MediaSink, Muxer> factory) {
        var random = new Random(8);
        var packets = new ArrayList<Packet>();
        for (var frame = 0; frame < 40; frame++) {
            var data = new byte[20];
            random.nextBytes(data);
            packets.add(new Packet(1, data, 320L * frame, 320L * frame, 320, true));
        }
        var output = MuxSupport.toMemory(List.<Track>of(track(config())), packets, factory);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(output))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.CELP, read.codec());
            assertEquals(8000, read.sampleRate());
            assertEquals(ChannelLayout.MONO, read.layout().orElseThrow());
            assertArrayEquals(config(), read.codecConfig().orElseThrow());
            for (var i = 0; i < packets.size(); i++) {
                var packet = demuxer.read();
                assertArrayEquals(packets.get(i).data(), packet.data(), "packet " + i);
                assertEquals(320L * i, packet.pts(), "pts " + i);
                assertEquals(320, packet.duration(), "duration " + i);
            }
            assertNull(demuxer.read());
        }
    }

    @Test
    @DisplayName("an MP4 file keeps the track, its configuration and its access units")
    void progressive() {
        roundTrip(Mp4Muxer::new);
    }

    @Test
    @DisplayName("a fragmented MP4 file keeps the track, its configuration and its access units")
    void fragmented() {
        roundTrip(FragmentedMp4Muxer::new);
    }

    @Test
    @DisplayName("every brand but 3GP carries CELP")
    void brands() {
        assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.CELP));
        assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.CELP));
        assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, Codec.CELP));
        assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.THREE_GPP, Codec.CELP));
        assertTrue(FragmentedMp4Muxer.supports(Codec.CELP));
    }

    @Test
    @DisplayName("refuses a track without a configuration or with one of another object type")
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> new Mp4Muxer(MediaSink.memory()).addTrack(track(null)));
        assertThrows(IllegalArgumentException.class,
                () -> new Mp4Muxer(MediaSink.memory()).addTrack(track(new byte[]{0x12, 0x10})));
    }

    @Test
    @DisplayName("the registry names the artifact that decodes CELP")
    void artifact() {
        assertEquals("smalt-mpeg", Artifacts.decoderOf(Codec.CELP));
    }
}
