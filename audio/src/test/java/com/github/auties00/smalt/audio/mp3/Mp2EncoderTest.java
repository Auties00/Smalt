package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.PerceptualDistortion;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link Mp2Encoder} end to end: every stream is decoded back with Smalt's decoder and, with ffmpeg as a
 * black-box oracle, checked with {@code ffmpeg -xerror} (and its CRC check), decoded by ffmpeg to the same
 * samples, and compared in perceptual distortion with ffmpeg's own Layer II encoder at the same bitrate.
 */
class Mp2EncoderTest {
    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(48000, 2, 2, 192, Mp3StereoMode.STEREO, 20.0),
                Arguments.of(44100, 2, 2, 224, Mp3StereoMode.STEREO, 20.0),
                Arguments.of(32000, 1, 2, 64, Mp3StereoMode.STEREO, 18.0),
                Arguments.of(48000, 2, 2, 128, Mp3StereoMode.JOINT_STEREO, 16.0),
                Arguments.of(24000, 2, 2, 96, Mp3StereoMode.JOINT_STEREO, 16.0),
                Arguments.of(22050, 2, 2, 128, Mp3StereoMode.STEREO, 20.0),
                Arguments.of(16000, 1, 2, 48, Mp3StereoMode.STEREO, 18.0),
                Arguments.of(48000, 2, 2, 192, Mp3StereoMode.DUAL_CHANNEL, 20.0),
                Arguments.of(48000, 2, 1, 384, Mp3StereoMode.STEREO, 20.0),
                Arguments.of(44100, 1, 1, 192, Mp3StereoMode.STEREO, 20.0),
                Arguments.of(32000, 2, 1, 256, Mp3StereoMode.JOINT_STEREO, 16.0),
                Arguments.of(24000, 2, 1, 192, Mp3StereoMode.STEREO, 20.0));
    }

    static Mp2EncoderOptions options(int rate, int channels, int layer, int kbps, Mp3StereoMode mode, boolean crc) {
        return Mp2EncoderOptions.builder().format(rate, Mp3Encoding.layout(channels)).layer(layer).bitrate(kbps * 1000)
                .stereoMode(mode).crc(crc).build();
    }

    static List<Packet> encode(Mp2Encoder encoder, float[][] samples, int chunk) {
        var packets = new ArrayList<Packet>();
        var length = samples[0].length;
        var position = 0;
        while (position < length) {
            var count = Math.min(length - position, chunk);
            var planes = new float[samples.length][count];
            for (var c = 0; c < samples.length; c++) {
                System.arraycopy(samples[c], position, planes[c], 0, count);
            }
            encoder.encode(new AudioBuffer(encoder.layout(), encoder.sampleRate(), planes, count), packets::add);
            position += count;
            chunk = Math.min(65536, chunk * 3 + 7);
        }
        encoder.flush(packets::add);
        return packets;
    }

    static List<Packet> encode(Mp2EncoderOptions options, float[][] samples) {
        return encode(new Mp2Encoder(options), samples, 1);
    }

    static Path save(String name, byte[] data) {
        try {
            var file = Files.createTempFile("mp2enc-" + name, ".mp2");
            file.toFile().deleteOnExit();
            Files.write(file, data);
            return file;
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static float[] ffmpegDecode(Path file, int layer, boolean crc) {
        var arguments = new ArrayList<>(List.of("-v", "error", "-xerror"));
        if (crc) {
            arguments.addAll(List.of("-err_detect", "crccheck"));
        }
        arguments.addAll(List.of("-c:a", layer == 1 ? "mp1float" : "mp2float", "-f", "mp3", "-i", file.toString(), "-f",
                "f32le", "-"));
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments);
        var samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    static AudioTrack track(Mp2EncoderOptions options) {
        return AudioTrack.builder().codec(Codec.MP2).timeBase(Rational.timeBaseOf(options.sampleRate()))
                .sampleRate(options.sampleRate()).layout(options.layout()).skipSamples(Mp2Encoder.DELAY).build();
    }

    static int peak(float[] interleaved, int channels) {
        var peak = 0;
        for (var i = 0; i < interleaved.length / channels; i++) {
            if (Math.abs(interleaved[channels * i]) > Math.abs(interleaved[channels * peak])) {
                peak = i;
            }
        }
        return peak;
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrip {
        @ParameterizedTest(name = "{0} Hz, {1} channels, Layer {2}, {3} kbit/s, {4}")
        @MethodSource("com.github.auties00.smalt.audio.mp3.Mp2EncoderTest#formats")
        @DisplayName("decodes with Smalt and ffmpeg alike, CRCs verified, frames sized and timed as the header says")
        void everyFormat(int rate, int channels, int layer, int kbps, Mp3StereoMode mode, double minimumSnr) {
            var options = options(rate, channels, layer, kbps, mode, true);
            var length = rate * 2 + 123;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, channels, rate, length, rate + channels + layer);
            var packets = encode(options, input);
            var frameSize = layer == 1 ? 384 : 1152;
            assertEquals((length + Mp2Encoder.DELAY + frameSize - 1) / frameSize, packets.size());
            long bytes = 0;
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                var header = MpegAudioHeader.read(packet.data(), 0);
                assertTrue(MpegAudioHeader.isValid(header));
                assertEquals(layer, MpegAudioHeader.layer(header));
                assertEquals(rate, MpegAudioHeader.sampleRate(header));
                assertEquals(kbps, MpegAudioHeader.bitrateKbps(header));
                assertEquals(channels, MpegAudioHeader.channels(header));
                assertTrue(MpegAudioHeader.hasCrc(header));
                var expectedMode = channels == 1 ? MpegAudioHeader.MODE_MONO : switch (mode) {
                    case STEREO -> 0;
                    case JOINT_STEREO -> MpegAudioHeader.MODE_JOINT_STEREO;
                    case DUAL_CHANNEL -> 2;
                };
                assertEquals(expectedMode, MpegAudioHeader.mode(header));
                assertEquals(MpegAudioHeader.frameBytes(header, 0) + MpegAudioHeader.padding(header), packet.size());
                assertEquals((long) i * frameSize, packet.pts());
                assertEquals(frameSize, packet.duration());
                bytes += packet.size();
            }
            var exact = (double) packets.size() * frameSize * kbps * 1000 / 8 / rate;
            assertTrue(Math.abs(bytes - exact) <= (layer == 1 ? 4 : 1), bytes + " bytes for " + exact);
            var stream = Mp3Encoding.concatenate(packets);
            var decoder = new Mp3Decoder(track(options), Mp3DecoderOptions.defaults());
            var decoded = AudioPipeline.decode(track(options), packets, decoder);
            assertEquals(0, decoder.concealedFrames(), "a frame failed its CRC");
            assertEquals(packets.size() * frameSize, decoded.frames());
            var snr = Mp3Encoding.snr(input, decoded.interleaved(), Mp2Encoder.DELAY);
            assertTrue(snr >= minimumSnr, "SNR " + snr + " dB below " + minimumSnr);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                // ffmpeg's Layer I CRC check rejects joint stereo frames, ISO/IEC 11172-4 l1-fl2 and l1-fl3 included
                var reference = ffmpegDecode(save("rt", stream), layer, layer == 2 || mode != Mp3StereoMode.JOINT_STEREO);
                var ours = decoded.interleaved();
                var common = Math.min(reference.length, ours.length);
                assertTrue(common >= ours.length - frameSize * channels, "ffmpeg decoded fewer samples");
                assertTrue(Quality.snr(java.util.Arrays.copyOf(reference, common), java.util.Arrays.copyOf(ours, common)) >= 90,
                        "Smalt and ffmpeg decode differently");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Mp3Encoding.Shape.class)
        @DisplayName("codes every signal shape, including silence, full scale and transients")
        void shapes(Mp3Encoding.Shape shape) {
            var options = Mp2EncoderOptions.defaults();
            var input = Mp3Encoding.signal(shape, 2, 48000, 48000 + 17, 3);
            var stream = Mp3Encoding.concatenate(encode(options, input));
            var decoded = Mp3Encoding.decode(stream).interleaved();
            if (shape == Mp3Encoding.Shape.SILENCE) {
                for (var sample : decoded) {
                    assertEquals(0, sample);
                }
            } else if (shape != Mp3Encoding.Shape.NOISE && shape != Mp3Encoding.Shape.IMPULSE) {
                // noise bursts on a quiet tone: ffmpeg's Layer II encoder reaches 4.8 dB on this signal at 192 kbit/s
                var minimum = shape == Mp3Encoding.Shape.TRANSIENTS ? 4 : 10;
                var snr = Mp3Encoding.snr(input, decoded, Mp2Encoder.DELAY);
                assertTrue(snr >= minimum, shape + " SNR " + snr);
            }
            for (var sample : decoded) {
                assertTrue(Float.isFinite(sample) && Math.abs(sample) <= 2, shape + " sample " + sample);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(save(shape.name(), stream));
            }
        }

        @ParameterizedTest(name = "{0} kbit/s")
        @ValueSource(ints = {128, 192, 256})
        @DisplayName("perceptual distortion stays within 0.5 dB of ffmpeg's Layer II encoder at the same bitrate")
        void versusFfmpeg(int kbps) {
            var mode = Mp3StereoMode.STEREO;
            Oracle.assume(Oracle.Tool.FFMPEG);
            var length = 48000 * 4;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 48000, length, 61);
            var ours = Mp3Encoding.decode(Mp3Encoding.concatenate(encode(options(48000, 2, 2, kbps, mode, false), input)))
                    .interleaved();
            var raw = ByteBuffer.allocate(length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < length; i++) {
                raw.putFloat(input[0][i]).putFloat(input[1][i]);
            }
            var source = save("src", raw.array());
            var encoded = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "f32le", "-ar",
                    "48000", "-ac", "2", "-i", source.toString(), "-c:a", "mp2", "-b:a", kbps + "k", "-f", "mp2", "-"));
            var reference = ffmpegDecode(save("ffmpeg", encoded), 2, false);
            var interleaved = Mp3Encoding.interleave(input);
            var oursNmr = PerceptualDistortion.measureInterleaved(interleaved, ours, 2, Mp2Encoder.DELAY, 48000);
            var ffmpegNmr = PerceptualDistortion.measureInterleaved(interleaved, reference, 2, Mp2Encoder.DELAY, 48000);
            System.out.println("[mp2-nmr] " + kbps + " " + mode + ": Smalt " + oursNmr + ", ffmpeg " + ffmpegNmr);
            assertTrue(oursNmr.nmr() <= ffmpegNmr.nmr() + 0.5, oursNmr + " versus " + ffmpegNmr);
        }

        @ParameterizedTest(name = "{0} kbit/s")
        @ValueSource(ints = {96, 112, 128})
        @DisplayName("joint stereo keeps the noise masked in at least as many frames as plain stereo at low bitrates")
        void jointStereoQuality(int kbps) {
            var length = 48000 * 4;
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 48000, length, 67);
            var interleaved = Mp3Encoding.interleave(input);
            var stereo = PerceptualDistortion.measureInterleaved(interleaved, Mp3Encoding.decode(Mp3Encoding.concatenate(
                    encode(options(48000, 2, 2, kbps, Mp3StereoMode.STEREO, false), input))).interleaved(), 2,
                    Mp2Encoder.DELAY, 48000);
            var joint = PerceptualDistortion.measureInterleaved(interleaved, Mp3Encoding.decode(Mp3Encoding.concatenate(
                    encode(options(48000, 2, 2, kbps, Mp3StereoMode.JOINT_STEREO, false), input))).interleaved(), 2,
                    Mp2Encoder.DELAY, 48000);
            System.out.println("[mp2-joint] " + kbps + ": stereo " + stereo + ", joint " + joint);
            assertTrue(joint.disturbedFrames() <= stereo.disturbedFrames(), joint + " versus " + stereo);
            assertTrue(joint.nmr() <= stereo.nmr() + 2, joint + " versus " + stereo);
        }

        @Test
        @DisplayName("joint stereo signals every frame as joint stereo and lowers the bound when bits run short")
        void jointStereoBound() {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 48000, 48000 * 2, 5);
            var low = encode(options(48000, 2, 2, 112, Mp3StereoMode.JOINT_STEREO, false), input);
            var high = encode(options(48000, 2, 2, 384, Mp3StereoMode.JOINT_STEREO, false), input);
            var lowExtension = 0.0;
            for (var packet : low) {
                var header = MpegAudioHeader.read(packet.data(), 0);
                assertEquals(MpegAudioHeader.MODE_JOINT_STEREO, MpegAudioHeader.mode(header));
                lowExtension += MpegAudioHeader.modeExtension(header);
            }
            for (var packet : high) {
                assertEquals(3, MpegAudioHeader.modeExtension(MpegAudioHeader.read(packet.data(), 0)));
            }
            assertTrue(lowExtension / low.size() < 3, "the bound never moved at 112 kbit/s");
        }
    }

    @Nested
    @DisplayName("encoder contract")
    class Contract {
        @Test
        @DisplayName("reports MP2, the frame size of each layer, the filterbank delay and no codec configuration")
        void properties() {
            var encoder = new Mp2Encoder();
            assertEquals(Codec.MP2, encoder.codec());
            assertEquals(48000, encoder.sampleRate());
            assertEquals(ChannelLayout.STEREO, encoder.layout());
            assertEquals(1152, encoder.frameSize());
            assertEquals(481, encoder.delay());
            assertTrue(encoder.codecConfig().isEmpty());
            assertEquals(384, new Mp2Encoder(Mp2EncoderOptions.builder().layer(1).build()).frameSize());
            assertEquals(192_000, Mp2EncoderOptions.defaults().effectiveBitrate());
            assertEquals(224_000, Mp2EncoderOptions.forVideoCd().effectiveBitrate());
            assertEquals(44_100, Mp2EncoderOptions.forVideoCd().sampleRate());
        }

        @ParameterizedTest(name = "Layer {0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("the decoded output is the input delayed by exactly delay() samples")
        void delay(int layer) {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.IMPULSE, 1, 48000, 20000, 0);
            var options = Mp2EncoderOptions.builder().format(48000, ChannelLayout.MONO).layer(layer).bitrate(192_000).build();
            var decoded = Mp3Encoding.decode(Mp3Encoding.concatenate(encode(options, input))).interleaved();
            assertEquals(20000 / 3 + Mp2Encoder.DELAY, peak(decoded, 1), 1);
        }

        @Test
        @DisplayName("produces the same bytes whatever the input chunking, and on a second run")
        void deterministic() {
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 48000, 48000 * 2, 29);
            var options = options(48000, 2, 2, 128, Mp3StereoMode.JOINT_STEREO, true);
            var reference = Mp3Encoding.concatenate(encode(new Mp2Encoder(options), input, 48000 * 2));
            for (var chunk : new int[]{1, 383, 1152, 1153}) {
                assertArrayEquals(reference, Mp3Encoding.concatenate(encode(new Mp2Encoder(options), input, chunk)));
            }
        }

        @Test
        @DisplayName("codes NaN as silence and clips samples beyond full scale")
        void nanAndClipping() {
            var length = 48000;
            var nan = new float[2][length];
            for (var plane : nan) {
                java.util.Arrays.fill(plane, Float.NaN);
            }
            for (var sample : Mp3Encoding.decode(Mp3Encoding.concatenate(encode(Mp2EncoderOptions.defaults(), nan)))
                    .interleaved()) {
                assertEquals(0, sample);
            }
            var loud = Mp3Encoding.signal(Mp3Encoding.Shape.FULL_SCALE, 2, 48000, length, 1);
            for (var plane : loud) {
                for (var i = 0; i < length; i++) {
                    plane[i] *= 4;
                }
            }
            var clipped = Mp3Encoding.signal(Mp3Encoding.Shape.FULL_SCALE, 2, 48000, length, 1);
            assertArrayEquals(Mp3Encoding.concatenate(encode(Mp2EncoderOptions.defaults(), clipped)),
                    Mp3Encoding.concatenate(encode(Mp2EncoderOptions.defaults(), loud)));
        }

        @Test
        @DisplayName("flush without input emits nothing, and input after a flush is rejected")
        void flushContract() {
            var encoder = new Mp2Encoder();
            var packets = new ArrayList<Packet>();
            encoder.flush(packets::add);
            assertTrue(packets.isEmpty());
            assertThrows(IllegalStateException.class, () -> encoder.encode(
                    new AudioBuffer(ChannelLayout.STEREO, 48000, new float[2][10], 10), packets::add));
            assertThrows(IllegalArgumentException.class, () -> new Mp2Encoder().encode(
                    new AudioBuffer(ChannelLayout.MONO, 48000, new float[1][10], 10), packets::add));
        }

        @Test
        @DisplayName("rejects rates, layers and bitrates the format cannot code")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().format(8000, ChannelLayout.MONO).build());
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().format(48000,
                    ChannelLayout.SURROUND_5_1).build());
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().layer(3).build());
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().bitrate(100_000).build());
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().bitrate(56_000).build());
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().format(48000, ChannelLayout.MONO)
                    .bitrate(256_000).build());
            assertThrows(IllegalArgumentException.class, () -> Mp2EncoderOptions.builder().format(24000, ChannelLayout.STEREO)
                    .bitrate(192_000).build());
            assertEquals(56_000, Mp2EncoderOptions.builder().format(48000, ChannelLayout.MONO).bitrate(56_000).build()
                    .effectiveBitrate());
            assertEquals(448_000, Mp2EncoderOptions.builder().layer(1).bitrate(448_000).build().effectiveBitrate());
            var options = Mp2EncoderOptions.forVideoCd();
            assertEquals(options, options.toBuilder().build());
            assertEquals(Mp2EncoderOptions.of(48000, ChannelLayout.STEREO), Mp2EncoderOptions.defaults());
            assertTrue(Mp2EncoderOptions.defaults().toBuilder().bitrate(256_000).automaticBitrate().build().bitrate().isEmpty());
        }

        @Test
        @DisplayName("AudioEncoders opens a Layer II encoder at the nearest rate and allowed bitrate")
        void registry() {
            var encoder = AudioEncoders.create(Codec.MP2, AudioEncoderOptions.builder().sampleRate(44000)
                    .layout(ChannelLayout.STEREO).bitrate(100_000).build());
            var mp2 = assertInstanceOf(Mp2Encoder.class, encoder);
            assertEquals(44100, mp2.sampleRate());
            assertEquals(96_000, mp2.options().effectiveBitrate());
            assertEquals(Mp3StereoMode.JOINT_STEREO, mp2.options().stereoMode());
            var mono = (Mp2Encoder) AudioEncoders.create(Codec.MP2, AudioEncoderOptions.builder().sampleRate(48000)
                    .layout(ChannelLayout.MONO).bitrate(320_000).build());
            assertEquals(192_000, mono.options().effectiveBitrate());
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        @Test
        @DisplayName("an .mp2 file reads back as an MP2 track of the exact frame count")
        void rawFile() {
            var options = Mp2EncoderOptions.forVideoCd();
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 44100, 44100 * 2, 11);
            var packets = encode(options, input);
            var sink = MediaSink.memory();
            try (var muxer = new RawMuxer(sink)) {
                muxer.addTrack(track(options));
                for (var packet : packets) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var file = sink.toByteArray();
            assertArrayEquals(Mp3Encoding.concatenate(packets), file);
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(file))) {
                var track = AudioPipeline.firstAudio(demuxer);
                assertEquals(Codec.MP2, track.codec());
                assertEquals((long) packets.size() * 1152, track.duration());
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(save("vcd", file));
            }
        }

        @Test
        @DisplayName("an MPEG-TS stream of Layer II audio, as DVB carries it, decodes in ffmpeg to Smalt's samples")
        void transportStream() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var options = Mp2EncoderOptions.builder().bitrate(256_000).crc(true).build();
            var input = Mp3Encoding.signal(Mp3Encoding.Shape.MUSIC, 2, 48000, 48000 * 2, 13);
            var packets = encode(options, input);
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink)) {
                muxer.addTrack(track(options));
                for (var packet : packets) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var path = save("dvb", sink.toByteArray());
            Oracle.assertDecodesCleanly(path);
            var reference = Oracle.decodeAudio(path, List.of("-c:a", "mp2float"));
            var ours = Mp3Encoding.decode(Mp3Encoding.concatenate(packets)).interleaved();
            var common = Math.min(reference.length, ours.length);
            assertTrue(common >= ours.length - 1152 * 2);
            assertTrue(Quality.snr(java.util.Arrays.copyOf(ours, common), java.util.Arrays.copyOf(reference, common)) >= 90);
        }
    }
}
