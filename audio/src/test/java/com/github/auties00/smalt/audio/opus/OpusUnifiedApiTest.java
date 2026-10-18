package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers how the buffering and the per-packet halves of {@link OpusEncoder} and {@link OpusDecoder}
 * share one coding state: identical packets and samples through either entry point, timestamps
 * across both, the guards between them, decoders created without a track, decoding a stereo track
 * to mono, and the limits and multistream restrictions.
 */
class OpusUnifiedApiTest {
    private static final int RATE = 16000;
    private static final int FRAME = 320;

    private static float[] speech(int frames) {
        var pcm = OpusCallGoldenTest.input("speech16k");
        var out = new float[frames];
        for (var i = 0; i < frames; i++) {
            out[i] = pcm[i] / 32768.0f;
        }
        return out;
    }

    private static List<byte[]> encodeFrames(OpusEncoder encoder, float[] input, int frameSize) {
        var packets = new ArrayList<byte[]>();
        var packet = new byte[OpusEncoder.maxPacketBytes()];
        for (var position = 0; position + frameSize <= input.length; position += frameSize) {
            var length = encoder.encode(input, position, frameSize, packet, 0, packet.length);
            packets.add(Arrays.copyOf(packet, length));
        }
        return packets;
    }

    private static AudioTrack track(byte[] head) {
        return AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).codecConfig(head)
                .sampleRate(48000)
                .build();
    }

    private static float[] decodeTrack(OpusDecoder decoder, List<byte[]> packets) {
        var out = new float[0];
        for (var data : packets) {
            var collected = new float[1][];
            decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> collected[0] = Arrays.copyOf(buffer.plane(0), buffer.frames()));
            if (collected[0] != null) {
                var grown = Arrays.copyOf(out, out.length + collected[0].length);
                System.arraycopy(collected[0], 0, grown, out.length, collected[0].length);
                out = grown;
            }
        }
        return out;
    }

    @Nested
    @DisplayName("encoder")
    class Encoder {
        @Test
        @DisplayName("codes the same packets through the buffering and the per-packet methods")
        void samePackets() {
            var input = speech(50 * FRAME);
            var perPacket = encodeFrames(new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1)), input, FRAME);
            var buffered = new ArrayList<Packet>();
            var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
            encoder.encode(new AudioBuffer(ChannelLayout.MONO, RATE, new float[][]{input}, input.length), buffered::add);
            assertEquals(perPacket.size(), buffered.size());
            for (var i = 0; i < perPacket.size(); i++) {
                assertArrayEquals(perPacket.get(i), buffered.get(i).data(), "packet " + i);
            }
        }

        @Test
        @DisplayName("timestamps buffered packets after the per-packet frames")
        void timestamps() {
            var input = speech(5 * FRAME);
            var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
            encodeFrames(encoder, Arrays.copyOf(input, 3 * FRAME), FRAME);
            var packets = new ArrayList<Packet>();
            var tail = Arrays.copyOfRange(input, 3 * FRAME, 5 * FRAME);
            encoder.encode(new AudioBuffer(ChannelLayout.MONO, RATE, new float[][]{tail}, tail.length), packets::add);
            assertEquals(List.of(3L * FRAME, 4L * FRAME), packets.stream().map(Packet::pts).toList());
        }

        @Test
        @DisplayName("refuses per-packet calls while buffered input waits and after a flush")
        void guards() {
            var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
            var packet = new byte[OpusEncoder.maxPacketBytes()];
            encoder.encode(new AudioBuffer(ChannelLayout.MONO, RATE, new float[][]{new float[100]}, 100), p -> {
            });
            assertThrows(IllegalStateException.class, () -> encoder.encode(new short[FRAME], 0, FRAME, packet, 0, packet.length));
            encoder.reset();
            assertTrue(encoder.encode(new short[FRAME], 0, FRAME, packet, 0, packet.length) > 0);
            encoder.flush(p -> {
            });
            assertThrows(IllegalStateException.class, () -> encoder.encode(new short[FRAME], 0, FRAME, packet, 0, packet.length));
        }

        @Test
        @DisplayName("changes live controls without changing its options")
        void liveControls() {
            var options = OpusEncoderOptions.forVoip(RATE, 1);
            var encoder = new OpusEncoder(options);
            encoder.setComplexity(3);
            encoder.setBitrate(12_000);
            assertEquals(3, encoder.complexity());
            assertEquals(12_000, encoder.bitrate());
            assertEquals(options, encoder.options());
            assertEquals(9, encoder.options().complexity());
            encoder.setMaximumBitrate();
            encoder.setAutomaticBitrate();
            assertEquals(40_000, encoder.bitrate());
        }

        @Test
        @DisplayName("emits packets of one size at a constant bitrate")
        void constantBitrate() {
            var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
            encoder.setBitrate(24_000);
            encoder.setBitrateMode(OpusBitrateMode.CBR);
            var sizes = encodeFrames(encoder, speech(20 * FRAME), FRAME).stream().map(p -> p.length).distinct().toList();
            assertEquals(List.of(60), sizes);
        }

        @Test
        @DisplayName("forces and frees the coded channel count and bandwidth")
        void forcedControls() {
            var encoder = new OpusEncoder(OpusEncoderOptions.forRealtime());
            assertThrows(IllegalArgumentException.class, () -> encoder.setForcedChannels(3));
            encoder.setForcedChannels(1);
            encoder.setBandwidth(OpusBandwidth.WIDEBAND);
            var packet = new byte[OpusEncoder.maxPacketBytes()];
            var length = encoder.encode(new float[2 * 960], 0, 960, packet, 0, packet.length);
            assertEquals(1, OpusPackets.channels(packet, 0));
            assertEquals(OpusBandwidth.WIDEBAND, OpusPackets.bandwidth(packet, 0));
            assertTrue(length > 0);
            encoder.setAutomaticChannels();
            encoder.setAutomaticBandwidth();
            var mono = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
            assertThrows(IllegalArgumentException.class, () -> mono.setForcedChannels(2));
        }
    }

    @Nested
    @DisplayName("decoder")
    class Decoder {
        @Test
        @DisplayName("decodes the same samples through a track-less packet decode and the per-packet methods")
        void trackless() {
            var packets = encodeFrames(new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1)), speech(30 * FRAME), FRAME);
            var planar = decodeTrack(new OpusDecoder(OpusDecoderOptions.of(RATE, 1)), packets);
            var perPacket = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var interleaved = new float[planar.length];
            var position = 0;
            for (var p : packets) {
                position += perPacket.decode(p, 0, p.length, interleaved, position, FRAME);
            }
            assertEquals(planar.length, position);
            assertArrayEquals(planar, interleaved);
            var stereo = new OpusDecoder(OpusDecoderOptions.defaults());
            assertEquals(ChannelLayout.STEREO, stereo.layout());
            assertEquals(48000, stereo.sampleRate());
        }

        @Test
        @DisplayName("decodes a stereo track to mono natively when the options ask for one channel")
        void stereoToMono() {
            var input = new float[2][48000];
            for (var i = 0; i < input[0].length; i++) {
                input[0][i] = (float) (0.3 * Math.sin(i * 0.05));
                input[1][i] = (float) (0.3 * Math.sin(i * 0.11));
            }
            var encoder = new OpusEncoder(OpusEncoderOptions.defaults().toBuilder().bitrate(96_000).build());
            var packets = new ArrayList<byte[]>();
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, input, input[0].length), p -> packets.add(p.data()));
            encoder.flush(p -> packets.add(p.data()));
            var head = encoder.codecConfig().orElseThrow();
            var mono = new OpusDecoder(track(head), OpusDecoderOptions.defaults().toBuilder().channels(1).build());
            assertEquals(ChannelLayout.MONO, mono.layout());
            var decoded = decodeTrack(mono, packets);
            var reference = decodeTrack(new OpusDecoder(OpusDecoderOptions.of(48000, 1)), packets);
            var preSkip = OpusHead.parse(head, MediaLimits.DEFAULT).preSkip();
            assertArrayEquals(Arrays.copyOfRange(reference, preSkip, reference.length), decoded);
        }

        @Test
        @DisplayName("keeps multistream tracks to their own layout and to packet decoding")
        void multistream() {
            var head = new OpusHead(1, 3, 0, 48000, 0, 255, 2, 0, new int[]{1, 255, 0}).toBytes();
            var decoder = new OpusDecoder(track(head));
            assertThrows(IllegalStateException.class, () -> decoder.conceal(new float[960 * 3], 0, 960));
            assertThrows(MediaException.Unsupported.class,
                    () -> new OpusDecoder(track(head), OpusDecoderOptions.defaults().toBuilder().channels(1).build()));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> new OpusDecoder(track(head), OpusDecoderOptions.defaults().toBuilder()
                            .limits(MediaLimits.builder().maxChannels(2).build()).build()));
        }

        @Test
        @DisplayName("refuses decoding after close")
        void closed() {
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            decoder.close();
            assertThrows(IllegalStateException.class, () -> decoder.conceal(new short[FRAME], 0, FRAME));
            assertThrows(IllegalStateException.class, () -> decoder.decode(new Packet(0, new byte[0], 0, 0, 0, true), b -> {
            }));
        }
    }
}
