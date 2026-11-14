package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
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
 * Covers MPEG-4 HVXC in ISO base media files: the {@code mp4a} sample entry whose {@code esds} names MPEG-4 Audio and
 * carries the HVXC {@code AudioSpecificConfig}, written by the muxers and read back as {@link Codec#HVXC}.
 */
@DisplayName("MP4 with HVXC")
class Mp4HvxcTest {
    /**
     * Returns the {@code AudioSpecificConfig} of an HVXC base layer at 2 kbit/s, fixed rate.
     */
    private static byte[] config() {
        var writer = new MsbBitWriter();
        writer.write(9, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 2);
        writer.write(0, 1);
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private static AudioTrack track(byte[] config) {
        var builder = AudioTrack.builder().id(1).codec(Codec.HVXC).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                .layout(ChannelLayout.MONO);
        return config == null ? builder.build() : builder.codecConfig(config).build();
    }

    private void roundTrip(Function<MediaSink, Muxer> factory) {
        var random = new Random(8);
        var packets = new ArrayList<Packet>();
        for (var frame = 0; frame < 40; frame++) {
            var data = new byte[20];
            random.nextBytes(data);
            packets.add(new Packet(1, data, 160L * frame, 160L * frame, 160, true));
        }
        var output = MuxSupport.toMemory(List.<Track>of(track(config())), packets, factory);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(output))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.HVXC, read.codec());
            assertEquals(8000, read.sampleRate());
            assertEquals(ChannelLayout.MONO, read.layout().orElseThrow());
            assertArrayEquals(config(), read.codecConfig().orElseThrow());
            for (var i = 0; i < packets.size(); i++) {
                var packet = demuxer.read();
                assertArrayEquals(packets.get(i).data(), packet.data(), "packet " + i);
                assertEquals(160L * i, packet.pts(), "pts " + i);
                assertEquals(160, packet.duration(), "duration " + i);
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
    @DisplayName("an empty sample whose offset lies past the end of the file reads as empty; a non-empty one fails")
    void samplesPastTheEnd() {
        for (var size : new int[]{0, 10}) {
            var packets = new ArrayList<Packet>();
            for (var frame = 0; frame < 4; frame++) {
                packets.add(new Packet(1, new byte[size], 160L * frame, 160L * frame, 160, true));
            }
            var output = MuxSupport.toMemory(List.<Track>of(track(config())), packets, Mp4Muxer::new);
            // the ISO/IEC 14496-26 er_hv streams place their empty samples past the end of the file
            var moved = MovieBoxes.replace(output, "moov",
                    MovieBoxes.shiftChunkOffsets(MovieBoxes.movie(output), 0, 1 << 20));
            try (var demuxer = new Mp4Demuxer(MediaSource.of(moved))) {
                if (size == 0) {
                    for (var i = 0; i < packets.size(); i++) {
                        var packet = demuxer.read();
                        assertEquals(0, packet.data().length, "packet " + i);
                        assertEquals(160L * i, packet.pts(), "pts " + i);
                    }
                    assertNull(demuxer.read());
                } else {
                    assertThrows(MediaException.Malformed.class, demuxer::read);
                }
            }
        }
    }

    @Test
    @DisplayName("every brand but 3GP carries HVXC")
    void brands() {
        assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.HVXC));
        assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.HVXC));
        assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, Codec.HVXC));
        assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.THREE_GPP, Codec.HVXC));
        assertTrue(FragmentedMp4Muxer.supports(Codec.HVXC));
    }

    @Test
    @DisplayName("refuses a track without a configuration or with one of another object type")
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> new Mp4Muxer(MediaSink.memory()).addTrack(track(null)));
        assertThrows(IllegalArgumentException.class,
                () -> new Mp4Muxer(MediaSink.memory()).addTrack(track(new byte[]{0x12, 0x10})));
    }

    @Test
    @DisplayName("the registry names the artifact that decodes HVXC")
    void artifact() {
        assertEquals("smalt-mpeg", Artifacts.decoderOf(Codec.HVXC));
    }
}
