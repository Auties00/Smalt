package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link PcmEncoder}: exact round trips through {@link PcmDecoder} for every PCM format,
 * clipping, G.711 code tables, packetization of arbitrary input blocks, the registry, and
 * agreement with ffmpeg's float-to-integer conversion.
 */
@DisplayName("PcmEncoder")
class PcmEncoderTest {
    private static int bits(Codec codec) {
        return PcmDecoder.bytesPerSample(codec) * 8;
    }

    /** Builds samples that sit exactly on the codec's quantization grid. */
    private static float[][] gridSamples(Codec codec, int channels, int frames, long seed) {
        var random = new Random(seed);
        var planes = new float[channels][frames];
        for (var plane : planes) {
            for (var index = 0; index < frames; index++) {
                plane[index] = switch (codec) {
                    case PCM_F32LE, PCM_F32BE, PCM_F64LE, PCM_F64BE -> random.nextFloat() * 2 - 1;
                    case PCM_ALAW, PCM_MULAW -> 0;
                    case PCM_S32LE, PCM_S32BE, PCM_S64LE, PCM_S64BE -> (random.nextInt(1 << 24) - (1 << 23)) * 0x1p-23f;
                    default -> {
                        var scale = 1 << (bits(codec) - 1);
                        yield (random.nextInt(2 * scale) - scale) / (float) scale;
                    }
                };
            }
            plane[0] = -1f;
        }
        return planes;
    }

    private static List<Packet> encode(PcmEncoder encoder, float[][] planes, int frames, int... blocks) {
        var packets = new ArrayList<Packet>();
        var layout = encoder.layout();
        var offset = 0;
        var block = 0;
        while (offset < frames) {
            var count = Math.min(blocks[block++ % blocks.length], frames - offset);
            var slice = new float[planes.length][count];
            for (var channel = 0; channel < planes.length; channel++) {
                System.arraycopy(planes[channel], offset, slice[channel], 0, count);
            }
            encoder.encode(new AudioBuffer(layout, encoder.sampleRate(), slice, count), packets::add);
            offset += count;
        }
        encoder.flush(packets::add);
        return packets;
    }

    @ParameterizedTest
    @EnumSource(value = Codec.class, names = {"PCM_.*", "^(?!PCM_ALAW|PCM_MULAW).*"}, mode = EnumSource.Mode.MATCH_ALL)
    @DisplayName("round-trips samples on the format's grid exactly through PcmDecoder")
    void roundTrip(Codec codec) {
        var layout = ChannelLayout.STEREO;
        var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, 44_100, layout).toBuilder().framesPerPacket(300).build());
        var planes = gridSamples(codec, 2, 1000, codec.ordinal());
        var packets = encode(encoder, planes, 1000, 7, 450, 1, 128);
        assertEquals(4, packets.size());
        var decoder = new PcmDecoder(AudioTrack.builder()
                .id(0)
                .codec(codec)
                .timeBase(Rational.timeBaseOf(44_100))
                .duration(-1)
                .sampleRate(44_100)
                .layout(layout)
                .bitsPerSample(bits(codec))
                .skipSamples(0)
                .build());
        var decoded = new float[2][1000];
        var position = new int[1];
        var expectedPts = 0L;
        for (var packet : packets) {
            assertEquals(expectedPts, packet.pts());
            assertTrue(packet.keyframe());
            expectedPts += packet.duration();
            decoder.decode(packet, buffer -> {
                for (var channel = 0; channel < 2; channel++) {
                    System.arraycopy(buffer.plane(channel), 0, decoded[channel], position[0], buffer.frames());
                }
                position[0] += buffer.frames();
            });
        }
        assertEquals(1000, expectedPts);
        assertEquals(1000, position[0]);
        for (var channel = 0; channel < 2; channel++) {
            assertArrayEquals(planes[channel], decoded[channel], codec + " channel " + channel);
        }
    }

    @Test
    @DisplayName("maps every G.711 code back to itself")
    void g711Codes() {
        for (var code = 0; code < 256; code++) {
            var alaw = new PcmDecoder(track(Codec.PCM_ALAW));
            var mulaw = new PcmDecoder(track(Codec.PCM_MULAW));
            var aValue = decodeOne(alaw, (byte) code);
            var muValue = decodeOne(mulaw, (byte) code);
            assertEquals(code, PcmEncoder.alaw(Math.round(aValue * 32768)), "A-law code " + code);
            // mu-law has two zeros: 0x7F (negative zero) encodes back as 0xFF
            assertEquals(code == 0x7F ? 0xFF : code, PcmEncoder.mulaw(Math.round(muValue * 32768)), "mu-law code " + code);
        }
    }

    private static AudioTrack track(Codec codec) {
        return AudioTrack.builder()
                .id(0)
                .codec(codec)
                .timeBase(Rational.timeBaseOf(8000))
                .duration(-1)
                .sampleRate(8000)
                .layout(ChannelLayout.MONO)
                .bitsPerSample(8)
                .skipSamples(0)
                .build();
    }

    private static float decodeOne(PcmDecoder decoder, byte code) {
        var value = new float[1];
        decoder.decode(new Packet(0, new byte[]{code}, 0, 0, 1, true), buffer -> value[0] = buffer.plane(0)[0]);
        return value[0];
    }

    @Nested
    @DisplayName("clipping")
    class Clipping {
        @ParameterizedTest
        @EnumSource(value = Codec.class, names = {"PCM_U8", "PCM_S16LE", "PCM_S24BE", "PCM_S32LE", "PCM_F32LE", "PCM_F64BE"})
        @DisplayName("clips out-of-range samples and encodes NaN as zero")
        void clip(Codec codec) {
            var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, 8000, ChannelLayout.MONO));
            var packets = encode(encoder, new float[][]{{1.5f, -7f, Float.NaN, Float.POSITIVE_INFINITY}}, 4, 4);
            var decoder = new PcmDecoder(AudioTrack.builder()
                    .id(0)
                    .codec(codec)
                    .timeBase(Rational.timeBaseOf(8000))
                    .duration(-1)
                    .sampleRate(8000)
                    .layout(ChannelLayout.MONO)
                    .bitsPerSample(bits(codec))
                    .skipSamples(0)
                    .build());
            var out = new float[4];
            decoder.decode(packets.getFirst(), buffer -> System.arraycopy(buffer.plane(0), 0, out, 0, 4));
            var top = codec.name().startsWith("PCM_F") ? 1f : 1f - (float) Math.pow(2, -(bits(codec) - 1));
            assertEquals(top, out[0], 1e-9);
            assertEquals(-1f, out[1]);
            assertEquals(0f, out[2]);
            assertEquals(top, out[3], 1e-9);
        }
    }

    @Test
    @DisplayName("is found by AudioEncoders for every PCM codec and rejects mismatched input")
    void registry() {
        for (var codec : Codec.values()) {
            if (codec.isPcm()) {
                assertTrue(AudioEncoders.isSupported(codec), codec.name());
            }
        }
        var encoder = AudioEncoders.create(Codec.PCM_S24LE, AudioEncoderOptions.of(96_000, ChannelLayout.SURROUND_5_1));
        var pcm = assertInstanceOf(PcmEncoder.class, encoder);
        assertEquals(24, pcm.bitsPerSample());
        assertEquals(0, pcm.delay());
        assertTrue(pcm.codecConfig().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 96_000, 16), packet -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> PcmEncoderOptions.of(Codec.AAC, 48_000, ChannelLayout.MONO));
        assertThrows(IllegalArgumentException.class, () -> PcmEncoderOptions.builder().framesPerPacket(0).build());
    }

    @Test
    @DisplayName("quantizes floats to 16 bits like ffmpeg, within one code")
    void matchesFfmpeg() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var random = new Random(7);
        var samples = new float[48_000];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = (float) (Math.sin(index * 0.05) * 0.9 + random.nextGaussian() * 0.01);
        }
        var floats = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var sample : samples) {
            floats.putFloat(sample);
        }
        var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), floats.array(), List.of("-v", "error", "-f", "f32le",
                "-ar", "48000", "-ac", "1", "-i", "-", "-f", "s16le", "-"));
        var encoder = new PcmEncoder(PcmEncoderOptions.of(Codec.PCM_S16LE, 48_000, ChannelLayout.MONO));
        var output = new ByteArrayOutputStream();
        for (var packet : encode(encoder, new float[][]{samples}, samples.length, 4096)) {
            output.writeBytes(packet.data());
        }
        var ours = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        var theirs = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(reference.length, output.size());
        for (var index = 0; index < samples.length; index++) {
            assertTrue(Math.abs(ours.getShort(index * 2) - theirs.getShort(index * 2)) <= 1, "sample " + index);
        }
    }
}
