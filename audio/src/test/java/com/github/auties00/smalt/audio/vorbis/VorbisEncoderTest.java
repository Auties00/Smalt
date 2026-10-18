package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.PerceptualDistortion;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link VorbisEncoder}: bit-exactness against libvorbis 1.3.7 (golden digests), round
 * trips through Smalt's decoder and, when installed, ffmpeg ({@code -xerror}), Ogg and Matroska
 * carriage with exact lengths, and the encoder contract.
 */
class VorbisEncoderTest {
    static Path save(String name, String suffix, byte[] data) {
        try {
            var file = Files.createTempFile("vorbisenc-" + name, suffix);
            file.toFile().deleteOnExit();
            Files.write(file, data);
            return file;
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static VorbisEncoder encoder(int rate, int channels, float quality) {
        return new VorbisEncoder(VorbisEncoderOptions.builder().format(rate, VorbisEncoding.layout(channels)).quality(quality).build());
    }

    /** Decodes an Ogg file with Smalt, trimmed by the granule positions. */
    static OggVorbis.Decoded decodeOgg(byte[] file) {
        var links = OggVorbis.read(file);
        assertEquals(1, links.size());
        return OggVorbis.decode(links.getFirst());
    }

    @Nested
    @DisplayName("libvorbis equivalence")
    class Golden {
        // Digests of the identification header, setup header and audio packets libvorbis 1.3.7
        // (built with an IEEE round-half-even rint) produces from the same samples fed in
        // 1024-sample chunks through vorbis_encode_init_vbr.
        @ParameterizedTest(name = "{0} {1} ch {2} Hz q{4}")
        @CsvSource({
                "MUSIC, 2, 44100, 132300, 4, 1, 4aba3732e1c120f20f48c095fac6c99daf4bf26e7112d270061d4e5908c84cd3",
                "TRANSIENTS, 2, 48000, 96000, 6, 2, 0b32aa010e2c71ef69e5f7721a85bdde98d5b6e3ba45fa1e27192bd22fa43474",
                "MUSIC, 1, 22050, 44100, -1, 3, bea856c6824966c00ace9c633cc3d486763cb68b1c18a54aa53d4a7c66cc17dc",
                "NOISE, 1, 8000, 16000, 0, 4, 64fbc68cc1cadfe59a50ed2b2f9f43697a48603feb2650679e1ab7735872c964",
                "MUSIC, 2, 96000, 96000, 3, 5, c752900da0b9535331fa80cfd1f703c77a630bb8ade26e84f2d5c696bc98a0c4",
                "SINE, 1, 32000, 40000, 10, 6, 21eda1314616846352f5d1bdaa7cea336884c4f7d2091c47c77df7da0ad4ec99"
        })
        @DisplayName("produces libvorbis's packets byte for byte")
        void matchesLibvorbis(VorbisEncoding.Shape shape, int channels, int rate, int length, float quality, long seed, String digest) {
            var encoder = encoder(rate, channels, quality);
            var input = VorbisEncoding.signal(shape, channels, rate, length, seed);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            assertEquals(digest, VorbisEncoding.digest(encoder.codecConfig().orElseThrow(), packets));
        }
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(44100, 2, 3f), Arguments.of(48000, 2, 5f), Arguments.of(44100, 1, 0f),
                Arguments.of(32000, 2, 2f), Arguments.of(22050, 2, 4f), Arguments.of(16000, 1, 1f),
                Arguments.of(11025, 2, 6f), Arguments.of(8000, 1, -1f), Arguments.of(96000, 2, 8f),
                Arguments.of(44100, 6, 4f), Arguments.of(48000, 3, 3f), Arguments.of(44100, 2, 10f));
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrip {
        @ParameterizedTest(name = "{0} Hz, {1} channels, quality {2}")
        @MethodSource("com.github.auties00.smalt.audio.vorbis.VorbisEncoderTest#formats")
        @DisplayName("an Ogg file decodes to the exact input length with Smalt and ffmpeg alike")
        void everyFormat(int rate, int channels, float quality) {
            var encoder = encoder(rate, channels, quality);
            var length = rate * 2 + 123;
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.MUSIC, channels, rate, length, rate + channels);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            var previous = 0L;
            for (var packet : packets) {
                assertEquals(previous, packet.pts());
                previous = packet.pts() + packet.duration();
            }
            assertEquals(length, previous);
            var file = VorbisEncoding.ogg(encoder, packets, length);
            var decoded = decodeOgg(file);
            assertEquals(length, decoded.frames());
            // the 5.1 setting codes the LFE channel (WAVE index 3) band-limited, so it is left out
            var measured = channels == 6 ? new float[][]{input[0], input[1], input[2], input[4], input[5]} : input;
            var output = decoded.planes();
            var compared = channels == 6 ? new float[][]{output[0], output[1], output[2], output[4], output[5]} : output;
            var snr = VorbisEncoding.snr(measured, compared);
            var minimum = quality >= 4 ? 15 : quality >= 0 ? 8 : 5;
            assertTrue(snr >= minimum, "SNR " + snr + " dB below " + minimum);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = save("rt", ".ogg", file);
                Oracle.assertDecodesCleanly(path);
                var reference = Oracle.decodeAudio(path);
                assertEquals(length * channels, reference.length);
                assertTrue(Quality.snr(reference, decoded.interleaved()) >= 90, "Smalt and ffmpeg decode differently");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(VorbisEncoding.Shape.class)
        @DisplayName("codes every signal shape, including silence, full scale and transients")
        void shapes(VorbisEncoding.Shape shape) {
            var encoder = new VorbisEncoder();
            var length = 44100 + 17;
            var input = VorbisEncoding.signal(shape, 2, 44100, length, 3);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            var file = VorbisEncoding.ogg(encoder, packets, length);
            var decoded = decodeOgg(file);
            assertEquals(length, decoded.frames());
            if (shape == VorbisEncoding.Shape.SILENCE) {
                for (var sample : decoded.interleaved()) {
                    assertEquals(0, sample);
                }
            } else if (shape == VorbisEncoding.Shape.MUSIC || shape == VorbisEncoding.Shape.SINE || shape == VorbisEncoding.Shape.FULL_SCALE) {
                var snr = VorbisEncoding.snr(input, decoded.planes());
                assertTrue(snr >= 6, shape + " SNR " + snr);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(save(shape.name(), ".ogg", file));
            }
        }

        @Test
        @DisplayName("keeps 5.1 channels in WAVE order through the Vorbis channel order")
        void channelOrder() {
            var encoder = encoder(48000, 6, 5);
            var length = 48000;
            var input = new float[6][length];
            for (var c = 0; c < 6; c++) {
                // the LFE channel (WAVE index 3) is coded band-limited, so it gets a low tone
                var frequency = c == 3 ? 70 : 300 + 450 * c;
                for (var i = 0; i < length; i++) {
                    input[c][i] = (float) (0.3 * StrictMath.sin(2 * Math.PI * frequency * i / 48000.0));
                }
            }
            var decoded = decodeOgg(VorbisEncoding.ogg(encoder, VorbisEncoding.encode(encoder, input, 1), length));
            for (var c = 0; c < 6; c++) {
                var channelSnr = VorbisEncoding.snr(new float[][]{input[c]}, new float[][]{decoded.planes()[c]});
                assertTrue(channelSnr >= 10, "channel " + c + " SNR " + channelSnr);
            }
        }

        @Test
        @DisplayName("perceptual distortion stays within 1 dB of ffmpeg's libvorbis at the same quality")
        void versusLibvorbis() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var length = 44100 * 4;
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.MUSIC, 2, 44100, length, 41);
            var encoder = new VorbisEncoder(VorbisEncoderOptions.ofQuality(4));
            var ours = VorbisEncoding.ogg(encoder, VorbisEncoding.encode(encoder, input, 1), length);
            var raw = ByteBuffer.allocate(length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (var i = 0; i < length; i++) {
                raw.putFloat(input[0][i]).putFloat(input[1][i]);
            }
            var source = save("src", ".f32", raw.array());
            var theirs = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-f", "f32le",
                    "-ar", "44100", "-ac", "2", "-i", source.toString(), "-c:a", "libvorbis", "-q:a", "4", "-f", "ogg", "-"));
            var interleaved = VorbisEncoding.interleave(input);
            var oursNmr = PerceptualDistortion.measureInterleaved(interleaved, decodeOgg(ours).interleaved(), 2, 0, 44100);
            var theirsNmr = PerceptualDistortion.measureInterleaved(interleaved,
                    Oracle.decodeAudio(save("lib", ".ogg", theirs)), 2, 0, 44100);
            assertTrue(oursNmr.nmr() <= theirsNmr.nmr() + 1, oursNmr + " versus " + theirsNmr);
            assertTrue(Math.abs(ours.length - theirs.length) <= theirs.length * 0.05, ours.length + " bytes versus " + theirs.length);
        }
    }

    @Nested
    @DisplayName("containers")
    class Containers {
        @Test
        @DisplayName("an Ogg file ends on the exact input length and seeks with Smalt's demuxer")
        void oggGranules() {
            var encoder = new VorbisEncoder();
            var length = 44100 * 10 + 333;
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.TRANSIENTS, 2, 44100, length, 5);
            var file = VorbisEncoding.ogg(encoder, VorbisEncoding.encode(encoder, input, 1), length);
            var link = OggVorbis.read(file).getFirst();
            assertEquals(length, link.lastGranule());
            try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
                var track = AudioPipeline.firstAudio(demuxer);
                assertEquals(Codec.VORBIS, track.codec());
                assertEquals(length, track.timeBase()
                        .rescale(track.duration(), com.github.auties00.smalt.track.Rational.timeBaseOf(44100)));
                demuxer.seek(5_000_000);
                var packet = demuxer.read();
                var seconds = track.timeBase().toMicros(packet.pts()) / 1e6;
                assertTrue(Math.abs(seconds - 5) <= 0.1, "seek landed at " + seconds);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var probe = Oracle.probe(save("granule", ".ogg", file));
                var matcher = java.util.regex.Pattern.compile("\"duration\": \"([0-9.]+)\"").matcher(probe);
                assertTrue(matcher.find(), probe);
                assertEquals(length / 44100.0, Double.parseDouble(matcher.group(1)), 0.001);
            }
        }

        @Test
        @DisplayName("a Matroska file carries the headers in CodecPrivate and decodes to the input timing")
        void matroska() {
            var encoder = new VorbisEncoder(VorbisEncoderOptions.of(48000, ChannelLayout.STEREO));
            var length = 48000 + 777;
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.IMPULSE, 2, 48000, length, 0);
            var packets = VorbisEncoding.encode(encoder, input, 1);
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink)) {
                muxer.addTrack(VorbisEncoding.track(encoder, length));
                for (var packet : packets) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var file = sink.toByteArray();
            var decoded = read(file);
            assertTrue(Math.abs(decoded.length / 2 - length) <= 1024, decoded.length / 2 + " frames for " + length);
            var peak = 0;
            for (var i = 0; i < decoded.length / 2; i++) {
                if (Math.abs(decoded[2 * i]) > Math.abs(decoded[2 * peak])) {
                    peak = i;
                }
            }
            assertTrue(Math.abs(peak - length / 3) <= 2, "impulse at " + peak + " instead of " + length / 3);
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(save("mkv", ".mkv", file));
            }
        }

        @Test
        @DisplayName("the container registry muxes Vorbis into Ogg")
        void registry() {
            assertTrue(com.github.auties00.smalt.container.Containers.isMuxable(ContainerFormat.OGG, Codec.VORBIS));
        }

        private float[] read(byte[] file) {
            try (var reader = AudioReader.open(MediaSource.of(file))) {
                var out = new ArrayList<Float>();
                AudioBuffer buffer;
                while ((buffer = reader.read()) != null) {
                    for (var i = 0; i < buffer.frames(); i++) {
                        for (var c = 0; c < buffer.channels(); c++) {
                            out.add(buffer.plane(c)[i]);
                        }
                    }
                }
                var result = new float[out.size()];
                for (var i = 0; i < result.length; i++) {
                    result[i] = out.get(i);
                }
                return result;
            }
        }
    }

    @Nested
    @DisplayName("encoder contract")
    class Contract {
        @Test
        @DisplayName("reports Vorbis, the long half block, no delay and the three laced headers")
        void properties() {
            var encoder = new VorbisEncoder();
            assertEquals(Codec.VORBIS, encoder.codec());
            assertEquals(44100, encoder.sampleRate());
            assertEquals(ChannelLayout.STEREO, encoder.layout());
            assertEquals(1024, encoder.frameSize());
            assertEquals(0, encoder.delay());
            var headers = XiphLacing.unlace(encoder.codecConfig().orElseThrow());
            for (var i = 0; i < 3; i++) {
                assertEquals(2 * i + 1, headers[i][0]);
                assertEquals("vorbis", new String(headers[i], 1, 6, java.nio.charset.StandardCharsets.US_ASCII));
            }
            assertEquals(VorbisEncoder.VENDOR, new String(headers[1], 11, headers[1][7], java.nio.charset.StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("produces the same packets whatever the input chunking, and on a second run")
        void deterministic() {
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.TRANSIENTS, 2, 44100, 44100 * 2, 29);
            var reference = VorbisEncoding.encode(new VorbisEncoder(), input, 44100 * 2);
            for (var chunk : new int[]{1, 64, 1023, 4097}) {
                var packets = VorbisEncoding.encode(new VorbisEncoder(), input, chunk);
                assertEquals(reference.size(), packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    assertArrayEquals(reference.get(i).data(), packets.get(i).data());
                }
            }
        }

        @Test
        @DisplayName("codes NaN and infinities as silence and loud samples without failing")
        void hostileSamples() {
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.MUSIC, 2, 44100, 20000, 31);
            for (var i = 0; i < 20000; i += 7) {
                input[0][i] = Float.NaN;
                input[1][i] = i % 2 == 0 ? Float.POSITIVE_INFINITY : 4;
            }
            var encoder = new VorbisEncoder();
            var decoded = decodeOgg(VorbisEncoding.ogg(encoder, VorbisEncoding.encode(encoder, input, 1), 20000));
            for (var sample : decoded.interleaved()) {
                assertTrue(Float.isFinite(sample));
            }
        }

        @Test
        @DisplayName("an empty stream produces no packets, one sample produces a decodable stream")
        void tinyInputs() {
            var encoder = new VorbisEncoder();
            var packets = new ArrayList<Packet>();
            encoder.flush(packets::add);
            assertTrue(packets.isEmpty());
            var one = new VorbisEncoder();
            var onePackets = VorbisEncoding.encode(one, new float[][]{{0.5f}, {-0.5f}}, 1);
            assertFalse(onePackets.isEmpty());
            assertEquals(1, decodeOgg(VorbisEncoding.ogg(one, onePackets, 1)).frames());
        }

        @Test
        @DisplayName("rejects a mismatched input and input after flush")
        void misuse() {
            var encoder = new VorbisEncoder();
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 44100, 16), packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, 16), packet -> {
            }));
            encoder.flush(packet -> {
            });
            assertThrows(IllegalStateException.class, () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 44100, 16), packet -> {
            }));
        }

        @Test
        @DisplayName("finishes long noise streams at the extreme qualities within a time bound")
        void bounded() {
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (var quality : new float[]{-1, 10}) {
                    var noise = VorbisEncoding.signal(VorbisEncoding.Shape.NOISE, 2, 44100, 44100 * 3, 37);
                    assertFalse(VorbisEncoding.encode(VorbisEncoderOptions.ofQuality(quality), noise).isEmpty());
                }
            });
        }

        @Test
        @DisplayName("a nominal bitrate selects a quality whose bitrate is near it")
        void nominalBitrate() {
            var input = VorbisEncoding.signal(VorbisEncoding.Shape.MUSIC, 2, 44100, 44100 * 4, 43);
            var previous = 0.0;
            for (var kbps : new int[]{64, 128, 192}) {
                var packets = VorbisEncoding.encode(VorbisEncoderOptions.ofNominalBitrate(kbps * 1000), input);
                var bytes = packets.stream().mapToLong(Packet::size).sum();
                var actual = bytes * 8.0 / 4 / 1000;
                assertTrue(actual > previous * 1.1, actual + " kbit/s for nominal " + kbps + " after " + previous);
                assertTrue(actual < kbps * 2, actual + " kbit/s for nominal " + kbps);
                previous = actual;
            }
        }
    }

    @Nested
    @DisplayName("registry")
    class Registry {
        @Test
        @DisplayName("AudioEncoders creates a Vorbis encoder, mapping a bitrate to a nominal bitrate")
        void create() {
            assertTrue(AudioEncoders.isSupported(Codec.VORBIS));
            var encoder = (VorbisEncoder) AudioEncoders.create(Codec.VORBIS, AudioEncoderOptions.of(48000, ChannelLayout.SURROUND_5_1));
            assertEquals(48000, encoder.sampleRate());
            assertTrue(encoder.options().nominalBitrate().isEmpty());
            var bitrate = (VorbisEncoder) AudioEncoders.create(Codec.VORBIS,
                    AudioEncoderOptions.of(44100, ChannelLayout.STEREO).toBuilder().bitrate(128_000).build());
            assertEquals(128_000, bitrate.options().nominalBitrate().orElseThrow());
            var clamped = (VorbisEncoder) AudioEncoders.create(Codec.VORBIS,
                    AudioEncoderOptions.of(44100, ChannelLayout.STEREO).toBuilder().bitrate(8_000).build());
            assertEquals(45_000, clamped.options().nominalBitrate().orElseThrow());
        }
    }
}
