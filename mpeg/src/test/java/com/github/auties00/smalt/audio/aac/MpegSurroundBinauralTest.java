package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsBinaural;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsConfig;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsDecoder;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsUpmixer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the plumbing of the binaural rendering of MPEG Surround: the options, the data sets, the layout, the choice of
 * path by downmix and quality, and the reverb on both inputs. {@code MpegSurroundOracleTest} compares the rendering
 * itself with the reference decoder.
 */
class MpegSurroundBinauralTest {
    private static final int RATE = 48000;

    private static MpsBinaural binaural(AacBinauralHrtf hrtf, AacBinauralQuality quality) {
        return new MpsBinaural(hrtf, quality);
    }

    private static float[][] render(String resource, MpsBinaural binaural) {
        var blocks = MpegSurroundTest.blocks(MpegSurroundTest.resource(resource));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var decoder = new MpsDecoder(config, RATE, binaural);
        var length = decoder.frameLength() * Math.min(blocks.size() - 1, 12);
        return MpegSurroundTest.upmix(blocks, MpegSurroundTest.noise(config.inputChannels(), length, 0.2f, 7), RATE,
                binaural);
    }

    private static double energy(float[] samples) {
        var sum = 0.0;
        for (var sample : samples) {
            assertTrue(Float.isFinite(sample), "finite output");
            sum += sample * sample;
        }
        return sum;
    }

    private static boolean same(float[][] a, float[][] b) {
        for (var c = 0; c < a.length; c++) {
            if (!Arrays.equals(a[c], b[c])) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the left and right reverb responses of a data set, read from the resource by its documented layout}
     */
    private static float[][] reverb(AacBinauralHrtf set) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(
                MpsDecoder.class.getResourceAsStream("mps-hrtf.bin.gz")))) {
            for (var value : AacBinauralHrtf.values()) {
                var impulse = in.readInt();
                var reverb = in.readInt();
                in.readNBytes(5 * (4 * 28 + 2 * impulse) * 4);
                var out = new float[2][reverb];
                for (var side : out) {
                    for (var i = 0; i < reverb; i++) {
                        side[i] = in.readFloat();
                    }
                }
                if (value == set) {
                    return out;
                }
            }
        }
        throw new IllegalArgumentException();
    }

    @Test
    @DisplayName("keeps the loudspeaker upmix by default and carries the binaural options through the builder")
    void options() {
        var defaults = AacDecoderOptions.defaults();
        assertTrue(defaults.mpegSurroundBinaural().isEmpty());
        assertEquals(AacBinauralQuality.PARAMETRIC, defaults.mpegSurroundBinauralQuality());
        var options = AacDecoderOptions.builder().mpegSurroundBinaural(AacBinauralHrtf.VAST)
                .mpegSurroundBinauralQuality(AacBinauralQuality.FILTERED).build();
        assertEquals(AacBinauralHrtf.VAST, options.mpegSurroundBinaural().orElseThrow());
        assertEquals(AacBinauralQuality.FILTERED, options.mpegSurroundBinauralQuality());
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        assertFalse(options.equals(defaults));
        assertTrue(options.toBuilder().noMpegSurroundBinaural().build().mpegSurroundBinaural().isEmpty());
        assertThrows(NullPointerException.class, () -> AacDecoderOptions.builder().mpegSurroundBinaural(null));
        assertThrows(NullPointerException.class, () -> AacDecoderOptions.builder().mpegSurroundBinauralQuality(null));
    }

    @Test
    @DisplayName("ships the data sets of the reference software, pinned by hash")
    void dataSets() throws IOException, NoSuchAlgorithmException {
        try (var raw = new GZIPInputStream(MpsDecoder.class.getResourceAsStream("mps-hrtf.bin.gz"))) {
            var data = raw.readAllBytes();
            assertEquals(150168, data.length);
            assertEquals("9911b5698d2c683af5d888f969dae00a8993ee59fa67f6122e2546b773c2808a",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        }
        assertEquals(0, reverb(AacBinauralHrtf.KEMAR)[0].length);
        assertEquals(0, reverb(AacBinauralHrtf.VAST)[1].length);
        assertEquals(2048, reverb(AacBinauralHrtf.MPSVT)[0].length);
    }

    @Test
    @DisplayName("outputs stereo for headphones through the decoder, exactly as the spatial decoder over the downmix")
    void endToEnd() {
        var muxed = MpegSurroundTest.stream("mps-lc5151");
        var options = AacDecoderOptions.builder().mpegSurroundBinaural(AacBinauralHrtf.KEMAR).build();
        var downmix = MpegSurroundTest.decode(muxed, false);
        AacTestSupport.Decoded decoded;
        try (var decoder = new AacDecoder(muxed.track(), options)) {
            decoded = AacTestSupport.decodeAll(decoder, muxed.frames());
        }
        assertEquals(ChannelLayout.STEREO, decoded.layout());
        var expected = MpegSurroundTest.upmix(MpegSurroundTest.blocks(MpegSurroundTest.resource("mps-lc5151.bs")),
                downmix.planes(), RATE, binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.PARAMETRIC));
        MpegSurroundTest.assertSameUpmix(expected, decoded.planes());
        assertTrue(energy(decoded.planes()[0]) > 0, "signal");
    }

    @Test
    @DisplayName("reports stereo from the upmixer and keeps the loudspeakers of a 5.1 downmix")
    void layouts() {
        var binaural = binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.PARAMETRIC);
        var upmixer = new MpsUpmixer(binaural);
        upmixer.configure(MpegSurroundTest.config(MpegSurroundTest.blocks(
                MpegSurroundTest.resource("mps-3d525.bs")).getFirst()));
        assertEquals(ChannelLayout.STEREO, upmixer.layout());
        assertTrue(MpsBinaural.appliesTo(MpsConfig.TREE_5151));
        assertTrue(MpsBinaural.appliesTo(MpsConfig.TREE_525));
        assertTrue(MpsBinaural.appliesTo(MpsConfig.TREE_7272));
        assertFalse(MpsBinaural.appliesTo(MpsConfig.TREE_7571));
        assertFalse(MpsBinaural.appliesTo(MpsConfig.TREE_7572));
    }

    @Test
    @DisplayName("ignores the arbitrary tree: its stream renders to the two ears")
    void arbitraryTree() {
        var output = render("mps-at71.bs", binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.PARAMETRIC));
        assertEquals(2, output.length);
        assertTrue(energy(output[0]) > 0 && energy(output[1]) > 0, "signal in both ears");
    }

    @ParameterizedTest
    @EnumSource(AacBinauralHrtf.class)
    @DisplayName("renders a stereo downmix differently by quality, and to two different ears")
    void stereoPaths(AacBinauralHrtf hrtf) {
        var parametric = render("mps-3d525.bs", binaural(hrtf, AacBinauralQuality.PARAMETRIC));
        var filtered = render("mps-3d525.bs", binaural(hrtf, AacBinauralQuality.FILTERED));
        assertEquals(2, parametric.length);
        assertEquals(2, filtered.length);
        assertFalse(same(parametric, filtered), "the qualities differ");
        assertFalse(Arrays.equals(filtered[0], filtered[1]), "the ears differ");
        assertTrue(energy(filtered[0]) > 0 && energy(parametric[1]) > 0, "signal");
    }

    @Test
    @DisplayName("gives each data set its own rendering")
    void dataSetsDiffer() {
        var kemar = render("mps-3d525.bs", binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.FILTERED));
        var vast = render("mps-3d525.bs", binaural(AacBinauralHrtf.VAST, AacBinauralQuality.FILTERED));
        assertFalse(same(kemar, vast));
    }

    @Test
    @DisplayName("renders a mono downmix through the band parameters at either quality when the set has no reverb")
    void monoHasNoFilterbank() {
        var parametric = render("mps-lc5151.bs", binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.PARAMETRIC));
        var filtered = render("mps-lc5151.bs", binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.FILTERED));
        assertArrayEquals(parametric[0], filtered[0], 0.0f);
        assertArrayEquals(parametric[1], filtered[1], 0.0f);
    }

    @Test
    @DisplayName("adds the reverb of a set only to the band parameters, as a convolution of the delayed downmix")
    void reverbOnTimeInput() throws IOException {
        var blocks = MpegSurroundTest.blocks(MpegSurroundTest.resource("mps-lc5151.bs"));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var probe = new MpsDecoder(config, RATE);
        var length = probe.frameLength() * Math.min(blocks.size() - 1, 12);
        var downmix = MpegSurroundTest.noise(1, length, 0.2f, 11);
        var with = MpegSurroundTest.upmix(blocks, downmix, RATE,
                binaural(AacBinauralHrtf.MPSVT, AacBinauralQuality.PARAMETRIC));
        var without = MpegSurroundTest.upmix(blocks, downmix, RATE,
                binaural(AacBinauralHrtf.MPSVT, AacBinauralQuality.FILTERED));
        assertReverb(with, without, downmix[0], reverb(AacBinauralHrtf.MPSVT), 1281, probe.frameLength());
    }

    @Test
    @DisplayName("takes the reverb's input from the time samples of the QMF path, without the delay it leads by")
    void reverbOnQmfInput() throws IOException {
        var blocks = MpegSurroundTest.blocks(MpegSurroundTest.resource("mps-lc5151.bs"));
        var config = MpegSurroundTest.config(blocks.getFirst());
        var binaural = binaural(AacBinauralHrtf.MPSVT, AacBinauralQuality.PARAMETRIC);
        var decoder = new MpsDecoder(config, RATE, binaural);
        var plain = new MpsDecoder(config, RATE, binaural);
        assertTrue(decoder.needsTimeInput());
        assertFalse(new MpsDecoder(config, RATE, binaural(AacBinauralHrtf.KEMAR, AacBinauralQuality.PARAMETRIC))
                .needsTimeInput());
        var frame = decoder.frameLength();
        var slots = decoder.slots();
        var frames = Math.min(blocks.size() - 1, 8);
        var time = MpegSurroundTest.noise(1, frames * frame, 0.2f, 13);
        var scaled = new float[1][frames * frame];
        for (var i = 0; i < scaled[0].length; i++) {
            scaled[0][i] = time[0][i] * 32768.0f;
        }
        var real = MpegSurroundTest.noise(frames * slots, 64, 4000.0f, 31);
        var imag = MpegSurroundTest.noise(frames * slots, 64, 4000.0f, 32);
        var with = new float[2][frames * frame];
        var without = new float[2][frames * frame];
        for (var f = 0; f < frames; f++) {
            var inReal = new float[1][slots][];
            var inImag = new float[1][slots][];
            for (var ts = 0; ts < slots; ts++) {
                inReal[0][ts] = real[f * slots + ts].clone();
                inImag[0][ts] = imag[f * slots + ts].clone();
            }
            decoder.readFrame(new AacBitReader(blocks.get(f + 1)));
            decoder.decodeQmf(inReal, inImag, scaled, f * frame, with, f * frame);
            plain.readFrame(new AacBitReader(blocks.get(f + 1)));
            plain.decodeQmf(inReal, inImag, without, f * frame);
        }
        for (var c = 0; c < 2; c++) {
            for (var i = 0; i < with[c].length; i++) {
                with[c][i] /= 32768.0f;
                without[c][i] /= 32768.0f;
            }
        }
        assertReverb(with, without, time[0], reverb(AacBinauralHrtf.MPSVT), 1281 - 577, frame);
    }

    private static void assertReverb(float[][] with, float[][] without, float[] downmix, float[][] response, int delay,
                                     int frame) {
        var active = 0.0;
        for (var ear = 0; ear < 2; ear++) {
            for (var n = 0; n < with[ear].length; n++) {
                var expected = 0.0;
                for (var l = 0; l < response[ear].length && n - delay - l >= 0; l++) {
                    expected += response[ear][l] * downmix[n - delay - l];
                }
                var actual = with[ear][n] - without[ear][n];
                active += expected * expected;
                assertEquals(expected, actual, 2e-4, "ear " + ear + " sample " + n + " of frames of " + frame);
            }
        }
        assertTrue(active > 0, "the reverb has signal");
    }

    @Test
    @DisplayName("stays finite over a frame that is lost and keeps its parameters")
    void repeatedFrames() {
        var blocks = MpegSurroundTest.blocks(MpegSurroundTest.resource("mps-3d525.bs"));
        var config = MpegSurroundTest.config(blocks.getFirst());
        for (var quality : AacBinauralQuality.values()) {
            var decoder = new MpsDecoder(config, RATE, binaural(AacBinauralHrtf.KEMAR, quality));
            var frame = decoder.frameLength();
            var input = MpegSurroundTest.noise(2, 6 * frame, 6000.0f, 17);
            var output = new float[2][6 * frame];
            for (var f = 0; f < 6; f++) {
                if (f % 2 == 0 || f >= blocks.size() - 1) {
                    decoder.readFrame(new AacBitReader(blocks.get(Math.min(f, blocks.size() - 2) + 1)));
                } else {
                    decoder.repeatFrame();
                }
                decoder.decode(input, f * frame, output, f * frame);
            }
            assertTrue(energy(output[0]) > 0 && energy(output[1]) > 0, "signal");
        }
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}")
    @CsvSource({"mps-long5151.bs, 5, 4, KEMAR, PARAMETRIC", "mps-long5151.bs, 5, 4, MPSVT, PARAMETRIC",
            "mps-3d525.bs, 3, 5, KEMAR, PARAMETRIC", "mps-3d525.bs, 3, 5, VAST, FILTERED"})
    @DisplayName("repeats the last parameter set, already in 28 bands, like a frame holding only that set")
    void repeatHoldsLastParameterSet(String name, int boxes, int iccBoxes, AacBinauralHrtf hrtf,
                                     AacBinauralQuality quality) {
        var spatial = MpegSurroundTest.blocks(MpegSurroundTest.resource(name));
        var config = MpegSurroundTest.config(spatial.getFirst());
        var binaural = binaural(hrtf, quality);
        var last = 1;
        // the frames up to the first one of several parameter sets, which bits 1 to 3 of a frame count
        while (((spatial.get(last)[0] >> 4) & 7) == 0) {
            last++;
        }
        var length = new MpsDecoder(config, RATE, binaural).frameLength();
        var input = MpegSurroundTest.LongFrames.scaled(
                MpegSurroundTest.noise(config.inputChannels(), (last + 2) * length, 0.5f, 103));
        var repeating = new MpsDecoder(config, RATE, binaural);
        var keeping = new MpsDecoder(config, RATE, binaural);
        var expected = new float[2][(last + 2) * length];
        var actual = new float[2][(last + 2) * length];
        for (var f = 1; f <= last; f++) {
            repeating.readFrame(new AacBitReader(spatial.get(f)));
            keeping.readFrame(new AacBitReader(spatial.get(f)));
            repeating.decode(input, (f - 1) * length, actual, (f - 1) * length);
            keeping.decode(input, (f - 1) * length, expected, (f - 1) * length);
        }
        var frame = MpegSurroundTest.LongFrames.keepingFrame(boxes, iccBoxes, 0);
        for (var f = last; f < last + 2; f++) {
            repeating.repeatFrame();
            keeping.readFrame(new AacBitReader(frame));
            repeating.decode(input, f * length, actual, f * length);
            keeping.decode(input, f * length, expected, f * length);
        }
        MpegSurroundTest.assertSameUpmix(expected, actual);
        assertTrue(energy(actual[0]) > 0, "signal");
    }

    /**
     * Compares the upmixer over blocks with the spatial decoder over whole frames, on noise, so that each of its paths
     * must hand the reverb the time samples of the frame it upmixes.
     */
    private static void assertUpmixerMatchesDecoder(String name, MpsBinaural binaural, int blocksPerFrame,
                                                    int framesPerBlock, boolean qmf) {
        var spatial = MpegSurroundTest.blocks(MpegSurroundTest.resource(name));
        var config = MpegSurroundTest.config(spatial.getFirst());
        var reference = new MpsDecoder(config, RATE, binaural);
        var frame = reference.frameLength();
        var block = frame * framesPerBlock / blocksPerFrame;
        assertEquals(1024, block, "the block the stream is laid out for");
        var slots = reference.slots();
        var inputs = config.inputChannels();
        var frames = Math.min(spatial.size() - 1, 8) / (framesPerBlock * blocksPerFrame) * framesPerBlock
                * blocksPerFrame;
        assertTrue(frames >= 2, "frames");
        var time = MpegSurroundTest.noise(inputs, frames * frame, 0.2f, 41);
        var input = MpegSurroundTest.LongFrames.scaled(time);
        var real = new float[inputs][][];
        var imag = new float[inputs][][];
        for (var c = 0; c < inputs; c++) {
            real[c] = MpegSurroundTest.noise(frames * slots, 64, 4000.0f, 42 + 2 * c);
            imag[c] = MpegSurroundTest.noise(frames * slots, 64, 4000.0f, 43 + 2 * c);
        }
        var expected = new float[2][frames * frame];
        for (var f = 0; f < frames; f++) {
            reference.readFrame(new AacBitReader(spatial.get(f + 1)));
            if (!qmf) {
                reference.decode(input, f * frame, expected, f * frame);
                continue;
            }
            var inReal = new float[inputs][slots][];
            var inImag = new float[inputs][slots][];
            for (var c = 0; c < inputs; c++) {
                for (var ts = 0; ts < slots; ts++) {
                    inReal[c][ts] = real[c][f * slots + ts].clone();
                    inImag[c][ts] = imag[c][f * slots + ts].clone();
                }
            }
            reference.decodeQmf(inReal, inImag, input, f * frame, expected, f * frame);
        }
        var upmixer = new MpsUpmixer(binaural);
        upmixer.configure(config);
        var out = new float[2][block];
        var delay = blocksPerFrame - 1;
        var blockSlots = block / 64;
        var count = frames * frame / block;
        for (var k = 0; k < count; k++) {
            if (framesPerBlock > 1) {
                for (var f = 0; f < framesPerBlock; f++) {
                    var data = spatial.get(k * framesPerBlock + f + 1);
                    upmixer.addFrame(data, 0, data.length);
                }
            } else if (k % blocksPerFrame == 0) {
                var data = spatial.get(k / blocksPerFrame + 1);
                upmixer.addFrame(data, 0, data.length);
            }
            assertTrue(upmixer.prepare(RATE, block), "block " + k);
            var samples = new float[inputs][block];
            for (var c = 0; c < inputs; c++) {
                System.arraycopy(time[c], k * block, samples[c], 0, block);
            }
            if (qmf) {
                assertTrue(upmixer.acceptsQmf(), "block " + k);
                var blockReal = new float[inputs][blockSlots * 64];
                var blockImag = new float[inputs][blockSlots * 64];
                for (var c = 0; c < inputs; c++) {
                    for (var ts = 0; ts < blockSlots; ts++) {
                        System.arraycopy(real[c][k * blockSlots + ts], 0, blockReal[c], ts * 64, 64);
                        System.arraycopy(imag[c][k * blockSlots + ts], 0, blockImag[c], ts * 64, 64);
                    }
                }
                upmixer.upmixQmf(blockReal, blockImag, samples, out);
            } else {
                upmixer.upmix(samples, out);
            }
            assertEquals(k >= delay, upmixer.upmixed(), "block " + k);
            if (k < delay) {
                continue;
            }
            assertEquals(ChannelLayout.STEREO, upmixer.layout());
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < block; i++) {
                    assertEquals(expected[c][(k - delay) * block + i] / 32768.0f, out[c][i], 0.0f,
                            "block " + k + " ear " + c + " sample " + i);
                }
                assertTrue(energy(out[c]) > 0, "signal");
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({"mps-lc5151.bs, MPSVT, PARAMETRIC, 1, 1", "mps-long5151.bs, MPSVT, PARAMETRIC, 2, 1",
            "mps-short5151.bs, MPSVT, PARAMETRIC, 1, 2", "mps-3d525.bs, MPSVT, PARAMETRIC, 1, 1",
            "mps-3d525.bs, VAST, FILTERED, 1, 1"})
    @DisplayName("upmixes blocks of time samples like whole frames, whichever way frames and blocks divide")
    void upmixerOverTime(String name, AacBinauralHrtf hrtf, AacBinauralQuality quality, int blocksPerFrame,
                         int framesPerBlock) {
        assertUpmixerMatchesDecoder(name, binaural(hrtf, quality), blocksPerFrame, framesPerBlock, false);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({"mps-lc5151.bs, MPSVT, PARAMETRIC, 1, 1", "mps-long5151.bs, MPSVT, PARAMETRIC, 2, 1",
            "mps-short5151.bs, MPSVT, PARAMETRIC, 1, 2", "mps-3d525.bs, MPSVT, PARAMETRIC, 1, 1",
            "mps-3d525.bs, VAST, FILTERED, 1, 1"})
    @DisplayName("upmixes blocks of QMF slots like whole frames, handing the reverb their time samples")
    void upmixerOverQmf(String name, AacBinauralHrtf hrtf, AacBinauralQuality quality, int blocksPerFrame,
                        int framesPerBlock) {
        assertUpmixerMatchesDecoder(name, binaural(hrtf, quality), blocksPerFrame, framesPerBlock, true);
    }

    // The streams repeat one spatial frame, so that both upmixes see the same parameters whatever the offset of their
    // slots: the QMF path leads by the 577 samples of the filterbank pair it skips, and a reverb misaligned on either
    // path would stand out against the rendering.
    @ParameterizedTest(name = "{0} {2} {3}")
    @CsvSource({"mps-he5151-static, 1, KEMAR, PARAMETRIC, 36", "mps-he5151-static, 1, MPSVT, PARAMETRIC, 44",
            "mps-he525-static, 2, KEMAR, PARAMETRIC, 38", "mps-he525-static, 2, MPSVT, PARAMETRIC, 46",
            "mps-he525-static, 2, VAST, FILTERED, 40"})
    @DisplayName("renders a spectral band replication downmix in the QMF domain like the time domain rendering")
    void spectralBandReplication(String name, int downmixChannels, AacBinauralHrtf hrtf, AacBinauralQuality quality,
                                 double minimumSnr) {
        var muxed = MpegSurroundTest.stream(name);
        var options = AacDecoderOptions.builder().mpegSurroundBinaural(hrtf).mpegSurroundBinauralQuality(quality).build();
        AacTestSupport.Decoded decoded;
        try (var decoder = new AacDecoder(muxed.track(), options)) {
            decoded = AacTestSupport.decodeAll(decoder, muxed.frames());
        }
        assertEquals(ChannelLayout.STEREO, decoded.layout());
        assertEquals(RATE, decoded.sampleRate());
        var downmix = MpegSurroundTest.decode(muxed, false).planes();
        var expected = MpegSurroundTest.upmix(MpegSurroundTest.blocks(MpegSurroundTest.resource(name + ".bs")),
                Arrays.copyOf(downmix, downmixChannels), RATE, binaural(hrtf, quality));
        var lag = 577;
        for (var c = 0; c < 2; c++) {
            var signal = 0.0;
            var noise = 0.0;
            for (var i = 4 * 2048; i + lag < expected[c].length; i++) {
                var reference = expected[c][i + lag];
                var difference = reference - decoded.planes()[c][i];
                signal += reference * reference;
                noise += difference * difference;
            }
            var snr = 10 * Math.log10(signal / noise);
            assertTrue(snr >= minimumSnr, name + " " + hrtf + " " + quality + " ear " + c + ": " + snr + " dB");
        }
    }

    private static void assertFiniteUnderCorruption(byte[] header, List<byte[]> frames, MpsBinaural binaural,
                                                    Random random) {
        MpsDecoder decoder;
        MpsConfig config;
        try {
            config = MpegSurroundTest.config(header);
            decoder = new MpsDecoder(config, RATE, binaural);
        } catch (MediaException _) {
            return;
        }
        var length = decoder.frameLength();
        var count = 5;
        var input = MpegSurroundTest.noise(config.inputChannels(), count * length, 6000.0f, 53);
        var output = new float[decoder.outputChannels()][count * length];
        for (var f = 0; f < count; f++) {
            var data = frames.get(1 + f % (frames.size() - 1)).clone();
            if (f > 0) {
                var flips = 1 + random.nextInt(8);
                for (var flip = 0; flip < flips; flip++) {
                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                }
                if (random.nextInt(4) == 0) {
                    data = Arrays.copyOf(data, random.nextInt(data.length + 1));
                }
            }
            try {
                decoder.readFrame(new AacBitReader(data));
            } catch (MediaException _) {
                if (f == 0) {
                    return;
                }
                decoder.repeatFrame();
            }
            decoder.decode(input, f * length, output, f * length);
        }
        if (MpsBinaural.appliesTo(config.treeConfig())) {
            energy(output[0]);
            energy(output[1]);
        }
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({"mps-lc5151.bs, KEMAR, PARAMETRIC", "mps-lc5151.bs, MPSVT, PARAMETRIC", "mps-at71.bs, VAST, PARAMETRIC",
            "mps-3d525.bs, KEMAR, PARAMETRIC", "mps-3d525.bs, VAST, FILTERED", "mps-3d-large.bs, MPSVT, FILTERED",
            "mps-3d-large.bs, MPSVT, PARAMETRIC"})
    @DisplayName("fails corrupted frames and configurations with a media exception and stays finite")
    void corruption(String name, AacBinauralHrtf hrtf, AacBinauralQuality quality) {
        var blocks = MpegSurroundTest.blocks(MpegSurroundTest.resource(name));
        var binaural = binaural(hrtf, quality);
        var random = new Random(97);
        for (var trial = 0; trial < 12; trial++) {
            assertFiniteUnderCorruption(blocks.getFirst(), blocks, binaural, random);
        }
        for (var trial = 0; trial < 24; trial++) {
            var header = blocks.getFirst().clone();
            // the bytes after sacTimeAlignFlag, sacHeaderLen and its escape
            var first = (header[0] & 0x7F) == 127 ? 3 : 1;
            for (var flip = 0; flip < 3; flip++) {
                header[first + random.nextInt(header.length - first)] ^= (byte) (1 << random.nextInt(8));
            }
            assertFiniteUnderCorruption(header, blocks, binaural, random);
        }
    }

    @Test
    @DisplayName("gives a second decoder of the same set the same output from the shared QMF domain responses")
    void sharedResponses() {
        var binaural = binaural(AacBinauralHrtf.VAST, AacBinauralQuality.FILTERED);
        var first = render("mps-3d525.bs", binaural);
        var second = render("mps-3d525.bs", binaural);
        assertArrayEquals(first[0], second[0], 0.0f);
        assertArrayEquals(first[1], second[1], 0.0f);
        assertEquals(List.of(2, 2), List.of(first.length, second.length));
    }
}
