package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.sbr.LowDelaySbrEncoder;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the AAC-ELD encoder with low delay spectral band replication, at dual rate and downsampled: the
 * {@code AudioSpecificConfig}, the delay, the waveform of the core band and the energy of the reconstructed band
 * through this module's decoder, the spectrum against AAC-ELD without spectral band replication and AAC-LC at the
 * same bitrate, the bitrate and the contract, and, as black boxes, libxaac's and the FDK AAC decoder's decodes of the
 * streams. ffmpeg 7.1 does not decode low delay spectral band replication.
 *
 * <p>The black boxes decode streams pinned in this module's test resources ({@code eld-sbr}), which the encoder must
 * still write bit for bit ({@code -Dsmalt.test.updateEldSbrStreams=true} rewrites them);
 * {@code tools/testdata/eld-sbr-oracle.py} decodes them into {@code <conformance cache>/eld-sbr-oracle/smalt} and
 * {@code eld-sbr-fdk/smalt}. The FDK AAC decoder agrees with this decoder in the core band to about 60 to 70 dB and
 * in the energy of the high band to about a decibel (it shapes the high band's waveform differently), and libxaac's
 * fixed-point decode of the dual-rate streams to 50 to 70 dB; libxaac's downsampled decodes disagree with both
 * other decoders, so they are only reported.
 */
class AacLowDelaySbrEncoderTest {
    @TempDir
    Path directory;

    static AacEncoderOptions options(AacLowDelaySbr mode, int rate, ChannelLayout layout, int bitrate,
                                     int frameLength) {
        return AacEncoderOptions.builder().format(rate, layout).bitrate(bitrate).profile(AacProfile.ELD)
                .frameLength(frameLength).lowDelaySbr(mode).build();
    }

    static ChannelLayout layout(int channels) {
        return switch (channels) {
            case 1 -> ChannelLayout.MONO;
            case 2 -> ChannelLayout.STEREO;
            default -> ChannelLayout.SURROUND_5_1;
        };
    }

    @ParameterizedTest(name = "{0} {1} Hz, {2} channels, {3}")
    @CsvSource({"DUAL_RATE, 48000, 1, 1024", "DUAL_RATE, 44100, 2, 960", "DOWNSAMPLED, 48000, 1, 1024",
            "DOWNSAMPLED, 32000, 2, 960", "DUAL_RATE, 48000, 6, 1024"})
    @DisplayName("writes the ELDSpecificConfig with the low delay SBR flags and one sbr_header per element")
    void config(AacLowDelaySbr mode, int rate, int channels, int frameLength) {
        var bitrate = switch (channels) {
            case 1 -> 48000;
            case 2 -> 96000;
            default -> 240000;
        };
        var dual = mode == AacLowDelaySbr.DUAL_RATE;
        var options = options(mode, rate, layout(channels), bitrate, frameLength);
        var config = new AacEncoder(options).codecConfig().orElseThrow();
        var parsed = AudioSpecificConfig.parse(config);
        assertEquals(AudioSpecificConfig.AOT_ER_ELD, parsed.objectType());
        assertEquals(dual ? rate / 2 : rate, parsed.sampleRate());
        assertEquals(channels == 6 ? 6 : channels, parsed.channelConfiguration());
        assertEquals(frameLength / 2, parsed.frameLength());
        assertEquals(0, parsed.resilience());
        assertEquals(0, parsed.epConfig());
        assertTrue(parsed.sbr());
        assertEquals(rate, parsed.extensionSampleRate());
        var lowDelay = parsed.lowDelaySbr();
        assertEquals(dual, lowDelay.dualRate());
        assertFalse(lowDelay.crc());
        assertEquals(channels == 6 ? 3 : 1, lowDelay.headerOffsets().length);
        try (var decoder = new AacDecoder(com.github.auties00.smalt.track.AudioTrack.builder().id(1)
                .codec(com.github.auties00.smalt.track.Codec.AAC)
                .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(rate)).codecConfig(config).build())) {
            assertEquals(rate, decoder.sampleRate());
            assertEquals(dual ? frameLength : frameLength / 2, decoder.frameLength());
            assertTrue(decoder.spectralBandReplicationSignaled());
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {
        @ParameterizedTest(name = "{0} {1} Hz, {2} channels at {3} bps, {4}")
        @CsvSource({"DUAL_RATE, 48000, 1, 32000, 1024, 896", "DUAL_RATE, 48000, 2, 48000, 960, 848",
                "DUAL_RATE, 44100, 1, 32000, 960, 848", "DUAL_RATE, 32000, 1, 24000, 1024, 896",
                "DUAL_RATE, 48000, 2, 64000, 1024, 896", "DOWNSAMPLED, 48000, 1, 48000, 1024, 416",
                "DOWNSAMPLED, 48000, 2, 96000, 960, 392", "DOWNSAMPLED, 44100, 1, 64000, 960, 392",
                "DOWNSAMPLED, 32000, 1, 32000, 1024, 416"})
        @DisplayName("this decoder reproduces the low band delay() samples late and the energy of the high band")
        void delay(AacLowDelaySbr mode, int rate, int channels, int bitrate, int frameLength, int expectedDelay) {
            var options = options(mode, rate, layout(channels), bitrate, frameLength);
            var input = HeAacEncoderTest.music(rate, channels, 3);
            var encoded = HeAacEncoderTest.encode(options, input);
            var dual = mode == AacLowDelaySbr.DUAL_RATE;
            var packetSamples = dual ? frameLength : frameLength / 2;
            assertEquals(packetSamples, encoded.encoder().frameSize());
            var delay = encoded.encoder().delay();
            assertEquals(expectedDelay, delay);
            var decoded = HeAacEncoderTest.decode(encoded, rate);
            assertEquals(rate, decoded.sampleRate());
            assertEquals(layout(channels), decoded.layout());
            assertEquals(encoded.packets().size() * (long) packetSamples, decoded.frames());
            // the integer lag of best match is within a sample; fractionalDelay checks the delay to a quarter
            var lag = HeAacEncoderTest.lag(input[0], decoded.planes()[0], delay);
            assertTrue(Math.abs(lag - delay) <= 1, "low band alignment: lag " + lag + ", delay " + delay);
            for (var packet : encoded.packets()) {
                assertEquals(packetSamples, packet.duration());
            }
            var covered = encoded.packets().size() * (long) packetSamples - delay;
            assertTrue(covered >= input[0].length && covered < input[0].length + packetSamples, "covered " + covered);
            var coreRate = dual ? rate / 2 : rate;
            var crossover = LowDelaySbrEncoder.crossoverFrequency(coreRate, options.coding(), bitrate, dual);
            var top = options.effectiveBandwidth();
            assertTrue(top > crossover && top <= rate / 2, "stop " + top + ", crossover " + crossover);
            for (var c = 0; c < channels; c++) {
                var reference = HeAacEncoderTest.bandEnergy(input[c], rate / 2, input[c].length - rate, crossover,
                        top, rate);
                var shifted = Arrays.copyOfRange(decoded.planes()[c], delay, decoded.planes()[c].length);
                var test = HeAacEncoderTest.bandEnergy(shifted, rate / 2, input[c].length - rate, crossover, top, rate);
                var db = 10 * Math.log10(test / reference);
                System.out.println("[eld-sbr] " + mode + " " + rate + " " + channels + "ch " + bitrate + " " + frameLength
                        + ": high band " + db + " dB, crossover " + crossover + ", stop " + top);
                assertTrue(Math.abs(db) < 3, "channel " + c + " high band energy changes by " + db + " dB");
                if (top * 1.08 < rate / 2.0) {
                    var above = HeAacEncoderTest.bandEnergy(shifted, rate / 2, input[c].length - rate, top * 1.08,
                            rate / 2.0, rate);
                    assertTrue(above < 0.1 * test, "nothing above the stop frequency");
                }
                var snr = TestSignals.segmentalSnr(new float[][]{HeAacCompare.lowPass(input[c])},
                        new float[][]{HeAacCompare.lowPass(shifted)});
                assertTrue(snr > 6, "channel " + c + ": the low band resembles the input, segmental SNR " + snr);
            }
        }

        @ParameterizedTest(name = "{0} {1} Hz, {2}")
        @CsvSource({"DUAL_RATE, 48000, 1024", "DUAL_RATE, 48000, 960", "DUAL_RATE, 32000, 1024",
                "DOWNSAMPLED, 48000, 1024", "DOWNSAMPLED, 48000, 960"})
        @DisplayName("a tone comes out delay() samples late to a quarter of a sample")
        void fractionalDelay(AacLowDelaySbr mode, int rate, int frameLength) {
            var bitrate = mode == AacLowDelaySbr.DUAL_RATE ? 32000 : 48000;
            var options = options(mode, rate, ChannelLayout.MONO, bitrate, frameLength);
            // whole periods in the half-second stretches, so that the least squares phases carry no bias
            var frequency = 1000.0;
            var input = new float[1][rate * 2];
            for (var i = 0; i < input[0].length; i++) {
                input[0][i] = (float) (0.25 * Math.sin(2 * Math.PI * frequency * i / rate));
            }
            var encoded = HeAacEncoderTest.encode(options, input);
            var decoded = HeAacEncoderTest.decode(encoded, rate).planes()[0];
            var delay = encoded.encoder().delay();
            var from = rate / 2;
            var length = rate / 2;
            var inputPhase = phase(input[0], from, length, frequency, rate);
            var outputPhase = phase(decoded, from + delay, length, frequency, rate);
            var difference = inputPhase - outputPhase;
            difference -= 2 * Math.PI * Math.round(difference / (2 * Math.PI));
            var measured = delay + difference * rate / (2 * Math.PI * frequency);
            System.out.println("[eld-sbr] " + mode + " " + rate + " " + frameLength + ": tone delay " + measured);
            // the low delay banks are not linear phase, so the phase delay of a tone differs a little from delay()
            assertEquals(delay, measured, 0.25, "the tone's delay");
        }

        /** Returns the phase of a sinusoid of a frequency in a stretch of a signal, by least squares. */
        private static double phase(float[] signal, int from, int length, double frequency, int rate) {
            double cosine = 0;
            double sine = 0;
            for (var i = from; i < from + length; i++) {
                var angle = 2 * Math.PI * frequency * (i - from) / rate;
                cosine += signal[i] * Math.cos(angle);
                sine += signal[i] * Math.sin(angle);
            }
            return Math.atan2(cosine, sine);
        }

        @ParameterizedTest(name = "{0} at {1} bps")
        @CsvSource({"DUAL_RATE, 32000", "DUAL_RATE, 64000", "DOWNSAMPLED, 96000"})
        @DisplayName("constant bitrate meets the bitrate exactly over the stream")
        void constantBitrate(AacLowDelaySbr mode, int bitrate) {
            var options = options(mode, 48000, ChannelLayout.STEREO, bitrate, 1024);
            var encoded = HeAacEncoderTest.encode(options, HeAacEncoderTest.music(48000, 2, 4));
            var seconds = encoded.packets().size() * (double) encoded.encoder().frameSize() / 48000;
            assertEquals(bitrate, encoded.bytes() * 8 / seconds, bitrate * 0.02);
        }

        @Test
        @DisplayName("variable bitrate decodes and stays near its nominal bitrate")
        void variableBitrate() {
            var options = options(AacLowDelaySbr.DUAL_RATE, 48000, ChannelLayout.STEREO, 48000, 1024).toBuilder()
                    .bitrateMode(AacBitrateMode.VBR).build();
            var encoded = HeAacEncoderTest.encode(options, HeAacEncoderTest.music(48000, 2, 4));
            var seconds = encoded.packets().size() * 1024.0 / 48000;
            var actual = encoded.bytes() * 8 / seconds;
            assertTrue(actual > 24000 && actual < 96000, "VBR bitrate " + actual);
            assertEquals(48000, HeAacEncoderTest.decode(encoded, 48000).sampleRate());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AacLowDelaySbr.class)
        @DisplayName("silence decodes to near silence and clipped or NaN input stays finite")
        void edges(AacLowDelaySbr mode) {
            var options = options(mode, 48000, ChannelLayout.STEREO, mode == AacLowDelaySbr.DUAL_RATE ? 48000 : 96000,
                    1024);
            var silent = HeAacEncoderTest.decode(HeAacEncoderTest.encode(options, new float[2][48000]), 48000);
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
            for (var plane : HeAacEncoderTest.decode(HeAacEncoderTest.encode(options, loud), 48000).planes()) {
                for (var value : plane) {
                    // the input is clipped to full scale, the reconstructed band of a clipped square wave adds
                    // its harmonics with other phases, so the peaks grow but stay bounded
                    assertTrue(Float.isFinite(value) && Math.abs(value) <= 8, "sample " + value);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AacLowDelaySbr.class)
        @DisplayName("5.1 codes low delay SBR for every element but the LFE")
        void surround(AacLowDelaySbr mode) {
            var rate = 48000;
            var input = HeAacEncoderTest.music(rate, 6, 2);
            for (var i = 0; i < input[3].length; i++) {
                input[3][i] = (float) (0.3 * Math.sin(2 * Math.PI * 60 * i / rate));
            }
            var options = options(mode, rate, ChannelLayout.SURROUND_5_1, 240000, 1024);
            var encoded = HeAacEncoderTest.encode(options, input);
            var decoded = HeAacEncoderTest.decode(encoded, rate);
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layout());
            var config = AudioSpecificConfig.parse(encoded.config());
            assertEquals(3, config.lowDelaySbr().headerOffsets().length, "one sbr_header per element but the LFE");
            assertEquals(mode == AacLowDelaySbr.DUAL_RATE, config.lowDelaySbr().dualRate());
            var delay = encoded.encoder().delay();
            for (var c = 0; c < 6; c++) {
                var shifted = Arrays.copyOfRange(decoded.planes()[c], delay, decoded.planes()[c].length);
                var high = HeAacEncoderTest.bandEnergy(shifted, rate / 2, rate, 12000, 15000, rate);
                var reference = HeAacEncoderTest.bandEnergy(input[c], rate / 2, rate, 12000, 15000, rate);
                if (ChannelLayout.SURROUND_5_1.speakers().get(c) == Speaker.LOW_FREQUENCY) {
                    // the decoder runs the LFE through the low delay banks too, whose aliasing stays far below
                    var low = HeAacEncoderTest.bandEnergy(shifted, rate / 2, rate, 20, 240, rate);
                    System.out.println("[eld-sbr] LFE high band " + 10 * Math.log10(high / low) + " dB below the tone");
                    assertTrue(high < 1e-7 * low, "the LFE stays band-limited: " + high + " against " + low);
                } else {
                    var db = 10 * Math.log10(high / reference);
                    assertTrue(Math.abs(db) < 4, "channel " + c + " high band " + db + " dB");
                }
            }
        }

        @Test
        @DisplayName("a new target bitrate keeps the stream decodable and changes the frame sizes")
        void retarget() {
            var options = options(AacLowDelaySbr.DUAL_RATE, 48000, ChannelLayout.STEREO, 64000, 1024);
            var encoder = new AacEncoder(options);
            var input = HeAacEncoderTest.music(48000, 2, 4);
            var packets = new ArrayList<Packet>();
            var half = input[0].length / 2;
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, new float[][]{
                    Arrays.copyOf(input[0], half), Arrays.copyOf(input[1], half)}, half), packets::add);
            var before = packets.size();
            encoder.setTargetBitrate(40000);
            assertEquals(40000, encoder.options().bitrate());
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, new float[][]{
                    Arrays.copyOfRange(input[0], half, input[0].length),
                    Arrays.copyOfRange(input[1], half, input[1].length)}, input[0].length - half), packets::add);
            encoder.flush(packets::add);
            var first = packets.subList(2, before).stream().mapToLong(Packet::size).average().orElseThrow();
            var second = packets.subList(before + 2, packets.size()).stream().mapToLong(Packet::size).average()
                    .orElseThrow();
            assertEquals(64000 / 8.0 * 1024 / 48000, first, 64000 / 8.0 * 1024 / 48000 * 0.05);
            assertEquals(40000 / 8.0 * 1024 / 48000, second, 40000 / 8.0 * 1024 / 48000 * 0.05);
            var encoded = new HeAacEncoderTest.Encoded(encoder, packets, encoder.codecConfig().orElseThrow());
            var decoded = HeAacEncoderTest.decode(encoded, 48000);
            assertEquals(packets.size() * 1024L, decoded.frames());
        }

        @ParameterizedTest(name = "{0} {1} channels at {2} bps")
        @CsvSource({"DUAL_RATE, 1, 32000", "DUAL_RATE, 2, 48000", "DUAL_RATE, 1, 48000", "DOWNSAMPLED, 1, 48000"})
        @DisplayName("matches the spectrum of the input better than AAC-ELD and AAC-LC at the same bitrate")
        void againstFullBand(AacLowDelaySbr mode, int channels, int bitrate) {
            var rate = 48000;
            var layout = layout(channels);
            var input = HeAacEncoderTest.music(rate, channels, 4);
            var sbr = HeAacEncoderTest.encode(options(mode, rate, layout, bitrate, 1024), input);
            var eld = HeAacEncoderTest.encode(AacEncoderOptions.builder().format(rate, layout).bitrate(bitrate)
                    .profile(AacProfile.ELD).build(), input);
            var lc = HeAacEncoderTest.encode(AacEncoderOptions.of(rate, layout, bitrate), input);
            var sbrDecoded = HeAacEncoderTest.decode(sbr, rate);
            var eldDecoded = HeAacEncoderTest.decode(eld, rate);
            var lcDecoded = HeAacEncoderTest.decode(lc, rate);
            var sbrDistance = 0.0;
            var eldDistance = 0.0;
            var lcDistance = 0.0;
            for (var c = 0; c < channels; c++) {
                sbrDistance += HeAacEncoderTest.spectralDistance(input[c], sbrDecoded.planes()[c],
                        sbr.encoder().delay(), rate, 16000);
                eldDistance += HeAacEncoderTest.spectralDistance(input[c], eldDecoded.planes()[c],
                        eld.encoder().delay(), rate, 16000);
                lcDistance += HeAacEncoderTest.spectralDistance(input[c], lcDecoded.planes()[c],
                        lc.encoder().delay(), rate, 16000);
            }
            System.out.println("[eld-sbr] spectral distance " + mode + " " + channels + "ch " + bitrate + ": SBR "
                    + sbrDistance / channels + ", ELD " + eldDistance / channels + ", LC " + lcDistance / channels);
            assertTrue(sbrDistance < eldDistance, "spectral distance SBR " + sbrDistance + " ELD " + eldDistance);
            // the downsampled mode keeps the core at the input rate, so at 48 kbit/s it only comes close to AAC-LC,
            // whose 1024-sample frames code more efficiently; at dual rate it is well ahead
            var ratio = mode == AacLowDelaySbr.DUAL_RATE ? 1 : 1.2;
            assertTrue(sbrDistance < ratio * lcDistance, "spectral distance SBR " + sbrDistance + " LC " + lcDistance);
            assertTrue(sbrDistance / channels < 3, "spectral distance " + sbrDistance / channels + " dB");
        }
    }

    @Nested
    @DisplayName("black-box decoders")
    class BlackBox {
        /** The pinned streams, in this module's test resources. */
        private static final Path PINNED = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
                "audio", "aac", "eld-sbr");

        @ParameterizedTest(name = "{0}")
        @CsvSource({"smalt_d512_m48, DUAL_RATE, 48000, 1, 32000, 1024",
                "smalt_d480_s48, DUAL_RATE, 48000, 2, 48000, 960",
                "smalt_d512_m44, DUAL_RATE, 44100, 1, 32000, 1024",
                "smalt_s512_m48, DOWNSAMPLED, 48000, 1, 48000, 1024",
                "smalt_s480_s48, DOWNSAMPLED, 48000, 2, 96000, 960"})
        @DisplayName("the encoder reproduces the pinned streams the black-box decoders decoded, bit for bit")
        void pinned(String name, AacLowDelaySbr mode, int rate, int channels, int bitrate, int frameLength) {
            var options = options(mode, rate, layout(channels), bitrate, frameLength);
            var encoded = HeAacEncoderTest.encode(options, HeAacEncoderTest.music(rate, channels, 3));
            var raw = raw(encoded);
            var meta = meta(encoded, rate);
            if (Boolean.getBoolean("smalt.test.updateEldSbrStreams")) {
                write(PINNED.resolve(name + ".raw"), raw);
                write(PINNED.resolve(name + ".meta"), meta.getBytes(StandardCharsets.US_ASCII));
            }
            var hint = " (rewrite the pinned streams with -Dsmalt.test.updateEldSbrStreams=true; CI then decodes them"
                    + " again with tools/testdata/eld-sbr-oracle.py)";
            assertArrayEquals(resource(name + ".raw"), raw, name + " differs from the pinned stream" + hint);
            assertEquals(new String(resource(name + ".meta"), StandardCharsets.US_ASCII), meta, name + hint);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"smalt_d512_m48, DUAL_RATE, 48000, 1, 32000, 1024",
                "smalt_d480_s48, DUAL_RATE, 48000, 2, 48000, 960",
                "smalt_d512_m44, DUAL_RATE, 44100, 1, 32000, 1024",
                "smalt_s512_m48, DOWNSAMPLED, 48000, 1, 48000, 1024",
                "smalt_s480_s48, DOWNSAMPLED, 48000, 2, 96000, 960"})
        @DisplayName("libxaac and the FDK AAC decoder decode the pinned streams as this decoder does")
        void oracles(String name, AacLowDelaySbr mode, int rate, int channels, int bitrate, int frameLength) {
            var stream = AacTestSupport.parseRaw(resource(name + ".raw"),
                    new String(resource(name + ".meta"), StandardCharsets.US_ASCII));
            var libxaac = AacLowDelaySbrTest.readWav(AacLowDelaySbrTest.libxaac().resolve("smalt")
                    .resolve(name + ".wav"));
            var dual = mode == AacLowDelaySbr.DUAL_RATE;
            var coreRate = dual ? rate / 2 : rate;
            var decoded = AacTestSupport.decodeAll(stream).planes();
            var fdk = AacLowDelaySbrTest.readWav(AacLowDelaySbrTest.fdk().resolve("smalt").resolve(name + ".wav"));
            var options = options(mode, rate, layout(channels), bitrate, frameLength);
            var crossover = LowDelaySbrEncoder.crossoverFrequency(coreRate, options.coding(), bitrate, dual);
            var bin = 2048.0 / rate;
            var core = (int) (0.8 * crossover * bin);
            var high = (int) (1.2 * crossover * bin);
            var top = (int) (Math.min(15000, rate / 2 - 1000) * bin);
            assertEquals(channels, fdk.length, name + " channels");
            assertEquals(channels, libxaac.length, name + " channels");
            var failures = new ArrayList<String>();
            for (var c = 0; c < channels; c++) {
                var againstFdk = AacLowDelaySbrTest.Spectra.of(fdk[c], decoded[c]);
                var againstLibxaac = AacLowDelaySbrTest.Spectra.of(libxaac[c], decoded[c]);
                var between = AacLowDelaySbrTest.Spectra.of(fdk[c], libxaac[c]);
                var fdkCore = againstFdk.snr(1, core);
                var fdkHigh = againstFdk.energyRatio(high, top);
                var libxaacSnr = againstLibxaac.snr(1, 1024);
                var libxaacHigh = againstLibxaac.energyRatio(high, top);
                System.out.println("[eld-sbr] " + name + " channel " + c + ": FDK core SNR " + fdkCore + " dB, high "
                        + fdkHigh + " dB; libxaac SNR " + libxaacSnr + " dB, high " + libxaacHigh
                        + " dB; libxaac against FDK core SNR " + between.snr(1, core) + " dB, high "
                        + between.energyRatio(high, top) + " dB");
                if (!(fdkCore > 55) || !(Math.abs(fdkHigh) < 1.5)) {
                    failures.add("channel " + c + ": FDK core band SNR " + fdkCore + " dB, high band " + fdkHigh);
                }
                // libxaac's downsampled decodes of these streams differ from both other decoders, which agree,
                // so only its dual-rate decodes are a reference; it decodes in fixed point to 16 bits
                if (dual && (!(libxaacSnr > 50) || !(Math.abs(libxaacHigh) < 0.5))) {
                    failures.add("channel " + c + ": libxaac SNR " + libxaacSnr + " dB, high band " + libxaacHigh);
                }
            }
            assertEquals(List.of(), failures, name);
        }

        @Test
        @DisplayName("the M4A file validates, and ffmpeg 7.1 rejects its low delay SBR rather than decoding the core alone")
        void ffmpeg() {
            var options = options(AacLowDelaySbr.DUAL_RATE, 48000, ChannelLayout.MONO, 32000, 1024);
            var encoded = HeAacEncoderTest.encode(options, HeAacEncoderTest.music(48000, 1, 1));
            var test = new HeAacEncoderTest();
            test.directory = directory;
            var file = test.mp4("eld-sbr", encoded, options);
            Validators.assertValid(file);
            Oracle.assume(Oracle.Tool.FFMPEG);
            var result = Oracle.execute(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), null, List.of("-v", "warning", "-i",
                    file.toString(), "-f", "f32le", "-"));
            // when ffmpeg learns low delay SBR, this test turns into a comparison of its decode
            assertNotEquals(0, result.exitCode(), "ffmpeg decodes low delay SBR now");
            assertTrue(result.stderr().contains("Low Delay SBR"), result.stderr());
        }

        private static byte[] raw(HeAacEncoderTest.Encoded encoded) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(encoded.config());
            for (var frame : encoded.frames()) {
                out.writeBytes(frame);
            }
            return out.toByteArray();
        }

        private static String meta(HeAacEncoderTest.Encoded encoded, int rate) {
            var meta = new StringBuilder();
            meta.append("-dec_info_init:").append(encoded.config().length).append('\n');
            meta.append("-g_track_count:1\n");
            meta.append("-ia_mp4_stsz_entries:").append(encoded.frames().size()).append('\n');
            meta.append("-media_time_scale:").append(rate).append('\n');
            for (var frame : encoded.frames()) {
                meta.append("-ia_mp4_stsz_size:").append(frame.length).append('\n');
            }
            return meta.toString();
        }

        private static byte[] resource(String name) {
            try (var input = AacLowDelaySbrEncoderTest.class.getResourceAsStream("eld-sbr/" + name)) {
                if (input == null) {
                    throw new AssertionError("no pinned stream " + name + " (write it with"
                            + " -Dsmalt.test.updateEldSbrStreams=true)");
                }
                return input.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static void write(Path file, byte[] data) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, data);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("rejects what low delay SBR cannot code")
        void rejects() {
            var builder = AacEncoderOptions.builder().profile(AacProfile.ELD).lowDelaySbr(AacLowDelaySbr.DUAL_RATE);
            assertThrows(IllegalArgumentException.class, () -> builder.format(48000, ChannelLayout.MONO)
                    .bitrate(8000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.format(16000, ChannelLayout.MONO)
                    .bitrate(16000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.format(44101, ChannelLayout.MONO)
                    .bitrate(32000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.format(48000, ChannelLayout.MONO)
                    .bitrate(32000).bandwidth(10000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.automaticBandwidth()
                    .format(48000, ChannelLayout.SURROUND_7_1).bitrate(256000).build());
            assertThrows(IllegalArgumentException.class, () -> builder.format(48000, ChannelLayout.MONO)
                    .bitrate(32000).frameLength(512).build());
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.builder()
                    .lowDelaySbr(AacLowDelaySbr.DUAL_RATE).format(48000, ChannelLayout.MONO).bitrate(32000).build());
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.builder().profile(AacProfile.HE)
                    .lowDelaySbr(AacLowDelaySbr.DOWNSAMPLED).format(48000, ChannelLayout.MONO).bitrate(32000).build());
            assertThrows(IllegalArgumentException.class, () -> AacEncoderOptions.builder().profile(AacProfile.ELD)
                    .lowDelaySbr(AacLowDelaySbr.DOWNSAMPLED).format(48000, ChannelLayout.MONO).bitrate(24000).build());
            assertThrows(NullPointerException.class, () -> AacEncoderOptions.builder().lowDelaySbr(null));
        }

        @Test
        @DisplayName("keeps the mode through toBuilder, equals and hashCode, and noLowDelaySbr removes it")
        void builder() {
            var options = options(AacLowDelaySbr.DUAL_RATE, 48000, ChannelLayout.STEREO, 48000, 1024);
            assertEquals(AacLowDelaySbr.DUAL_RATE, options.lowDelaySbr().orElseThrow());
            var copy = options.toBuilder().build();
            assertEquals(options, copy);
            assertEquals(options.hashCode(), copy.hashCode());
            assertTrue(options.toString().contains("lowDelaySbr=DUAL_RATE"), options.toString());
            assertNotEquals(options, options.toBuilder().lowDelaySbr(AacLowDelaySbr.DOWNSAMPLED).bitrate(96000)
                    .build());
            var plain = options.toBuilder().noLowDelaySbr().build();
            assertTrue(plain.lowDelaySbr().isEmpty());
            assertNotEquals(options, plain);
            assertTrue(AacEncoderOptions.defaults().lowDelaySbr().isEmpty());
            assertEquals(512, new AacEncoder(plain).frameSize());
            assertEquals(1024, new AacEncoder(options).frameSize());
            assertEquals(512, new AacEncoder(options.toBuilder().lowDelaySbr(AacLowDelaySbr.DOWNSAMPLED)
                    .bitrate(96000).build()).frameSize());
        }

        @Test
        @DisplayName("the bandwidth is the stop frequency of the tuning, which grows with the bitrate")
        void bandwidth() {
            var low = options(AacLowDelaySbr.DUAL_RATE, 48000, ChannelLayout.MONO, 24000, 1024);
            var high = options(AacLowDelaySbr.DUAL_RATE, 48000, ChannelLayout.MONO, 56000, 1024);
            assertTrue(low.effectiveBandwidth() < high.effectiveBandwidth(),
                    low.effectiveBandwidth() + " " + high.effectiveBandwidth());
            assertTrue(high.effectiveBandwidth() <= 24000);
            var downsampled = options(AacLowDelaySbr.DOWNSAMPLED, 48000, ChannelLayout.MONO, 48000, 1024);
            assertTrue(downsampled.effectiveBandwidth() <= 24000);
            assertTrue(downsampled.bandwidth().isEmpty());
        }
    }
}
