package com.github.auties00.smalt.audio.gsm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.telephony.TelephonyStreams;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the GSM 06.10 decoder and encoder against libgsm: the files in
 * {@code src/test/resources/com/github/auties00/smalt/audio/gsm} were coded by libgsm from {@code speech8k.raw} in
 * both byte layouts (see the README there), so the encoder must reproduce their bytes and the decoder libgsm's
 * decode of them, pinned as a SHA-256 digest; the RPE-LTP arithmetic itself is checked by
 * {@link GsmConformanceTest} against the ETSI sequences.
 */
@DisplayName("GSM 06.10 decoder and encoder")
class GsmCodecTest {
    /** libgsm's decode of both reference files, as 16-bit little-endian samples. */
    private static final String LIBGSM_DECODE = "a67f5461d2eb8cd2f8012bef6494d9dc101b73d6b1d8c85360f80cbcdece4f40";

    private static byte[] resource(String name) {
        try (var in = GsmCodecTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int[][] speech() {
        var bytes = resource("speech8k.raw");
        var samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        var plane = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            plane[i] = samples[i];
        }
        return new int[][]{plane};
    }

    private static AudioTrack track(Codec codec) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                .layout(ChannelLayout.MONO).build();
    }

    private static List<Packet> frames(byte[] raw) {
        var packets = new ArrayList<Packet>();
        for (var offset = 0; offset < raw.length; offset += GsmFrames.FRAME_BYTES) {
            packets.add(new Packet(1, Arrays.copyOfRange(raw, offset, offset + GsmFrames.FRAME_BYTES),
                    offset / GsmFrames.FRAME_BYTES * 160L, offset / GsmFrames.FRAME_BYTES * 160L, 160, true));
        }
        return packets;
    }

    private static byte[] concat(List<Packet> packets) {
        var out = new java.io.ByteArrayOutputStream();
        for (var packet : packets) {
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("decodes libgsm's 33-byte frames to libgsm's samples")
    void decodesFrames() {
        var decoder = AudioDecoders.create(track(Codec.GSM));
        assertInstanceOf(GsmDecoder.class, decoder);
        var samples = TelephonyStreams.decodeShorts(decoder, frames(resource("libgsm.gsm")));
        assertEquals(16000, samples.length);
        assertEquals(LIBGSM_DECODE, TelephonyStreams.sha256(samples));
    }

    @Test
    @DisplayName("decodes libgsm's Microsoft blocks in WAVE to libgsm's samples")
    void decodesBlocks() {
        var stream = TelephonyStreams.demux(resource("libgsm-ms.wav"));
        assertEquals(Codec.GSM_MS, stream.track().codec());
        var samples = TelephonyStreams.decodeShorts(AudioDecoders.create(stream.track()), stream.packets());
        assertEquals(LIBGSM_DECODE, TelephonyStreams.sha256(samples));
    }

    @Test
    @DisplayName("codes the input to libgsm's 33-byte frames, byte for byte")
    void encodesFrames() {
        var packets = TelephonyStreams.encode(new GsmEncoder(Codec.GSM), speech());
        assertEquals(100, packets.size());
        assertArrayEquals(resource("libgsm.gsm"), concat(packets));
        for (var index = 0; index < packets.size(); index++) {
            assertEquals(160L * index, packets.get(index).pts());
            assertEquals(160, packets.get(index).duration());
        }
    }

    @Test
    @DisplayName("codes the input to libgsm's Microsoft blocks, byte for byte")
    void encodesBlocks() {
        var packets = TelephonyStreams.encode(new GsmEncoder(Codec.GSM_MS), speech());
        var reference = TelephonyStreams.demux(resource("libgsm-ms.wav"));
        assertEquals(concat(reference.packets()).length, concat(packets).length);
        assertArrayEquals(concat(reference.packets()), concat(packets));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"GSM", "GSM_MS"})
    @DisplayName("a partial final packet is padded and keeps its input duration")
    void partialPacket(Codec codec) {
        var encoder = (GsmEncoder) AudioEncoders.create(codec, AudioEncoderOptions.of(8000, ChannelLayout.MONO));
        var input = new int[][]{Arrays.copyOf(speech()[0], 1000)};
        var packets = TelephonyStreams.encode(encoder, input);
        var unit = encoder.frameSize();
        assertEquals((1000 + unit - 1) / unit, packets.size());
        assertEquals(1000 % unit, packets.getLast().duration());
        var decoded = TelephonyStreams.decodeShorts(new GsmDecoder(track(codec)), packets);
        assertEquals(packets.size() * unit, decoded.length);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"GSM", "GSM_MS"})
    @DisplayName("a round trip through WAV or AIFF-C keeps every packet")
    void containers(Codec codec) {
        var encoder = new GsmEncoder(codec);
        var packets = TelephonyStreams.encode(encoder, speech());
        var format = codec == Codec.GSM ? ContainerFormat.AIFF : ContainerFormat.WAV;
        var file = TelephonyStreams.mux(format, TelephonyStreams.track(encoder), packets);
        var back = TelephonyStreams.demux(file);
        assertEquals(codec, back.track().codec());
        assertArrayEquals(concat(packets), concat(back.packets()));
        assertEquals(16000, back.track().duration());
    }

    @Test
    @DisplayName("the encoder homing frame resets the encoder, so the next frame codes as from the start")
    void homing() {
        var plane = speech()[0];
        var input = new int[160 * 3];
        System.arraycopy(plane, 0, input, 0, 160);
        Arrays.fill(input, 160, 320, 8);
        System.arraycopy(plane, 0, input, 320, 160);
        var packets = TelephonyStreams.encode(new GsmEncoder(Codec.GSM), new int[][]{input});
        assertArrayEquals(packets.get(0).data(), packets.get(2).data());
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("a frame without its signature")
        void signature() {
            var frame = new byte[33];
            assertThrows(MediaException.Malformed.class, () -> new GsmDecoder(track(Codec.GSM))
                    .decode(new Packet(1, frame, 0, 0, 160, true), block -> {
                    }));
        }

        @Test
        @DisplayName("a packet of partial frames or blocks")
        void partial() {
            assertThrows(MediaException.Malformed.class, () -> new GsmDecoder(track(Codec.GSM))
                    .decode(new Packet(1, new byte[40], 0, 0, 160, true), block -> {
                    }));
            assertThrows(MediaException.Malformed.class, () -> new GsmDecoder(track(Codec.GSM_MS))
                    .decode(new Packet(1, new byte[64], 0, 0, 320, true), block -> {
                    }));
        }

        @Test
        @DisplayName("several channels")
        void channels() {
            var stereo = track(Codec.GSM).toBuilder().layout(ChannelLayout.STEREO).build();
            assertThrows(MediaException.Unsupported.class, () -> new GsmDecoder(stereo));
        }
    }

    @Test
    @DisplayName("a lost span is produced exactly")
    void concealment() {
        var decoder = new GsmDecoder(track(Codec.GSM));
        TelephonyStreams.decodeFloats(decoder, frames(resource("libgsm.gsm")).subList(0, 10));
        var produced = new int[1];
        assertTrue(decoder.supportsConcealment());
        assertEquals(500, decoder.conceal(500, (AudioBuffer block) -> produced[0] += block.frames()));
        assertEquals(500, produced[0]);
    }
}
