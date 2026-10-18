package com.github.auties00.smalt.container.aiff;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AiffMuxer}: plain AIFF and every AIFF-C compression type read back by
 * {@link AiffDemuxer} and ffprobe, the 80-bit sample rate, streaming headers and invalid input.
 */
@DisplayName("AiffMuxer")
class AiffMuxerTest {
    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(Codec.PCM_S8, "AIFF", "pcm_s8"),
                Arguments.of(Codec.PCM_S16BE, "AIFF", "pcm_s16be"),
                Arguments.of(Codec.PCM_S24BE, "AIFF", "pcm_s24be"),
                Arguments.of(Codec.PCM_S32BE, "AIFF", "pcm_s32be"),
                Arguments.of(Codec.PCM_S16LE, "AIFC", "pcm_s16le"),
                Arguments.of(Codec.PCM_U8, "AIFC", "pcm_u8"),
                Arguments.of(Codec.PCM_F32BE, "AIFC", "pcm_f32be"),
                Arguments.of(Codec.PCM_F64BE, "AIFC", "pcm_f64be"),
                Arguments.of(Codec.PCM_ALAW, "AIFC", "pcm_alaw"),
                Arguments.of(Codec.PCM_MULAW, "AIFC", "pcm_mulaw"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formats")
    @DisplayName("writes a file that AiffDemuxer and ffprobe read back, with the samples intact")
    void roundTrip(Codec codec, String form, String ffmpegName) {
        var input = PcmPackets.tone(codec, 44_100, ChannelLayout.STEREO, 4411);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), AiffMuxer::new);
        assertEquals(form, new String(output, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(output.length - 8, ByteBuffer.wrap(output).getInt(4));
        var result = MuxSupport.demux(output);
        var track = (AudioTrack) result.tracks().getFirst();
        assertEquals(codec, track.codec());
        assertEquals(44_100, track.sampleRate());
        assertEquals(4411, track.duration());
        assertArrayEquals(input.bytes(), PcmPackets.concatenate(result.order()));
        var name = "aiff-" + ffmpegName + ".aiff";
        assertEquals(ffmpegName, ContainerProbe.probe(MuxSupport.save(name, output)).streams().getFirst().codecName());
        MuxSupport.assertFfmpegClean(name, output);
    }

    @ParameterizedTest
    @ValueSource(ints = {8000, 11_025, 22_050, 44_100, 48_000, 96_000, 192_000, 1})
    @DisplayName("encodes integer sample rates as exact 80-bit floats")
    void sampleRates(int rate) {
        var writer = new ByteWriter(10);
        AiffMuxer.writeExtended(writer, rate);
        var bytes = writer.toByteArray();
        var exponent = ((bytes[0] & 0x7F) << 8 | (bytes[1] & 0xFF)) - 16383;
        var mantissa = ByteBuffer.wrap(bytes, 2, 8).getLong();
        assertEquals(rate, mantissa >>> (63 - exponent));
        assertEquals(0, mantissa << (exponent + 1), "no fraction bits");
        var input = PcmPackets.tone(Codec.PCM_S16BE, rate, ChannelLayout.MONO, 16);
        var track = (AudioTrack) MuxSupport.demux(MuxSupport.toMemory(List.of(input.track()), input.packets(), AiffMuxer::new))
                .tracks().getFirst();
        assertEquals(rate, track.sampleRate());
    }

    @Test
    @DisplayName("writes zero sizes to a stream, which AiffDemuxer reads to the end")
    void streaming() {
        var input = PcmPackets.tone(Codec.PCM_S16BE, 22_050, ChannelLayout.STEREO, 3001);
        var output = MuxSupport.toStream(List.of(input.track()), input.packets(), AiffMuxer::new);
        assertEquals(0, ByteBuffer.wrap(output).getInt(4));
        assertArrayEquals(input.bytes(), PcmPackets.concatenate(MuxSupport.demux(output).order()));
    }

    @Test
    @DisplayName("pads odd chunks and accepts a track without packets")
    void padAndEmpty() {
        var input = PcmPackets.tone(Codec.PCM_S8, 8000, ChannelLayout.MONO, 101);
        var output = MuxSupport.toMemory(List.of(input.track()), input.packets(), AiffMuxer::new);
        assertEquals(0, output.length % 2);
        var empty = MuxSupport.toMemory(List.of(input.track()), List.of(), AiffMuxer::new);
        assertTrue(MuxSupport.demux(empty).order().isEmpty());
    }

    @Test
    @DisplayName("rejects little-endian floats, partial frames and a second track")
    void rejections() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiffMuxer(MediaSink.memory()).addTrack(PcmPackets.tone(Codec.PCM_F32LE, 8000, ChannelLayout.MONO, 1).track()));
        var input = PcmPackets.tone(Codec.PCM_S24BE, 8000, ChannelLayout.MONO, 4);
        try (var muxer = new AiffMuxer(MediaSink.memory())) {
            muxer.addTrack(input.track());
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(input.track()));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(0, new byte[4], 0, 0, 1, true)));
        }
    }
}
