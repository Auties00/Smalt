package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes AAC-ELD with low delay spectral band replication: the conformance streams of ISO/IEC 14496-26
 * ({@code er_eld_sbr}: dual-rate and downsampled, mono and stereo, at 22.05 to 48 kHz core rates, 512-sample
 * frames) and streams with 480-sample frames, which no conformance stream covers, encoded from synthetic signals
 * by libxaac (dual rate) and by the Fraunhofer FDK AAC encoder (dual rate and downsampled). The conformance suite
 * ships no reference waveforms, so the output is compared with two independent decoders, both written by
 * {@code tools/testdata/eld-sbr-oracle.py}: libxaac's, in {@code <conformance cache>/eld-sbr-oracle} (with the
 * encoded streams in its {@code encoded} directory; {@code -Dsmalt.test.eldSbrOracle} overrides the directory),
 * and the FDK AAC decoder's, in {@code <conformance cache>/eld-sbr-fdk} ({@code -Dsmalt.test.eldSbrFdk}). Without
 * those files the comparisons are skipped.
 *
 * <p>libxaac decodes in fixed point, so the two decoders agree to about {@code 2^-16} of full scale rather than
 * bit for bit, with larger deviations in a few frames: frames of tonal passages ({@code gh}) that differ by up to
 * about {@code 5e-3}, and a few frames of the synthetic envelope streams ({@code e}) where the core band is
 * silent and the decoders limit the gains of an empty low band differently. The comparison therefore bounds the
 * error of the frames that agree and the number of frames that do not. The FDK decoder agrees with both in the core band, which
 * at dual rate passes through the low delay analysis bank of 32 subbands and the synthesis bank of 64; its high
 * band differs in waveform while keeping the energies of the envelopes, so that band is compared by its energy.
 */
class AacLowDelaySbrTest {
    static Stream<String> streams() {
        return Stream.concat(Stream.of("e", "gh", "i", "s").flatMap(signal -> Stream.of(1, 2)
                        .flatMap(channels -> Stream.of("32", "44", "48").map(rate -> "er_eld_sbr_" + signal + "_" + channels
                                + "_" + rate))),
                Stream.of("er_eld_sbr_twi_1_24", "er_eld_sbr_twi_1_48"));
    }

    static Stream<String> encoded() {
        return Stream.of("xaac480_m48", "xaac480_s48", "xaac480_m44", "xaac480_m32", "xaac512_m48", "fdk480d_m48",
                "fdk480d_s48");
    }

    static Path libxaac() {
        return Path.of(System.getProperty("smalt.test.eldSbrOracle",
                Conformance.directory().resolve("eld-sbr-oracle").toString()));
    }

    static Path fdk() {
        return Path.of(System.getProperty("smalt.test.eldSbrFdk",
                Conformance.directory().resolve("eld-sbr-fdk").toString()));
    }

    static Path existing(Path file) {
        Skip.unless(Files.isRegularFile(file), "data:eld-sbr-oracle", "no oracle file " + file + " (tools/testdata/eld-sbr-oracle.py)");
        return file;
    }

    static float[][] readWav(Path file) {
        try {
            return AacTestSupport.readWav(Files.readAllBytes(existing(file)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Parses a conformance stream, or an encoded stream of the oracle script when the name has no prefix. */
    static AacTestSupport.Stream parse(String name) {
        if (name.startsWith("er_")) {
            return AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
        }
        var directory = libxaac().resolve("encoded");
        try {
            return AacTestSupport.parseRaw(Files.readAllBytes(existing(directory.resolve(name + ".raw"))),
                    Files.readString(existing(directory.resolve(name + ".meta"))));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static int frameLength(AacTestSupport.Stream parsed) {
        try (var decoder = new AacDecoder(parsed.track())) {
            return decoder.frameLength();
        }
    }

    /**
     * Checks that a decode agrees with a fixed-point decode in all but a few frames: the RMS error of the frames
     * within {@code 1e-2}, the 95th percentile of the frame peaks, and the number of frames beyond {@code 1e-2},
     * which only the synthetic envelope streams ({@code e}) may have, at most three per channel at the end of their
     * ramps, where the core band is silent.
     */
    static void assertAgrees(String name, float[][] expected, float[][] decoded, int frameLength) {
        var allowed = name.startsWith("er_eld_sbr_e_") ? 3 : 0;
        assertEquals(expected.length, decoded.length, name + " channels");
        for (var channel = 0; channel < expected.length; channel++) {
            var reference = expected[channel];
            var actual = decoded[channel];
            assertEquals(reference.length, actual.length, name + " length");
            var frames = reference.length / frameLength;
            var peaks = new double[frames];
            double squared = 0;
            var counted = 0;
            var outliers = 0;
            for (var frame = 0; frame < frames; frame++) {
                double peak = 0;
                double sum = 0;
                for (var i = frame * frameLength; i < (frame + 1) * frameLength; i++) {
                    var value = Math.clamp(actual[i], -1.0f, 32767 / 32768.0f);
                    var difference = Math.abs((double) value - reference[i]);
                    peak = Math.max(peak, difference);
                    sum += difference * difference;
                }
                peaks[frame] = peak;
                if (peak > 1e-2) {
                    outliers++;
                } else {
                    squared += sum;
                    counted += frameLength;
                }
            }
            var rms = Math.sqrt(squared / counted);
            Arrays.sort(peaks);
            var percentile = peaks[(int) (0.95 * (frames - 1))];
            var what = name + " channel " + channel + ": " + outliers + " frames beyond 1e-2, RMS " + rms
                    + " elsewhere, 95th percentile frame peak " + percentile;
            assertTrue(outliers <= allowed, what);
            assertTrue(rms < 2e-4, what);
            assertTrue(percentile < 2e-3, what);
        }
    }

    /** The power spectra of 2048-sample Hann blocks of a signal and of its difference from another. */
    record Spectra(double[] signal, double[] error, double[] other) {
        static Spectra of(float[] reference, float[] actual) {
            var size = 2048;
            var signal = new double[size / 2];
            var error = new double[size / 2];
            var other = new double[size / 2];
            var re = new double[size];
            var im = new double[size];
            var dre = new double[size];
            var dim = new double[size];
            var are = new double[size];
            var aim = new double[size];
            for (var start = 0; start + size <= Math.min(reference.length, actual.length); start += size) {
                for (var i = 0; i < size; i++) {
                    var window = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / size);
                    re[i] = reference[start + i] * window;
                    dre[i] = (reference[start + i] - Math.clamp(actual[start + i], -1.0f, 1.0f)) * window;
                    are[i] = Math.clamp(actual[start + i], -1.0f, 1.0f) * window;
                    im[i] = 0;
                    dim[i] = 0;
                    aim[i] = 0;
                }
                fft(re, im);
                fft(dre, dim);
                fft(are, aim);
                for (var k = 0; k < size / 2; k++) {
                    signal[k] += re[k] * re[k] + im[k] * im[k];
                    error[k] += dre[k] * dre[k] + dim[k] * dim[k];
                    other[k] += are[k] * are[k] + aim[k] * aim[k];
                }
            }
            return new Spectra(signal, error, other);
        }

        static void fft(double[] re, double[] im) {
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
                    for (var k = 0; k < length / 2; k++) {
                        var cos = Math.cos(angle * k);
                        var sin = Math.sin(angle * k);
                        var ure = re[i + k];
                        var uim = im[i + k];
                        var vre = re[i + k + length / 2] * cos - im[i + k + length / 2] * sin;
                        var vim = re[i + k + length / 2] * sin + im[i + k + length / 2] * cos;
                        re[i + k] = ure + vre;
                        im[i + k] = uim + vim;
                        re[i + k + length / 2] = ure - vre;
                        im[i + k + length / 2] = uim - vim;
                    }
                }
            }
        }

        double snr(int from, int to) {
            double s = 0;
            double e = 0;
            for (var k = from; k < to; k++) {
                s += signal[k];
                e += error[k];
            }
            return 10 * Math.log10(s / Math.max(e, 1e-30));
        }

        double energyRatio(int from, int to) {
            double s = 0;
            double o = 0;
            for (var k = from; k < to; k++) {
                s += signal[k];
                o += other[k];
            }
            return 10 * Math.log10(o / Math.max(s, 1e-30));
        }
    }

    @Nested
    @DisplayName("against libxaac")
    class Libxaac {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacLowDelaySbrTest#streams")
        @DisplayName("agrees with libxaac's fixed-point decode of the conformance streams in all but a few frames")
        void conformance(String stream) {
            var expected = readWav(libxaac().resolve(stream + ".wav"));
            var parsed = parse(stream);
            assertAgrees(stream, expected, AacTestSupport.decodeAll(parsed).planes(), frameLength(parsed));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.aac.AacLowDelaySbrTest#encoded")
        @DisplayName("agrees with libxaac's decode of 480- and 512-sample streams of libxaac and FDK AAC")
        void encoded(String name) {
            var expected = readWav(libxaac().resolve("encoded").resolve(name + ".wav"));
            var parsed = parse(name);
            assertAgrees(name, expected, AacTestSupport.decodeAll(parsed).planes(), frameLength(parsed));
        }
    }

    @Nested
    @DisplayName("against the FDK AAC decoder")
    class Fdk {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "er_eld_sbr_e_1_32, 22", "er_eld_sbr_e_1_44, 23", "er_eld_sbr_e_1_48, 22", "er_eld_sbr_e_2_32, 15",
                "er_eld_sbr_e_2_44, 16", "er_eld_sbr_e_2_48, 15", "er_eld_sbr_gh_1_32, 7", "er_eld_sbr_gh_1_44, 8",
                "er_eld_sbr_gh_1_48, 7", "er_eld_sbr_gh_2_32, 7", "er_eld_sbr_gh_2_44, 8", "er_eld_sbr_gh_2_48, 7",
                "er_eld_sbr_i_1_32, 22", "er_eld_sbr_i_1_44, 23", "er_eld_sbr_i_1_48, 22", "er_eld_sbr_i_2_32, 15",
                "er_eld_sbr_i_2_44, 16", "er_eld_sbr_i_2_48, 15", "er_eld_sbr_s_1_32, 22", "er_eld_sbr_s_1_44, 23",
                "er_eld_sbr_s_1_48, 22", "er_eld_sbr_s_2_32, 15", "er_eld_sbr_s_2_44, 16", "er_eld_sbr_s_2_48, 15",
                "fdk480d_m48, 12", "fdk480d_s48, 14", "fdk480s_m48, 14", "fdk480s_s48, 14", "fdk480s_m32, 11"
        })
        @DisplayName("matches the core band through the low delay QMF banks and the energy of the high band")
        void coreBand(String name, int crossover) {
            var expected = readWav(fdk().resolve(name + ".wav"));
            var parsed = parse(name);
            int rate;
            int frameLength;
            try (var decoder = new AacDecoder(parsed.track())) {
                rate = decoder.sampleRate();
                frameLength = decoder.frameLength();
            }
            var decoded = AacTestSupport.decodeAll(parsed).planes();
            // the SBR frequency tables span 64 subbands of half the SBR rate, which is twice the output rate of a
            // downsampled stream (480 or 512 samples per frame)
            var sbrRate = frameLength <= 512 ? 2 * rate : rate;
            var bin = 2048.0 / rate;
            var core = (int) (0.8 * crossover * sbrRate / 128.0 * bin);
            var high = (int) (1.2 * crossover * sbrRate / 128.0 * bin);
            var top = (int) (Math.min(15000, rate / 2 - 1000) * bin);
            assertEquals(expected.length, decoded.length, name + " channels");
            for (var channel = 0; channel < expected.length; channel++) {
                var spectra = Spectra.of(expected[channel], decoded[channel]);
                var snr = spectra.snr(1, core);
                assertTrue(snr > 40, name + " channel " + channel + ": core band SNR " + snr + " dB");
                var ratio = spectra.energyRatio(high, top);
                assertTrue(Math.abs(ratio) < 1.5, name + " channel " + channel + ": high band energy " + ratio + " dB");
            }
        }
    }

    @Nested
    @DisplayName("output format")
    class Format {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"er_eld_sbr_e_1_48, 48000, 1024, 1", "er_eld_sbr_s_2_44, 44100, 1024, 2",
                "er_eld_sbr_twi_1_24, 24000, 512, 1", "xaac480_m48, 48000, 960, 1", "fdk480d_s48, 48000, 960, 2",
                "fdk480s_m48, 48000, 480, 1", "fdk480s_s48, 48000, 480, 2"})
        @DisplayName("decodes at dual rate or downsampled as the ELDSpecificConfig signals, keeping the core layout")
        void format(String stream, int sampleRate, int frameLength, int channels) {
            var parsed = parse(stream);
            try (var decoder = new AacDecoder(parsed.track())) {
                assertEquals(sampleRate, decoder.sampleRate());
                assertEquals(frameLength, decoder.frameLength());
                assertEquals(channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO, decoder.layout());
                assertTrue(decoder.spectralBandReplicationSignaled());
                assertTrue(decoder.decodesSpectralBandReplication());
            }
            var decoded = AacTestSupport.decodeAll(parsed);
            assertEquals(sampleRate, decoded.sampleRate());
            assertEquals(parsed.frames().size() * frameLength, decoded.frames());
        }
    }
}
