package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the public Opus API without external tools: configuration validation, the encoder's
 * FIFO, timestamps, padding and OpusHead, the decoder's pre-skip, rate conversion, packet
 * inspection and multistream channel routing.
 */
class OpusApiTest {
    static float[] tone(int rate, int channels, int frames, double frequency) {
        var out = new float[channels * frames];
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                out[i * channels + c] = (float) (0.4 * StrictMath.sin(2 * Math.PI * (frequency * (c + 1)) * i / rate));
            }
        }
        return out;
    }

    static List<Packet> encode(OpusEncoderOptions config, float[] interleaved, int chunk) {
        var encoder = new OpusEncoder(config);
        var channels = config.channels();
        var frames = interleaved.length / channels;
        var packets = new ArrayList<Packet>();
        for (var position = 0; position < frames; position += chunk) {
            var count = Math.min(chunk, frames - position);
            var planes = new float[channels][count];
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < count; i++) {
                    planes[c][i] = interleaved[(position + i) * channels + c];
                }
            }
            encoder.encode(new AudioBuffer(encoder.layout(), config.sampleRate(), planes, count), packets::add);
        }
        encoder.flush(packets::add);
        return packets;
    }

    static AudioTrack track(byte[] head) {
        return AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).codecConfig(head)
                .sampleRate(48000)
                .build();
    }

    static float[][] decode(byte[] head, List<byte[]> packets, int rate) {
        var planes = new ArrayList<float[]>();
        var total = new int[1];
        try (var decoder = new OpusDecoder(track(head), OpusDecoderOptions.defaults().toBuilder().sampleRate(rate)
                .build())) {
            var channels = decoder.layout().channels();
            for (var c = 0; c < channels; c++) {
                planes.add(new float[0]);
            }
            for (var data : packets) {
                decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                    for (var c = 0; c < buffer.channels(); c++) {
                        var old = planes.get(c);
                        var grown = java.util.Arrays.copyOf(old, total[0] + buffer.frames());
                        System.arraycopy(buffer.plane(c), 0, grown, total[0], buffer.frames());
                        planes.set(c, grown);
                    }
                    total[0] += buffer.frames();
                });
            }
        }
        return planes.toArray(float[][]::new);
    }

    @Nested
    @DisplayName("OpusEncoderOptions")
    class Config {
        @Test
        @DisplayName("forSpeech(bitrate) is 16 kHz mono VoIP, unconstrained VBR, complexity 10, 20 ms")
        void forSpeech() {
            var config = OpusEncoderOptions.forSpeech(16000);
            assertEquals(OpusEncoderOptions.defaults().toBuilder().format(16000, 1).application(OpusApplication.VOIP)
                    .bitrate(16000).build(), config);
            assertEquals(320, config.frameSize());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"rate", "channels", "bitrate low", "bitrate high", "complexity", "frame"})
        @DisplayName("rejects out-of-range values")
        void rejects(String field) {
            assertThrows(IllegalArgumentException.class, () -> {
                switch (field) {
                    case "rate" -> OpusEncoderOptions.builder().format(44100, 2).build();
                    case "channels" -> OpusEncoderOptions.builder().format(48000, 3).build();
                    case "bitrate low" -> OpusEncoderOptions.builder().bitrate(499).build();
                    case "bitrate high" -> OpusEncoderOptions.builder().bitrate(600_001).build();
                    case "complexity" -> OpusEncoderOptions.builder().complexity(11).build();
                    default -> OpusEncoderOptions.builder().frameDurationMicros(30000).build();
                }
            });
        }

        @Test
        @DisplayName("rejects null enums")
        void nulls() {
            assertThrows(NullPointerException.class, () -> OpusEncoderOptions.builder().application(null));
        }
    }

    @Nested
    @DisplayName("OpusEncoder")
    class Encoder {
        @Test
        @DisplayName("describes the stream: OpusHead, frame size, look-ahead, layout")
        void describes() {
            var encoder = new OpusEncoder(OpusEncoderOptions.forSpeech(16000));
            assertEquals(Codec.OPUS, encoder.codec());
            assertEquals(320, encoder.frameSize());
            assertEquals(104, encoder.delay());
            assertEquals(ChannelLayout.MONO, encoder.layout());
            var head = OpusHead.parse(encoder.codecConfig().orElseThrow(), MediaLimits.DEFAULT);
            assertEquals(1, head.version());
            assertEquals(1, head.channels());
            assertEquals(312, head.preSkip());
            assertEquals(16000, head.inputSampleRate());
            assertEquals(0, head.outputGain());
            assertEquals(0, head.mappingFamily());
            var lowDelay = new OpusEncoder(OpusEncoderOptions.defaults().toBuilder()
                    .application(OpusApplication.RESTRICTED_LOWDELAY)
                    .bitrate(64000).bitrateMode(OpusBitrateMode.CBR).complexity(5).frameDurationMicros(10000).build());
            assertEquals(120, lowDelay.delay());
            assertEquals(120, OpusHead.parse(lowDelay.codecConfig().orElseThrow(), MediaLimits.DEFAULT).preSkip());
        }

        @ParameterizedTest(name = "chunks of {0}")
        @ValueSource(ints = {1, 7, 320, 1000, 16000})
        @DisplayName("produces the same packets for any input chunking, with sample timestamps and padding")
        void fifo(int chunk) {
            var input = tone(16000, 1, 16000, 440);
            var reference = encode(OpusEncoderOptions.forSpeech(16000), input, 16000);
            var packets = encode(OpusEncoderOptions.forSpeech(16000), input, chunk);
            assertEquals(reference.size(), packets.size());
            assertEquals((16000 + 104 + 319) / 320, packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(reference.get(i).data(), packets.get(i).data());
                assertEquals(i * 320L, packets.get(i).pts());
                assertEquals(320, packets.get(i).duration());
                assertTrue(packets.get(i).keyframe());
            }
        }

        @Test
        @DisplayName("clips out-of-range samples and codes NaN as silence")
        void clips() {
            var loud = new float[4800];
            var clipped = new float[4800];
            var nan = new float[4800];
            for (var i = 0; i < loud.length; i++) {
                var v = (float) StrictMath.sin(i * 0.03);
                loud[i] = 3 * v;
                clipped[i] = Math.max(-1, Math.min(1, 3 * v));
                nan[i] = i % 7 == 0 ? Float.NaN : 0;
            }
            var config = OpusEncoderOptions.defaults().toBuilder().format(48000, 1).bitrate(64000).build();
            var a = encode(config, loud, 960);
            var b = encode(config, clipped, 960);
            for (var i = 0; i < a.size(); i++) {
                assertArrayEquals(b.get(i).data(), a.get(i).data());
            }
            var c = encode(config, nan, 960);
            var d = encode(config, new float[4800], 960);
            for (var i = 0; i < c.size(); i++) {
                assertArrayEquals(d.get(i).data(), c.get(i).data());
            }
        }

        @Test
        @DisplayName("rejects blocks of another rate or layout, and input after flush")
        void rejects() {
            var encoder = new OpusEncoder(OpusEncoderOptions.forSpeech(16000));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 48000, 10), p -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 16000, 10), p -> {
            }));
            encoder.flush(p -> {
            });
            assertThrows(IllegalStateException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 16000, 10), p -> {
            }));
        }

        @Test
        @DisplayName("emits nothing for an empty stream")
        void empty() {
            var packets = new ArrayList<Packet>();
            var encoder = new OpusEncoder(OpusEncoderOptions.forSpeech(16000));
            encoder.flush(packets::add);
            encoder.flush(packets::add);
            assertTrue(packets.isEmpty());
        }
    }

    @Nested
    @DisplayName("OpusDecoder")
    class Decoder {
        @ParameterizedTest(name = "{0} Hz output")
        @CsvSource({"48000", "24000", "16000", "12000", "8000"})
        @DisplayName("drops the pre-skip and round-trips a tone aligned with the input")
        void roundTrip(int outputRate) {
            var config = OpusEncoderOptions.defaults().toBuilder().bitrate(128000).build();
            var input = tone(48000, 2, 48000, 500);
            var packets = encode(config, input, 4096);
            var head = new OpusEncoder(config).codecConfig().orElseThrow();
            var decoded = decode(head, packets.stream().map(Packet::data).toList(), outputRate);
            var scale = 48000 / outputRate;
            assertEquals(packets.size() * 960 / scale - 312 / scale, decoded[0].length);
            if (outputRate == 48000) {
                var left = new float[48000];
                for (var i = 0; i < left.length; i++) {
                    left[i] = input[2 * i];
                }
                var snr = com.github.auties00.smalt.testkit.Quality.snr(left, java.util.Arrays.copyOf(decoded[0], 48000));
                assertTrue(snr > 15, "SNR " + snr);
            }
        }

        @Test
        @DisplayName("decodes a mono track without OpusHead from the declared layout")
        void headless() {
            var packets = encode(OpusEncoderOptions.forSpeech(16000), tone(16000, 1, 3200, 300), 3200);
            var track = AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.MONO)
                    .build();
            try (var decoder = new OpusDecoder(track)) {
                var frames = new int[1];
                for (var packet : packets) {
                    decoder.decode(packet, buffer -> frames[0] += buffer.frames());
                }
                assertEquals(packets.size() * 960, frames[0]);
            }
            var noLayout = track(null);
            assertThrows(MediaException.Malformed.class, () -> new OpusDecoder(noLayout));
        }

        @Test
        @DisplayName("rejects a non-Opus track and unsupported output rates")
        void rejects() {
            var vorbis = AudioTrack.builder().codec(Codec.VORBIS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new OpusDecoder(vorbis));
            assertThrows(IllegalArgumentException.class, () -> OpusDecoderOptions.defaults().toBuilder()
                    .sampleRate(44100).build());
        }

        @Test
        @DisplayName("routes a two-stream family 255 packet to its mapped channels")
        void multistream() {
            var config = OpusEncoderOptions.defaults().toBuilder().format(48000, 1).bitrate(64000).build();
            var low = encode(config, tone(48000, 1, 9600, 300), 9600);
            var high = encode(config, tone(48000, 1, 9600, 2000), 9600);
            var packets = new ArrayList<byte[]>();
            for (var i = 0; i < low.size(); i++) {
                var first = low.get(i).data();
                var second = high.get(i).data();
                var payload = first.length - 1;
                var sizeBytes = payload < 252 ? 1 : 2;
                var packet = new byte[1 + sizeBytes + payload + second.length];
                packet[0] = first[0];
                if (payload < 252) {
                    packet[1] = (byte) payload;
                } else {
                    packet[1] = (byte) (252 + (payload & 3));
                    packet[2] = (byte) ((payload - (252 + (payload & 3))) >> 2);
                }
                System.arraycopy(first, 1, packet, 1 + sizeBytes, payload);
                System.arraycopy(second, 0, packet, 1 + sizeBytes + payload, second.length);
                packets.add(packet);
            }
            var head = new OpusHead(1, 3, 0, 48000, 0, 255, 2, 0, new int[]{1, 255, 0}).toBytes();
            var decoded = decode(head, packets, 48000);
            var monoHead = OpusHead.family0(1, 0, 48000).toBytes();
            var expectedLow = decode(monoHead, low.stream().map(Packet::data).toList(), 48000)[0];
            var expectedHigh = decode(monoHead, high.stream().map(Packet::data).toList(), 48000)[0];
            assertArrayEquals(expectedHigh, decoded[0]);
            assertArrayEquals(new float[decoded[1].length], decoded[1]);
            assertArrayEquals(expectedLow, decoded[2]);
            try (var decoder = new OpusDecoder(track(head))) {
                assertEquals(List.of(Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN), decoder.layout().speakers());
            }
        }

        @Test
        @DisplayName("counts packet samples from the table of contents")
        void packetSamples() {
            assertEquals(960, OpusPackets.samples(new byte[]{(byte) 0xF8, 1, 2}, 0, 3, 48000));
            assertEquals(320, OpusPackets.samples(new byte[]{(byte) 0x48, 1}, 0, 2, 16000));
            assertEquals(5760, OpusPackets.samples(new byte[]{0x1B, 2}, 0, 2, 48000));
            assertThrows(IllegalArgumentException.class, () -> OpusPackets.samples(new byte[0], 0, 0, 48000));
            assertThrows(MediaException.Malformed.class, () -> OpusPackets.samples(new byte[]{0x1B, 3}, 0, 2, 48000));
            assertThrows(MediaException.Malformed.class, () -> OpusPackets.samples(new byte[]{0x0B}, 0, 1, 48000));
        }

        @Test
        @DisplayName("applies the OpusHead output gain")
        void gain() {
            var config = OpusEncoderOptions.defaults().toBuilder().format(48000, 1).bitrate(96000).build();
            var packets = encode(config, tone(48000, 1, 9600, 440), 9600).stream().map(Packet::data).toList();
            var plain = decode(OpusHead.family0(1, 0, 48000).toBytes(), packets, 48000)[0];
            var boosted = decode(new OpusHead(1, 1, 0, 48000, 6 * 256, 0, 1, 0, new int[]{0}).toBytes(), packets, 48000)[0];
            var ratio = boosted[4800] / plain[4800];
            assertEquals(Math.pow(10, 6 / 20.0), ratio, 1e-3);
        }
    }
}
