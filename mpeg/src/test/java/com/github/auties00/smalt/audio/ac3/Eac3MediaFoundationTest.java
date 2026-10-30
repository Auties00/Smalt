package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.ac3.internal.DitherClearing;
import com.github.auties00.smalt.audio.ac3.internal.StreamInspector;
import com.github.auties00.smalt.audio.ac3.internal.TransientForging;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares Smalt's E-AC-3 decoder and encoder with Dolby's decoder, the Dolby Digital Plus decoder of
 * Windows Media Foundation driven as a black box by {@code tools/testdata/mf-oracle/MfAudioDecode}
 * ({@code -Dsmalt.test.mfAudioDecode}; the tests skip without it). The decoder runs in its custom
 * mode without dialogue normalization or dynamic range control unless a test says otherwise, and
 * both decoders decode streams whose dither flags {@link DitherClearing} cleared, since their dither
 * generators differ. Comparisons that involve spectral extension are made below it, where neither
 * decoder synthesizes noise.
 */
class Eac3MediaFoundationTest {
    private static final String TICKET = "https://trac.ffmpeg.org/raw-attachment/ticket/10958/"
            + "08%20-%20It%27s%20Not%20Unusual_";

    private static final int FRAME = 1536;

    @TempDir
    Path directory;

    private static byte[] ticket(String rate, String sha256) {
        try {
            return Files.readAllBytes(Conformance.fetch("ffmpeg-trac-10958-its-not-unusual-" + rate + ".ec3",
                    TICKET + rate + ".ec3", sha256));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static float[] smalt(byte[] stream) {
        return Ac3TestSupport.decode(stream, Ac3DecoderOptions.fullRange()).interleaved();
    }

    private static float[] smalt(byte[] stream, Ac3DecoderOptions options) {
        return Ac3TestSupport.decode(stream, options).interleaved();
    }

    /**
     * Returns whether frame {@code frame} carries transient data that Dolby's decoder applies to
     * its own samples with a synthesis buffer completed only in the next frame, which a decoder
     * without output latency cannot.
     */
    private static boolean late(StreamInspector.Frame frame) {
        for (var ch = 0; ch < frame.locations().length; ch++) {
            var location = frame.locations()[ch];
            if (location < 1024 || location >= 2560) {
                continue;
            }
            var transient_ = location + 256;
            var blockStart = (transient_ / 256 - 1) * 256;
            var start = blockStart - frame.lengths()[ch] - 256;
            if (blockStart > FRAME && start < FRAME) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "{0} kbit/s")
    @CsvSource({
            "96, c76ce7fa7c62360c9378c5f9078c5c984c4c2c6104b892b7ee40267f25168d9d",
            "88, 492ae82a936ec2f88695102ecfe98dcdbef1d2f5b20a36d0712d2baeed2b8576"})
    @DisplayName("decode the transient pre-noise processing of Dolby's encoder as Dolby's decoder does")
    void auditionStreams(String rate, String sha256) {
        var driver = MediaFoundation.driver();
        var stream = DitherClearing.clear(ticket(rate, sha256));
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "ticket.ec3", stream), directory,
                -1, MediaFoundation.CUSTOM, 0);
        assertEquals(2, mf.channels());
        // below the extension band, which both decoders fill with their own noise
        var reference = MediaFoundation.lowPass(mf.samples(), 2, 4000.0 / 48000);
        var actual = MediaFoundation.lowPass(smalt(stream), 2, 4000.0 / 48000);
        // quiet frames are measured against a hundredth of the mean frame power, since the
        // decoder's output carries its own noise floor
        var snr = MediaFoundation.frameSnr(reference, actual, 2, FRAME, 0.01);
        var frames = StreamInspector.frames(stream);
        var corrected = 0;
        var checked = 0;
        var below = new ArrayList<Integer>();
        var sorted = new ArrayList<Double>();
        for (var f = 0; f < snr.length; f++) {
            var frame = frames.get(f);
            var data = Arrays.stream(frame.locations()).anyMatch(location -> location >= 0);
            corrected += data ? 1 : 0;
            // the low-pass filter spreads the difference of a late frame into the next frame
            if (late(frame) || f > 0 && late(frames.get(f - 1)) || Double.isInfinite(snr[f])) {
                continue;
            }
            checked++;
            sorted.add(snr[f]);
            assertTrue(snr[f] >= 30, "frame " + f + ": " + snr[f] + " dB against Dolby's decoder");
            if (snr[f] < 50) {
                below.add(f);
            }
        }
        sorted.sort(null);
        var median = sorted.get(sorted.size() / 2);
        System.out.printf("%s kbit/s: %d frames with pre-noise data, %d compared, median %.1f dB, below 50 dB %s%n",
                rate, corrected, checked, median, below);
        assertTrue(corrected >= 60, corrected + " frames with pre-noise data");
        assertTrue(median >= 80, "median " + median);
        // one block of the 88 kbit/s stream, which leaves enhanced coupling for a short transform,
        // decodes about 30 dB from Dolby's decoder below 400 Hz, whatever the pre-noise processing
        assertTrue(below.size() <= 1, "frames below 50 dB: " + below);
    }

    /**
     * Encodes 16 frames of stereo noise at a high rate and returns the frames, the plain stream and
     * a copy whose frames from 3 on, every other one, carry transient data for channel 0.
     */
    private static byte[][] forged(int location, int length) {
        var random = new Random(7);
        var samples = 16 * FRAME;
        var planes = new float[2][samples];
        for (var ch = 0; ch < 2; ch++) {
            for (var i = 0; i < samples; i++) {
                planes[ch][i] = (float) (random.nextGaussian() * 0.1);
            }
        }
        var options = Eac3EncoderOptions.builder().bitrate(768000).channelCoupling(false).spectralExtension(false).build();
        var plain = new ByteArrayOutputStream();
        var forged = new ByteArrayOutputStream();
        var index = 0;
        for (var packet : Eac3EncoderTest.encode(options, new AudioBuffer(ChannelLayout.STEREO, 48000, planes, samples))) {
            plain.writeBytes(packet.data());
            var hit = index >= 3 && index % 2 == 1 && index < 14;
            forged.writeBytes(hit ? TransientForging.forge(packet.data(), 0, location, length) : packet.data());
            index++;
        }
        return new byte[][]{plain.toByteArray(), forged.toByteArray()};
    }

    @ParameterizedTest(name = "transprocloc {0}, transproclen {1}")
    @CsvSource({"256, 0", "300, 100", "430, 255", "500, 0", "580, 37", "639, 200"})
    @DisplayName("place the time scaling synthesis and cross-fade it as Dolby's decoder does")
    void forgedData(int location, int length) {
        var driver = MediaFoundation.driver();
        var streams = forged(location, length);
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "forged.ec3", streams[1]), directory,
                -1, MediaFoundation.CUSTOM, 0);
        var plain = MediaFoundation.decode(driver, MediaFoundation.write(directory, "plain.ec3", streams[0]), directory,
                -1, MediaFoundation.CUSTOM, 0);
        var actual = smalt(streams[1]);
        var frames = Math.min(mf.samples().length, actual.length);
        var changed = 0;
        for (var i = 0; i < frames; i++) {
            changed += mf.samples()[i] != plain.samples()[i] ? 1 : 0;
        }
        assertTrue(changed > 500, "Dolby's decoder corrected " + changed + " samples");
        Tolerance.snr(110).assertPcm("forged transient data", Arrays.copyOf(mf.samples(), frames),
                Arrays.copyOf(actual, frames));
    }

    @ParameterizedTest(name = "transprocloc {0}")
    @CsvSource({"192", "255", "640", "1023"})
    @DisplayName("ignore the locations Dolby's decoder ignores")
    void ignoredLocations(int location) {
        var driver = MediaFoundation.driver();
        var streams = forged(location, 100);
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "forged.ec3", streams[1]), directory,
                -1, MediaFoundation.CUSTOM, 0);
        var plain = MediaFoundation.decode(driver, MediaFoundation.write(directory, "plain.ec3", streams[0]), directory,
                -1, MediaFoundation.CUSTOM, 0);
        assertArrayEquals(plain.samples(), mf.samples());
        assertArrayEquals(smalt(streams[0]), smalt(streams[1]));
    }

    @ParameterizedTest(name = "{0} blocks per frame")
    @CsvSource({"6", "3"})
    @DisplayName("send transient pre-noise processing data Dolby's decoder applies as Smalt's does")
    void encodedPreNoise(int blocks) {
        var driver = MediaFoundation.driver();
        var input = Eac3EncoderTest.onsets(ChannelLayout.STEREO, 4);
        var options = Eac3EncoderOptions.defaults().toBuilder().bitrate(128000).blockSwitching(false)
                .blocksPerFrame(blocks).preNoiseProcessing(true).build();
        var stream = DitherClearing.clear(Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, input)));
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "tpnp.ec3", stream), directory, -1,
                MediaFoundation.CUSTOM, 0);
        var cutoff = 6000.0 / 48000;
        var reference = MediaFoundation.lowPass(mf.samples(), 2, cutoff);
        var with = MediaFoundation.lowPass(smalt(stream), 2, cutoff);
        var without = MediaFoundation.lowPass(smalt(withoutPreNoise(stream)), 2, cutoff);
        var signal = 0.0;
        var errorWith = 0.0;
        var errorWithout = 0.0;
        var changed = 0;
        var length = Math.min(reference.length, with.length);
        for (var i = 0; i < length; i++) {
            if (Math.abs(with[i] - without[i]) < 1e-4) {
                continue;
            }
            changed++;
            signal += (double) reference[i] * reference[i];
            errorWith += Math.pow(reference[i] - with[i], 2);
            errorWithout += Math.pow(reference[i] - without[i], 2);
        }
        var snrWith = 10 * Math.log10(signal / errorWith);
        var snrWithout = 10 * Math.log10(signal / errorWithout);
        System.out.printf("%d blocks: %d corrected samples, %.1f dB against Dolby's decoder, %.1f dB without the "
                + "processing%n", blocks, changed, snrWith, snrWithout);
        assertTrue(changed > 2000, changed + " corrected samples");
        assertTrue(snrWith >= 38 && snrWith >= snrWithout + 10, snrWith + " dB, " + snrWithout + " dB without");
    }

    /**
     * Returns a copy of a stream of Smalt's encoder with the transient pre-noise processing data of
     * every frame left in place but out of reach of a decoder: each location moved below 1024.
     */
    private static byte[] withoutPreNoise(byte[] stream) {
        var out = stream.clone();
        var position = 0;
        var index = 0;
        var frames = StreamInspector.frames(stream);
        while (position + 4 < out.length) {
            var words = (((out[position + 2] & 0x07) << 8) | (out[position + 3] & 0xFF)) + 1;
            var frame = frames.get(index++);
            if (Arrays.stream(frame.locations()).anyMatch(location -> location >= 0)) {
                var copy = Arrays.copyOfRange(out, position, position + 2 * words);
                var stripped = TransientForging.relocate(copy, 0);
                System.arraycopy(stripped, 0, out, position, stripped.length);
            }
            position += 2 * words;
        }
        return out;
    }

    @Test
    @DisplayName("code enhanced coupling that Dolby's decoder reads as Smalt's does")
    void enhancedCoupling() {
        var driver = MediaFoundation.driver();
        var input = coupledStereo(6);
        var options = Eac3EncoderOptions.defaults().toBuilder().bitrate(96000).spectralExtension(false)
                .enhancedCoupling(true).build();
        var stream = DitherClearing.clear(Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, input)));
        var blocks = StreamInspector.frames(stream).stream().mapToInt(StreamInspector.Frame::enhancedCoupling).sum();
        assertTrue(blocks >= 50, blocks + " blocks with enhanced coupling");
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "ecpl.ec3", stream), directory, -1,
                MediaFoundation.CUSTOM, 0);
        var actual = smalt(stream);
        var length = Math.min(mf.samples().length, actual.length);
        Tolerance.snr(85).assertPcm("enhanced coupling", Arrays.copyOf(mf.samples(), length),
                Arrays.copyOf(actual, length));
    }

    /**
     * Returns a stereo signal whose channels share their content above 3 kHz, which channel coupling
     * codes well.
     */
    static AudioBuffer coupledStereo(int seconds) {
        var frames = 48000 * seconds;
        var planes = new float[2][frames];
        var random = new Random(3);
        for (var ch = 0; ch < 2; ch++) {
            for (var i = 0; i < frames; i++) {
                var t = i / 48000.0;
                var common = 0.1 * Math.sin(2 * Math.PI * 3000 * t) + 0.05 * Math.sin(2 * Math.PI * 9000 * t);
                planes[ch][i] = (float) (0.2 * Math.sin(2 * Math.PI * (220 + 110 * ch) * t) + common * (1 - 0.2 * ch)
                        + 0.01 * random.nextGaussian());
            }
        }
        return new AudioBuffer(ChannelLayout.STEREO, 48000, planes, frames);
    }

    /**
     * Returns 5.1 tones, a frequency per channel: 200 Hz for the left, 350 Hz for the right, 500 Hz
     * for the center, 650 Hz for the LFE, 800 Hz for the left surround and 950 Hz for the right one.
     */
    private static AudioBuffer tones51() {
        var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT));
        var frames = 96000;
        var planes = new float[6][frames];
        for (var ch = 0; ch < 6; ch++) {
            for (var i = 0; i < frames; i++) {
                planes[ch][i] = (float) (0.3 * Math.sin(2 * Math.PI * (200 + 150 * ch) * i / 48000.0));
            }
        }
        return new AudioBuffer(layout, 48000, planes, frames);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "AC-3 Annex D Lo/Ro 0 dB and -1.5 dB, false, MINUS_6_DB, SILENT, ZERO_DB, MINUS_1_5_DB, 0, -1.5",
            "E-AC-3 Lo/Ro -4.5 dB and -3 dB, true, MINUS_6_DB, SILENT, MINUS_4_5_DB, MINUS_3_DB, -4.5, -3"})
    @DisplayName("declare the downmix levels Dolby's decoder mixes with")
    void downmixLevels(String name, boolean enhanced, Ac3MixLevel center, Ac3MixLevel surround, Ac3MixLevel loroCenter,
                       Ac3MixLevel loroSurround, double centerDb, double surroundDb) {
        var driver = MediaFoundation.driver();
        var builder = Ac3Metadata.builder().centerMixLevel(center).surroundMixLevel(surround);
        if (loroCenter != null) {
            builder.preferredDownmix(Ac3PreferredDownmix.LO_RO).loroCenterMixLevel(loroCenter)
                    .loroSurroundMixLevel(loroSurround);
        }
        var metadata = builder.build();
        var input = tones51();
        byte[] stream;
        if (enhanced) {
            var options = Eac3EncoderOptions.of(48000, input.layout()).toBuilder().bitrate(448000).metadata(metadata).build();
            stream = Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, input));
        } else {
            var options = Ac3EncoderOptions.of(48000, input.layout()).toBuilder().bitrate(448000).metadata(metadata).build();
            stream = Ac3EncoderTest.concatenate(Ac3EncoderTest.encode(options, input));
        }
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "downmix.ac3", stream), directory, 2,
                MediaFoundation.CUSTOM, 0);
        assertEquals(2, mf.channels());
        var left = MediaFoundation.tone(mf.samples(), 2, 0, 9600, 48000, 200.0 / 48000);
        var mixedCenter = MediaFoundation.tone(mf.samples(), 2, 0, 9600, 48000, 500.0 / 48000);
        var mixedSurround = MediaFoundation.tone(mf.samples(), 2, 0, 9600, 48000, 800.0 / 48000);
        assertEquals(centerDb, 20 * Math.log10(mixedCenter / left), 0.1, "center level");
        assertEquals(surroundDb, 20 * Math.log10(mixedSurround / left), 0.1, "surround level");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"FILM_STANDARD", "MUSIC_LIGHT", "SPEECH"})
    @DisplayName("write dynamic range words Dolby's decoder applies in line mode as Smalt's does")
    void dynamicRange(Ac3DynamicRangeProfile profile) {
        var driver = MediaFoundation.driver();
        var input = Ac3EncoderTest.stereoMusic(48000, 8);
        var metadata = Ac3Metadata.builder().lineModeProfile(profile).build();
        var options = Eac3EncoderOptions.defaults().toBuilder().spectralExtension(false).metadata(metadata).build();
        var stream = DitherClearing.clear(Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, input)));
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "drc.ec3", stream), directory, -1,
                MediaFoundation.LINE, 100);
        var light = smalt(stream, Ac3DecoderOptions.defaults());
        var full = smalt(stream);
        var length = Math.min(mf.samples().length, light.length);
        Tolerance.snr(80).assertPcm("line mode", Arrays.copyOf(mf.samples(), length), Arrays.copyOf(light, length));
        var difference = 0.0;
        for (var i = 0; i < length; i++) {
            difference = Math.max(difference, Math.abs(light[i] - full[i]));
        }
        assertTrue(difference > 0.01, "the words change the output by " + difference);
    }

    @Test
    @DisplayName("let Dolby's decoder render the 5.1 core of a 9.1 program")
    void programBeyondSevenOne() {
        var driver = MediaFoundation.driver();
        var speakers = List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY,
                Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.WIDE_LEFT,
                Speaker.WIDE_RIGHT);
        var input = Eac3EncoderTest.tones(new ChannelLayout(speakers), 2);
        var options = Eac3EncoderOptions.of(48000, input.layout());
        var stream = Ac3EncoderTest.concatenate(Eac3EncoderTest.encode(options, input));
        var mf = MediaFoundation.decode(driver, MediaFoundation.write(directory, "91.ec3", stream), directory, -1,
                MediaFoundation.CUSTOM, 0);
        assertTrue(mf.channels() >= 6, mf.channels() + " channels");
        // the front channels, the center and the side surrounds of the core carry their own tones
        var outputSpeakers = new Speaker[]{Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT};
        for (var ch = 0; ch < mf.channels(); ch++) {
            var speaker = mf.channels() == 8 ? outputSpeakers[ch] : new Speaker[]{Speaker.FRONT_LEFT,
                    Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT,
                    Speaker.SIDE_RIGHT}[ch];
            if (speaker == Speaker.LOW_FREQUENCY || speaker == Speaker.BACK_LEFT || speaker == Speaker.BACK_RIGHT) {
                continue;
            }
            var frequency = Eac3EncoderTest.toneOf(input.layout(), speaker);
            var level = MediaFoundation.tone(mf.samples(), mf.channels(), ch, 9600, 48000, frequency / 48000);
            assertEquals(0.3, level, 0.01, speaker + " carries its tone");
        }
    }
}
