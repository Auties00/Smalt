package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link WavMuxer}: every sample format and channel layout read back by
 * {@link WavDemuxer} and ffprobe, the extensible and fact chunks, RF64 (with a lowered threshold
 * so small files reach it), streaming headers, pad bytes and invalid input.
 */
@DisplayName("WavMuxer")
class WavMuxerTest {
    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(Codec.PCM_U8, ChannelLayout.MONO, "pcm_u8", 1, 16),
                Arguments.of(Codec.PCM_S16LE, ChannelLayout.STEREO, "pcm_s16le", 1, 16),
                Arguments.of(Codec.PCM_S16LE, ChannelLayout.SURROUND_5_1, "pcm_s16le", 0xFFFE, 40),
                Arguments.of(Codec.PCM_S24LE, ChannelLayout.STEREO, "pcm_s24le", 0xFFFE, 40),
                Arguments.of(Codec.PCM_S32LE, ChannelLayout.MONO, "pcm_s32le", 0xFFFE, 40),
                Arguments.of(Codec.PCM_F32LE, ChannelLayout.STEREO, "pcm_f32le", 3, 18),
                Arguments.of(Codec.PCM_F64LE, ChannelLayout.SURROUND_7_1, "pcm_f64le", 0xFFFE, 40),
                Arguments.of(Codec.PCM_ALAW, ChannelLayout.MONO, "pcm_alaw", 6, 18),
                Arguments.of(Codec.PCM_MULAW, ChannelLayout.STEREO, "pcm_mulaw", 7, 18));
    }

    @ParameterizedTest(name = "{0} {2} channels {1}")
    @MethodSource("formats")
    @DisplayName("writes a header that WavDemuxer and ffprobe read back, with the samples intact")
    void roundTrip(Codec codec, ChannelLayout layout, String ffmpegName, int tag, int formatSize) {
        var input = PcmPackets.tone(codec, 44_100, layout, 4410);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), WavMuxer::new);
        var header = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(output, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(output.length - 8, header.getInt(4));
        assertEquals("JUNK", new String(output, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(formatSize, header.getInt(52));
        assertEquals(tag, header.getShort(56) & 0xFFFF);
        var result = MuxSupport.demux(output);
        var track = (AudioTrack) result.tracks().getFirst();
        assertEquals(codec, track.codec());
        assertEquals(44_100, track.sampleRate());
        assertEquals(layout.channels(), track.layout().orElseThrow().channels());
        if (tag == 0xFFFE) {
            assertEquals(layout, track.layout().orElseThrow());
        }
        assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
        var probe = ContainerProbe.probe(MuxSupport.save("wav-" + ffmpegName + "-" + layout.channels() + ".wav", output));
        assertEquals(ffmpegName, probe.streams().getFirst().codecName());
        assertEquals(String.valueOf(layout.channels()), probe.streams().getFirst().get("channels"));
        MuxSupport.assertFfmpegClean("wav-" + ffmpegName + "-" + layout.channels() + ".wav", output);
    }

    @Test
    @DisplayName("writes the fact sample count of float files")
    void fact() {
        var input = PcmPackets.tone(Codec.PCM_F32LE, 8000, ChannelLayout.MONO, 1234);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), WavMuxer::new);
        var text = new String(output, StandardCharsets.ISO_8859_1);
        var fact = text.indexOf("fact");
        assertTrue(fact > 0);
        assertEquals(1234, ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getInt(fact + 8));
    }

    @Nested
    @DisplayName("large files")
    class Large {
        @Test
        @DisplayName("turns the JUNK chunk into ds64 once the RIFF size passes the limit")
        void autoRf64() {
            var input = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 48_000);
            var sink = MediaSink.memory();
            MuxSupport.mux(List.of(input.track()), input.packets(), new WavMuxer(sink, WavMuxerOptions.defaults(), 1000));
            var output = sink.toByteArray();
            assertEquals("RF64", new String(output, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("ds64", new String(output, 12, 4, StandardCharsets.US_ASCII));
            var header = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(output.length - 8, header.getLong(20));
            assertEquals(48_000 * 4, header.getLong(28));
            assertEquals(48_000, header.getLong(36));
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(MuxSupport.demux(output).order()));
            MuxSupport.assertFfmpegClean("rf64-auto.wav", output);
        }

        @Test
        @DisplayName("always writes RF64 when asked, and rejects RF64 on a stream")
        void alwaysRf64() {
            var input = PcmPackets.tone(Codec.PCM_S24LE, 48_000, ChannelLayout.MONO, 1000);
            var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), sink -> new WavMuxer(sink, WavMuxerOptions.rf64()));
            assertEquals("RF64", new String(output, 0, 4, StandardCharsets.US_ASCII));
            assertArrayEquals(input.bytes(), PcmPackets.concatenate(MuxSupport.demux(output).order()));
            MuxSupport.assertFfmpegClean("rf64-always.wav", output);
            assertThrows(MediaException.Unsupported.class,
                    () -> new WavMuxer(MediaSink.of(new ByteArrayOutputStream()), WavMuxerOptions.rf64()));
        }

        @Test
        @DisplayName("refuses to grow past 4 GiB without RF64")
        void neverRf64() {
            var input = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.MONO, 4000);
            try (var muxer = new WavMuxer(MediaSink.memory(), WavMuxerOptions.builder()
                    .rf64Policy(WavMuxerOptions.Rf64.NEVER).build(), 3000)) {
                muxer.addTrack(input.track());
                muxer.writePacket(input.packets().getFirst());
                assertThrows(IllegalStateException.class, () -> muxer.writePacket(input.packets().get(1)));
            }
        }
    }

    @Test
    @DisplayName("writes unknown sizes to a stream, which WavDemuxer and ffmpeg read to the end")
    void streaming() {
        var input = PcmPackets.tone(Codec.PCM_S16LE, 22_050, ChannelLayout.STEREO, 5000);
        var output = MuxSupport.toStream(List.of(input.track()), input.packets(), WavMuxer::new);
        var header = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-1, header.getInt(4));
        assertEquals(-1, header.getInt(40));
        assertArrayEquals(input.bytes(), PcmPackets.concatenate(MuxSupport.demux(output).order()));
        // ffmpeg flags the short last read of an unknown-size data chunk, its own piped WAV included,
        // so check the decoded length instead of decoding with -xerror
        assertEquals(5000 * 2, com.github.auties00.smalt.testkit.Oracle.decodeAudio(MuxSupport.save("stream.wav", output)).length);
    }

    @Test
    @DisplayName("pads an odd data chunk and writes an empty file for a track without packets")
    void padAndEmpty() {
        var input = PcmPackets.tone(Codec.PCM_U8, 8000, ChannelLayout.MONO, 1001);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), WavMuxer::new);
        assertEquals(0, output.length % 2);
        assertEquals(1001, ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getInt(output.length - 1001 - 1 - 4));
        var empty = MuxSupport.toMemory(List.of(input.track()), List.of(), WavMuxer::new);
        assertTrue(MuxSupport.demux(empty).order().isEmpty());
        MuxSupport.assertFfmpegClean("empty.wav", empty);
    }

    @Test
    @DisplayName("writes a zero channel mask for unpositioned or reordered layouts")
    void channelMask() {
        assertEquals(0x3F, com.github.auties00.smalt.container.internal.WaveFormat.channelMask(ChannelLayout.SURROUND_5_1));
        assertEquals(0, com.github.auties00.smalt.container.internal.WaveFormat.channelMask(new ChannelLayout(List.of(Speaker.FRONT_RIGHT, Speaker.FRONT_LEFT))));
        assertEquals(0, com.github.auties00.smalt.container.internal.WaveFormat.channelMask(ChannelLayout.defaultFor(12)));
    }

    @Test
    @DisplayName("rejects big-endian samples, partial frames, a second track and late tracks")
    void rejections() {
        assertThrows(IllegalArgumentException.class,
                () -> new WavMuxer(MediaSink.memory()).addTrack(PcmPackets.tone(Codec.PCM_S16BE, 8000, ChannelLayout.MONO, 1).track()));
        var input = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.STEREO, 10);
        try (var muxer = new WavMuxer(MediaSink.memory())) {
            muxer.addTrack(input.track());
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(input.track()));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, new byte[3], 0, 0, 1, true)));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(9, new byte[4], 0, 0, 1, true)));
            muxer.finish();
            assertThrows(IllegalStateException.class, muxer::finish);
        }
    }
}
