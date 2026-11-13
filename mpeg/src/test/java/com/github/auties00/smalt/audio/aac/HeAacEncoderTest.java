package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.sbr.SbrEncoder;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the HE-AAC and HE-AAC v2 encoder through this module's decoder and, as black boxes, ffmpeg and Core
 * Audio: the signaling of every configuration form and its ADTS and MP4 carriage, the delay, the reconstruction of
 * the high band and of the stereo image, the conformance of the bitstream (an independent decoder outputs the same
 * samples within the 16-bit criterion of ISO/IEC 14496-26), the bitrate, and the quality against AAC-LC at the
 * same low bitrate.
 */
class HeAacEncoderTest {
    @TempDir
    Path directory;

    record Encoded(AacEncoder encoder, List<Packet> packets, byte[] config) {
        List<byte[]> frames() {
            return packets.stream().map(Packet::data).toList();
        }

        long bytes() {
            return packets.stream().mapToLong(Packet::size).sum();
        }
    }

    static AacEncoderOptions options(AacProfile profile, int rate, ChannelLayout layout, int bitrate) {
        return AacEncoderOptions.builder().format(rate, layout).bitrate(bitrate).profile(profile).build();
    }

    static Encoded encode(AacEncoderOptions options, float[][] input) {
        var encoder = new AacEncoder(options);
        var packets = new ArrayList<Packet>();
        var block = 1000;
        for (var start = 0; start < input[0].length; start += block) {
            var count = Math.min(block, input[0].length - start);
            var planes = new float[input.length][];
            for (var c = 0; c < input.length; c++) {
                planes[c] = Arrays.copyOfRange(input[c], start, start + count);
            }
            encoder.encode(new AudioBuffer(options.layout(), options.sampleRate(), planes, count), packets::add);
        }
        encoder.flush(packets::add);
        return new Encoded(encoder, packets, encoder.codecConfig().orElseThrow());
    }

    static AacTestSupport.Decoded decode(Encoded encoded, int declaredRate) {
        var track = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000))
                .codecConfig(encoded.config()).sampleRate(declaredRate).build();
        return AacTestSupport.decodeAll(new AacDecoder(track), encoded.frames());
    }

    /** Returns music-like content: a harmonic tone with vibrato, broadband noise and periodic clicks. */
    static float[][] music(int rate, int channels, double seconds) {
        var length = (int) (rate * seconds);
        var planes = new float[channels][length];
        for (var c = 0; c < channels; c++) {
            var random = new Random(31 + c);
            double colored = 0;
            for (var i = 0; i < length; i++) {
                var t = (double) i / rate;
                var value = 0.0;
                var fundamental = (c == 0 ? 196.0 : 247.0) * (1 + 0.003 * Math.sin(2 * Math.PI * 5 * t));
                for (var h = 1; fundamental * h < rate / 2.0 - 1000; h++) {
                    value += 0.12 / h * Math.sin(2 * Math.PI * fundamental * h * t + h * 0.7);
                }
                colored = 0.6 * colored + random.nextGaussian();
                value += 0.02 * colored;
                var click = i % (rate / 3);
                if (click < 300) {
                    value += 0.3 * random.nextGaussian() * Math.exp(-click / 80.0);
                }
                planes[c][i] = (float) value;
            }
        }
        return planes;
    }

    /** Returns the energy of a signal in a frequency range, from a sample on, through 2048-point Hann windows. */
    static double bandEnergy(float[] signal, int from, int length, double low, double high, int rate) {
        var size = 2048;
        var re = new double[size];
        var im = new double[size];
        var total = 0.0;
        for (var start = from; start + size <= Math.min(signal.length, from + length); start += size) {
            for (var i = 0; i < size; i++) {
                re[i] = signal[start + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / size));
                im[i] = 0;
            }
            fft(re, im);
            for (var k = 0; k < size / 2; k++) {
                var f = (double) k * rate / size;
                if (f >= low && f < high) {
                    total += re[k] * re[k] + im[k] * im[k];
                }
            }
        }
        return total;
    }

    private static void fft(double[] re, double[] im) {
        var n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            var bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                var t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (var length = 2; length <= n; length <<= 1) {
            var angle = -2 * Math.PI / length;
            for (var i = 0; i < n; i += length) {
                for (var j = 0; j < length / 2; j++) {
                    var wr = Math.cos(angle * j);
                    var wi = Math.sin(angle * j);
                    var a = i + j;
                    var b = a + length / 2;
                    var xr = re[b] * wr - im[b] * wi;
                    var xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    /**
     * Returns the mean absolute difference in dB between the band energies of a decode and its input, over
     * third-octave bands from 200 Hz to {@code top}, the decode taken {@code delay} samples later.
     */
    static double spectralDistance(float[] input, float[] decoded, int delay, int rate, double top) {
        var sum = 0.0;
        var count = 0;
        for (var low = 200.0; low * 1.26 <= top; low *= 1.26) {
            var reference = bandEnergy(input, rate / 2, input.length - rate, low, low * 1.26, rate);
            var test = bandEnergy(Arrays.copyOfRange(decoded, delay, decoded.length), rate / 2, input.length - rate, low,
                    low * 1.26, rate);
            if (reference > 1e-6) {
                sum += Math.abs(10 * Math.log10((test + 1e-9) / reference));
                count++;
            }
        }
        return sum / count;
    }

    /** Returns the lag from -64 to 64 around {@code expected} at which the low bands of two signals match best. */
    static int lag(float[] input, float[] decoded, int expected) {
        var low = HeAacCompare.lowPass(input);
        var out = HeAacCompare.lowPass(decoded);
        var best = Integer.MIN_VALUE;
        var bestError = Double.MAX_VALUE;
        for (var d = expected - 64; d <= expected + 64; d++) {
            var error = 0.0;
            for (var i = input.length / 4; i < input.length * 3 / 4 && i + d < out.length; i++) {
                var e = (double) out[i + d] - low[i];
                error += e * e;
            }
            if (error < bestError) {
                bestError = error;
                best = d;
            }
        }
        return best;
    }

    Path adts(String name, Encoded encoded, AacEncoderOptions options) {
        var sink = MediaSink.memory();
        try (var muxer = new AdtsMuxer(sink)) {
            muxer.addTrack(AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(options.sampleRate()))
                    .sampleRate(options.sampleRate()).layout(options.layout()).codecConfig(encoded.config()).build());
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
            return Files.write(directory.resolve(name + ".aac"), sink.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    Path mp4(String name, Encoded encoded, AacEncoderOptions options) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.m4a())) {
            muxer.addTrack(AudioTrack.builder().id(0).codec(Codec.AAC).timeBase(Rational.timeBaseOf(options.sampleRate()))
                    .duration(-1).sampleRate(options.sampleRate()).layout(options.layout())
                    .codecConfig(encoded.config()).build());
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
            return Files.write(directory.resolve(name + ".m4a"), sink.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("signaling")
    class Signaling {
        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "HE, HIERARCHICAL, 44100, 2, 2B920800",
                "HE, HIERARCHICAL, 48000, 1, 2B098800",
                "HE_V2, HIERARCHICAL, 48000, 2, EB098800",
                "HE, IMPLICIT, 44100, 2, 1390",
                "HE, BACKWARD_COMPATIBLE, 44100, 2, 139056E5A0",
                "HE_V2, BACKWARD_COMPATIBLE, 48000, 2, 130856E59D4880"
        })
        @DisplayName("writes the AudioSpecificConfig of each signaling form")
        void config(AacProfile profile, AacSignaling signaling, int rate, int channels, String expected) {
            var layout = channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
            var options = AacEncoderOptions.builder().format(rate, layout).bitrate(32000).profile(profile)
                    .signaling(signaling).build();
            var config = new AacEncoder(options).codecConfig().orElseThrow();
            assertEquals(expected, HexFormat.of().withUpperCase().formatHex(config));
            var parsed = AudioSpecificConfig.parse(config);
            assertEquals(AudioSpecificConfig.AOT_LC, parsed.objectType());
            assertEquals(rate / 2, parsed.sampleRate());
            assertEquals(profile == AacProfile.HE_V2 ? 1 : channels, parsed.channelConfiguration());
            assertEquals(signaling != AacSignaling.IMPLICIT, parsed.sbrSignaled());
            assertEquals(signaling != AacSignaling.IMPLICIT, parsed.sbr());
            assertEquals(signaling != AacSignaling.IMPLICIT && profile == AacProfile.HE_V2, parsed.ps());
            assertEquals(signaling == AacSignaling.IMPLICIT ? 0 : rate, parsed.extensionSampleRate());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AacSignaling.class)
        @DisplayName("decodes at the input rate whatever the signaling, a decoder finding implicit extensions itself")
        void decodes(AacSignaling signaling) {
            var input = music(44100, 2, 1.5);
            var options = AacEncoderOptions.builder().format(44100, ChannelLayout.STEREO).bitrate(32000)
                    .profile(AacProfile.HE).signaling(signaling).build();
            var encoded = encode(options, input);
            var decoded = decode(encoded, 0);
            assertEquals(44100, decoded.sampleRate());
            assertEquals(ChannelLayout.STEREO, decoded.layout());
            assertEquals(encoded.packets().size() * 2048L, decoded.frames());
        }

        @Test
        @DisplayName("ADTS carries the core configuration, which ffmpeg decodes with its extensions")
        void adtsHeaders() throws IOException {
            var input = music(48000, 2, 1);
            var options = options(AacProfile.HE, 48000, ChannelLayout.STEREO, 40000);
            var encoded = encode(options, input);
            var file = adts("adts-header", encoded, options);
            var data = Files.readAllBytes(file);
            assertEquals(0xFF, data[0] & 0xFF);
            assertEquals(1, (data[2] >> 6) & 3, "profile LC");
            assertEquals(6, (data[2] >> 2) & 15, "the 24000 Hz core rate");
            Oracle.assume(Oracle.Tool.FFPROBE);
            var probe = Oracle.probe(file);
            // ADTS signals the core alone, so ffprobe names the profile LC; it finds the output rate on decoding
            assertTrue(probe.contains("\"sample_rate\": \"48000\""), probe);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"HE, HE-AAC, 2", "HE_V2, HE-AACv2, 2"})
        @DisplayName("ffprobe identifies the profile, rate and channels of an M4A file")
        void ffprobe(AacProfile profile, String name, int channels) {
            Oracle.assume(Oracle.Tool.FFPROBE);
            var options = options(profile, 48000, ChannelLayout.STEREO, 32000);
            var encoded = encode(options, music(48000, 2, 1));
            var probe = Oracle.probe(mp4("probe-" + profile, encoded, options));
            assertTrue(probe.contains("\"profile\": \"" + name + "\""), probe);
            assertTrue(probe.contains("\"sample_rate\": \"48000\""), probe);
            assertTrue(probe.contains("\"channels\": " + channels), probe);
        }
    }

    @Nested
    @DisplayName("reconstruction")
    class Reconstruction {
        @ParameterizedTest(name = "{0} {1} Hz, {2} channels at {3} bps")
        @CsvSource({"HE, 44100, 2, 32000", "HE, 48000, 1, 24000", "HE, 32000, 1, 16000", "HE, 48000, 2, 64000",
                "HE_V2, 44100, 2, 24000", "HE_V2, 48000, 2, 32000", "HE, 22050, 1, 12000", "HE, 96000, 2, 96000"})
        @DisplayName("the low band arrives exactly delay() samples late and the high band keeps its energy")
        void delayAndEnvelope(AacProfile profile, int rate, int channels, int bitrate) {
            var layout = channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
            var options = options(profile, rate, layout, bitrate);
            var input = music(rate, channels, 3);
            var encoded = encode(options, input);
            var decoded = decode(encoded, rate);
            var delay = encoded.encoder().delay();
            assertEquals(2 * 1024 + 1539, delay);
            assertEquals(delay, lag(input[0], decoded.planes()[0], delay), "low band alignment");
            var crossover = crossover(encoded);
            var top = options.effectiveBandwidth();
            for (var c = 0; c < channels; c++) {
                var reference = bandEnergy(input[c], rate / 2, input[c].length - rate, crossover, top, rate);
                var shifted = Arrays.copyOfRange(decoded.planes()[c], delay, decoded.planes()[c].length);
                var test = bandEnergy(shifted, rate / 2, input[c].length - rate, crossover, top, rate);
                var db = 10 * Math.log10(test / reference);
                assertTrue(Math.abs(db) < 3, "channel " + c + " high band energy changes by " + db + " dB");
                var above = bandEnergy(shifted, rate / 2, input[c].length - rate, top * 1.08, rate / 2.0, rate);
                assertTrue(above < 0.1 * test, "nothing above the stop frequency");
            }
            if (profile == AacProfile.HE && bitrate / channels >= 16000) {
                // parametric stereo rebuilds the channels from a downmix, so only HE-AAC v1 keeps the waveform
                assertTrue(TestSignals.segmentalSnr(new float[][]{HeAacCompare.lowPass(input[0])},
                        new float[][]{HeAacCompare.lowPass(Arrays.copyOfRange(decoded.planes()[0], delay,
                                decoded.planes()[0].length))}) > 8, "the low band resembles the input");
            }
        }

        /** Returns the crossover frequency of a stream. */
        private static double crossover(Encoded encoded) {
            var options = encoded.encoder().options();
            return SbrEncoder.crossoverFrequency(options.sampleRate(), options.coding(), options.bitrate(),
                    options.profile() == AacProfile.HE_V2);
        }

        @Test
        @DisplayName("a pan of independent sources keeps each source in its channel")
        void stereoImage() {
            var rate = 48000;
            var length = rate * 3;
            var random = new Random(9);
            var input = new float[2][length];
            for (var i = 0; i < length; i++) {
                var low = 0.2 * Math.sin(2 * Math.PI * 700 * i / rate);
                var high = 0.04 * random.nextGaussian();
                input[0][i] = (float) (low + 0.3 * high);
                input[1][i] = (float) (0.25 * low + high);
            }
            var options = options(AacProfile.HE_V2, rate, ChannelLayout.STEREO, 32000);
            var encoded = encode(options, input);
            var decoded = decode(encoded, rate);
            assertEquals(2, decoded.planes().length);
            var delay = encoded.encoder().delay();
            for (var band : new double[][]{{500, 1000}, {4000, 8000}, {9000, 14000}}) {
                var expected = 10 * Math.log10(bandEnergy(input[0], rate / 2, length - rate, band[0], band[1], rate)
                        / bandEnergy(input[1], rate / 2, length - rate, band[0], band[1], rate));
                var left = Arrays.copyOfRange(decoded.planes()[0], delay, decoded.planes()[0].length);
                var right = Arrays.copyOfRange(decoded.planes()[1], delay, decoded.planes()[1].length);
                var actual = 10 * Math.log10(bandEnergy(left, rate / 2, length - rate, band[0], band[1], rate)
                        / bandEnergy(right, rate / 2, length - rate, band[0], band[1], rate));
                assertEquals(expected, actual, 3, "level difference " + band[0] + "-" + band[1] + " Hz");
            }
        }

        @Test
        @DisplayName("5.1 codes spectral band replication for every element but the LFE")
        void surround() {
            var rate = 48000;
            var input = music(rate, 6, 2);
            for (var i = 0; i < input[3].length; i++) {
                input[3][i] = (float) (0.3 * Math.sin(2 * Math.PI * 60 * i / rate));
            }
            var options = options(AacProfile.HE, rate, ChannelLayout.SURROUND_5_1, 160000);
            var encoded = encode(options, input);
            var decoded = decode(encoded, rate);
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layout());
            var delay = encoded.encoder().delay();
            for (var c = 0; c < 6; c++) {
                var shifted = Arrays.copyOfRange(decoded.planes()[c], delay, decoded.planes()[c].length);
                var high = bandEnergy(shifted, rate / 2, rate, 9000, 14000, rate);
                var reference = bandEnergy(input[c], rate / 2, rate, 9000, 14000, rate);
                if (c == 3) {
                    assertTrue(high < 1e-3, "the LFE stays band-limited");
                } else {
                    var db = 10 * Math.log10(high / reference);
                    assertTrue(Math.abs(db) < 4, "channel " + c + " high band " + db + " dB");
                }
            }
        }

        @Test
        @DisplayName("960-sample frames decode at the input rate")
        void shortFrames() {
            var options = AacEncoderOptions.builder().format(48000, ChannelLayout.STEREO).bitrate(32000)
                    .profile(AacProfile.HE).frameLength(960).build();
            var input = music(48000, 2, 2);
            var encoded = encode(options, input);
            assertEquals(1920, encoded.encoder().frameSize());
            var decoded = decode(encoded, 48000);
            assertEquals(48000, decoded.sampleRate());
            assertEquals(encoded.packets().size() * 1920L, decoded.frames());
            var delay = encoded.encoder().delay();
            assertEquals(2 * 960 + 1539, delay);
            assertEquals(delay, lag(input[0], decoded.planes()[0], delay));
        }

        @Test
        @DisplayName("silence decodes to near silence and clipped or NaN input stays finite")
        void edges() {
            var options = options(AacProfile.HE_V2, 48000, ChannelLayout.STEREO, 24000);
            var silent = decode(encode(options, new float[2][48000]), 48000);
            // the lowest envelope value stands for an energy of 64 on the 16-bit scale, about -70 dB
            for (var plane : silent.planes()) {
                for (var value : plane) {
                    assertEquals(0, value, 2e-3);
                }
            }
            var loud = new float[2][48000];
            for (var i = 0; i < loud[0].length; i++) {
                loud[0][i] = i % 100 == 0 ? Float.NaN : (float) (4 * Math.sin(i * 0.37));
                loud[1][i] = (i & 64) == 0 ? 2 : -2;
            }
            for (var plane : decode(encode(options, loud), 48000).planes()) {
                for (var value : plane) {
                    assertTrue(Float.isFinite(value) && Math.abs(value) <= 4, "sample " + value);
                }
            }
        }
    }

    @Nested
    @DisplayName("black-box decoders")
    class BlackBox {
        @ParameterizedTest(name = "{0} {1} Hz, {2} channels at {3} bps")
        @CsvSource({"HE, 44100, 2, 32000", "HE, 48000, 1, 24000", "HE_V2, 48000, 2, 24000", "HE_V2, 32000, 2, 20000",
                "HE, 48000, 6, 128000", "HE, 24000, 1, 16000", "HE, 96000, 2, 128000"})
        @DisplayName("ffmpeg decodes the M4A file cleanly and as this decoder does, within the 16-bit criterion")
        void ffmpeg(AacProfile profile, int rate, int channels, int bitrate) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var layout = switch (channels) {
                case 1 -> ChannelLayout.MONO;
                case 2 -> ChannelLayout.STEREO;
                default -> ChannelLayout.SURROUND_5_1;
            };
            var options = options(profile, rate, layout, bitrate);
            var encoded = encode(options, music(rate, channels, 3));
            var file = mp4("ffmpeg-" + profile + "-" + rate + "-" + channels, encoded, options);
            Oracle.assertDecodesCleanly(file);
            var mine = decode(encoded, rate);
            // a mono HE-AAC core may carry parametric stereo, so both decoders output two channels for it
            var outputs = mine.planes().length;
            var reference = AacTestSupport.deinterleave(Oracle.decodeAudio(file), outputs);
            for (var c = 0; c < outputs; c++) {
                var length = Math.min(reference[c].length, mine.planes()[c].length);
                assertTrue(length >= mine.planes()[c].length - 2 * 2048L, "ffmpeg decodes every frame");
                var error = HeAacConformanceTest.difference(Arrays.copyOf(reference[c], length),
                        Arrays.copyOf(mine.planes()[c], length));
                assertTrue(error[0] <= AacConformanceTest.RMS_LIMIT, "channel " + c + ": RMS " + error[0]);
                assertTrue(error[1] <= AacConformanceTest.PEAK_LIMIT, "channel " + c + ": peak " + error[1]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AacProfile.class, names = {"HE", "HE_V2"})
        @DisplayName("ffmpeg decodes the ADTS file, implicit signaling, at the input rate")
        void ffmpegAdts(AacProfile profile) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var options = options(profile, 44100, ChannelLayout.STEREO, 28000);
            var input = music(44100, 2, 2);
            var encoded = encode(options, input);
            var file = adts("adts-" + profile, encoded, options);
            Oracle.assertDecodesCleanly(file);
            var decoded = AacTestSupport.deinterleave(Oracle.decodeAudio(file), 2);
            assertTrue(decoded[0].length >= input[0].length, "decoded at 44100 Hz: " + decoded[0].length);
            var mine = decode(encoded, 0);
            var length = Math.min(decoded[0].length, mine.planes()[0].length);
            var error = HeAacConformanceTest.difference(Arrays.copyOfRange(decoded[0], 4096, length),
                    Arrays.copyOfRange(mine.planes()[0], 4096, length));
            assertTrue(error[0] <= AacConformanceTest.RMS_LIMIT, "RMS " + error[0]);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AacProfile.class, names = {"HE", "HE_V2"})
        @DisplayName("Core Audio decodes the M4A file as this decoder does")
        void coreAudio(AacProfile profile) throws IOException {
            var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            Skip.unless(os.startsWith("mac"), "platform:macos", "afconvert is part of macOS");
            var afconvert = Oracle.find("afconvert").orElseThrow(() -> new AssertionError("macOS without afconvert"));
            var options = options(profile, 44100, ChannelLayout.STEREO, 32000);
            var input = music(44100, 2, 3);
            var encoded = encode(options, input);
            var file = mp4("core-audio-" + profile, encoded, options);
            var wav = directory.resolve("core-audio-" + profile + ".wav");
            var result = Oracle.execute(afconvert, Duration.ofMinutes(1), null, List.of("-f", "WAVE", "-d", "LEF32",
                    file.toString(), wav.toString()));
            assertEquals(0, result.exitCode(), result.stderr());
            var bytes = Files.readAllBytes(wav);
            var format = format(bytes);
            var mine = decode(encoded, 44100);
            System.out.printf("Core Audio %s: %d channels at %d Hz, %d bytes; Smalt %d frames%n", profile, format[0],
                    format[1], bytes.length, mine.frames());
            assertEquals(2, format[0], "Core Audio output channels");
            assertEquals(44100, format[1], "Core Audio output rate");
            var decoded = AacTestSupport.deinterleave(samples(wav), 2);
            for (var c = 0; c < 2; c++) {
                var reference = mine.planes()[c];
                // below 4 kHz, inside the core band, where the decoders differ only by rounding; Core Audio
                // trims its own priming, so its output is searched over more than a frame of lags
                var cutoff = 4000.0 / 44100;
                var low = HeAacCompare.align(HeAacCompare.lowPass(reference, cutoff),
                        HeAacCompare.lowPass(decoded[c], cutoff), 12288, 2048);
                var lag = (int) low[1];
                var summary = String.format(Locale.ROOT, "Core Audio %s channel %d: %d frames (Smalt %d), low band SNR"
                        + " %.2f dB at lag %d", profile, c, decoded[c].length, reference.length, low[0], lag);
                System.out.println(summary);
                assertTrue(low[0] > 25, summary);
                var from = 22050 + Math.max(0, -lag);
                var span = Math.min(88200, Math.min(reference.length - from, decoded[c].length - from - lag));
                assertTrue(span >= 44100, summary);
                var energy = bandEnergy(decoded[c], from + lag, span, 8000, 15000, 44100)
                        / bandEnergy(reference, from, span, 8000, 15000, 44100);
                assertEquals(0, 10 * Math.log10(energy), 2, "channel " + c + " high band against Core Audio");
            }
        }

        /** Returns the channel count and the sampling rate of a WAVE file's format chunk. */
        private static int[] format(byte[] bytes) {
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var position = 12;
            while (position + 8 <= bytes.length) {
                var id = new String(bytes, position, 4, java.nio.charset.StandardCharsets.US_ASCII);
                var size = buffer.getInt(position + 4);
                if (id.equals("fmt ")) {
                    return new int[]{buffer.getShort(position + 10) & 0xFFFF, buffer.getInt(position + 12)};
                }
                position += 8 + size + (size & 1);
            }
            throw new AssertionError("no fmt chunk");
        }

        private static float[] samples(Path wav) throws IOException {
            var bytes = Files.readAllBytes(wav);
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var position = 12;
            while (position + 8 <= bytes.length) {
                var id = new String(bytes, position, 4, java.nio.charset.StandardCharsets.US_ASCII);
                var size = buffer.getInt(position + 4);
                if (id.equals("data")) {
                    var out = new float[size / 4];
                    buffer.position(position + 8);
                    buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
                    return out;
                }
                position += 8 + size + (size & 1);
            }
            throw new AssertionError("no data chunk in " + wav);
        }
    }

    @Nested
    @DisplayName("rate and quality")
    class RateAndQuality {
        @ParameterizedTest(name = "{0} at {1} bps")
        @CsvSource({"HE, 32000", "HE, 64000", "HE_V2, 24000", "HE_V2, 40000"})
        @DisplayName("constant bitrate meets the bitrate exactly over the stream")
        void constantBitrate(AacProfile profile, int bitrate) {
            var options = options(profile, 48000, ChannelLayout.STEREO, bitrate);
            var encoded = encode(options, music(48000, 2, 6));
            var seconds = encoded.packets().size() * 2048.0 / 48000;
            var actual = encoded.bytes() * 8 / seconds;
            assertEquals(bitrate, actual, bitrate * 0.02, "bitrate");
            for (var packet : encoded.packets()) {
                assertEquals(2048, packet.duration());
            }
        }

        @Test
        @DisplayName("variable bitrate decodes and stays near its nominal bitrate")
        void variableBitrate() {
            var options = options(AacProfile.HE, 48000, ChannelLayout.STEREO, 48000).toBuilder()
                    .bitrateMode(AacBitrateMode.VBR).build();
            var encoded = encode(options, music(48000, 2, 4));
            var seconds = encoded.packets().size() * 2048.0 / 48000;
            var actual = encoded.bytes() * 8 / seconds;
            assertTrue(actual > 24000 && actual < 96000, "VBR bitrate " + actual);
            assertEquals(48000, decode(encoded, 48000).sampleRate());
        }

        @ParameterizedTest(name = "{0} {1} channels at {2} bps")
        @CsvSource({"HE, 1, 24000, 0.6", "HE, 2, 32000, 0.6", "HE, 2, 48000, 0.6", "HE_V2, 2, 24000, 0.6",
                "HE, 1, 32000, 0.9"})
        @DisplayName("matches the spectrum of the input better than AAC-LC at the same low bitrate")
        void againstLowComplexity(AacProfile profile, int channels, int bitrate, double ratio) {
            var rate = 48000;
            var layout = channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;
            var input = music(rate, channels, 4);
            var he = encode(options(profile, rate, layout, bitrate), input);
            var lc = encode(AacEncoderOptions.of(rate, layout, bitrate), input);
            var heDecoded = decode(he, rate);
            var lcDecoded = decode(lc, rate);
            var heDistance = 0.0;
            var lcDistance = 0.0;
            for (var c = 0; c < channels; c++) {
                heDistance += spectralDistance(input[c], heDecoded.planes()[c], he.encoder().delay(), rate, 16000);
                lcDistance += spectralDistance(input[c], lcDecoded.planes()[c], lc.encoder().delay(), rate, 16000);
            }
            assertTrue(heDistance < ratio * lcDistance, "spectral distance HE " + heDistance + " LC " + lcDistance);
            assertTrue(heDistance / channels < 3, "HE spectral distance " + heDistance / channels + " dB");
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("rejects what HE-AAC cannot code")
        void rejects() {
            var builder = AacEncoderOptions.builder().profile(AacProfile.HE);
            assertThrows(IllegalArgumentException.class, () -> builder.format(44100, ChannelLayout.STEREO)
                    .bitrate(4000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.format(7350, ChannelLayout.STEREO)
                    .bitrate(32000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.format(48000, ChannelLayout.STEREO)
                    .bitrate(32000).bandwidth(10000).build());
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.builder()
                    .profile(AacProfile.HE_V2).format(48000, ChannelLayout.MONO).bitrate(32000).build());
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.builder()
                    .profile(AacProfile.HE_V2).format(48000, ChannelLayout.SURROUND_5_1).bitrate(64000).build());
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.builder()
                    .profile(AacProfile.HE).format(48000, ChannelLayout.STEREO).bitrate(500000).build());
        }

        @Test
        @DisplayName("keeps the profile and signaling through toBuilder, equals and hashCode")
        void optionsRoundTrip() {
            var options = AacEncoderOptions.builder().format(48000, ChannelLayout.STEREO).bitrate(24000)
                    .profile(AacProfile.HE_V2).signaling(AacSignaling.BACKWARD_COMPATIBLE).build();
            var copy = options.toBuilder().build();
            assertEquals(options, copy);
            assertEquals(options.hashCode(), copy.hashCode());
            assertEquals(AacProfile.HE_V2, copy.profile());
            assertEquals(AacSignaling.BACKWARD_COMPATIBLE, copy.signaling());
            assertFalse(options.equals(options.toBuilder().profile(AacProfile.HE).build()));
            assertEquals(AacProfile.LC, AacEncoderOptions.defaults().profile());
            assertEquals(AacSignaling.HIERARCHICAL, AacEncoderOptions.defaults().signaling());
            assertEquals(1, options.channelConfiguration());
            assertTrue(options.effectiveBandwidth() > 10000 && options.effectiveBandwidth() <= 24000);
        }

        @Test
        @DisplayName("packet timestamps count input samples, and a flush covers every input sample")
        void timestamps() {
            var options = options(AacProfile.HE, 44100, ChannelLayout.MONO, 24000);
            var encoded = encode(options, music(44100, 1, 1));
            for (var i = 0; i < encoded.packets().size(); i++) {
                assertEquals(i * 2048L, encoded.packets().get(i).pts());
            }
            var covered = encoded.packets().size() * 2048L - encoded.encoder().delay();
            assertTrue(covered >= 44100 && covered < 44100 + 2048, "covered " + covered);
        }

        @Test
        @DisplayName("changes the core bitrate while running and stays decodable")
        void retarget() {
            var options = options(AacProfile.HE, 48000, ChannelLayout.STEREO, 64000);
            var encoder = new AacEncoder(options);
            var packets = new ArrayList<Packet>();
            var input = music(48000, 2, 4);
            var half = input[0].length / 2;
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, new float[][]{Arrays.copyOf(input[0], half),
                    Arrays.copyOf(input[1], half)}, half), packets::add);
            var first = packets.size();
            encoder.setTargetBitrate(32000);
            assertEquals(32000, encoder.options().bitrate());
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, new float[][]{
                    Arrays.copyOfRange(input[0], half, 2 * half), Arrays.copyOfRange(input[1], half, 2 * half)}, half),
                    packets::add);
            encoder.flush(packets::add);
            var before = packets.subList(2, first).stream().mapToLong(Packet::size).sum() / (double) (first - 2);
            var after = packets.subList(first + 4, packets.size()).stream().mapToLong(Packet::size).sum()
                    / (double) (packets.size() - first - 4);
            assertTrue(after < 0.7 * before, "bytes per frame " + before + " then " + after);
            assertEquals(32000, after * 8 * 48000 / 2048, 32000 * 0.05, "constant bitrate after the change");
            var encoded = new Encoded(encoder, packets, encoder.codecConfig().orElseThrow());
            assertEquals(48000, decode(encoded, 48000).sampleRate());
            assertThrows(IllegalArgumentException.class, () -> new AacEncoder(options).setTargetBitrate(1000));
        }

        @Test
        @DisplayName("encodes identically however the input is split into blocks")
        void blockInvariance() {
            var options = options(AacProfile.HE_V2, 48000, ChannelLayout.STEREO, 32000);
            var input = music(48000, 2, 1);
            var whole = new ArrayList<Packet>();
            var encoder = new AacEncoder(options);
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, input, input[0].length), whole::add);
            encoder.flush(whole::add);
            var split = encode(options, input).packets();
            assertEquals(whole.size(), split.size());
            for (var i = 0; i < whole.size(); i++) {
                assertArrayEquals(whole.get(i).data(), split.get(i).data(), "packet " + i);
            }
        }
    }
}
