package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the decoder against streams of Dolby encoders. The Dolby Digital Plus Online Delivery Kit
 * carries the same 5.1 program as a 256 kbit/s E-AC-3 stream coded with the adaptive hybrid
 * transform and as a 640 kbit/s AC-3 stream, on the same block grid: the transform coefficients of
 * the AC-3 decode are the reference the vectors of each high-efficiency pointer are measured
 * against, read as the tables print them and shifted by one row, as ffmpeg 7.1 reads the table of
 * pointer 4. A stream of the Dolby encoder that shipped with Adobe Audition, attached to ffmpeg
 * ticket 10958, codes enhanced coupling from mantissa 13; its 96 kbit/s encode of the same song
 * does not use the tool.
 */
class DolbyStreamsTest {
    private static final String KIT = "https://ott.dolby.com/OnDelKits/DDP/"
            + "Dolby_Digital_Plus_Online_Delivery_Kit_v1.4.1/Test_Signals/elementary_streams/audio.zip";

    private static final String TICKET = "https://trac.ffmpeg.org/raw-attachment/ticket/10958/"
            + "08%20-%20It%27s%20Not%20Unusual_";

    // the SHA-256 of the 88 kbit/s encode, which uses enhanced coupling
    private static final String CODED = "492ae82a936ec2f88695102ecfe98dcdbef1d2f5b20a36d0712d2baeed2b8576";

    // the SHA-256 of the 96 kbit/s encode of the same song, which does not
    private static final String PLAIN = "c76ce7fa7c62360c9378c5f9078c5c984c4c2c6104b892b7ee40267f25168d9d";

    // frames of the kit streams the vector check reads, about 19 seconds
    private static final int FRAMES = 600;

    private static Path kit() {
        return Conformance.fetch("dolby-ddp-odk-1.4.1-audio.zip", KIT,
                "f94d5e3e933f756856686546763f42a8a5f16b10c264fc7af1d228acc09baa62");
    }

    private static byte[] entry(Path zip, String name) {
        try (var file = new ZipFile(zip.toFile())) {
            try (var input = file.getInputStream(file.getEntry(name))) {
                return input.readAllBytes();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] ticket(String rate, String sha256) {
        try {
            return Files.readAllBytes(Conformance.fetch("ffmpeg-trac-10958-its-not-unusual-" + rate + ".ec3",
                    TICKET + rate + ".ec3", sha256));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Object field(FrameDecoder decoder, String name) throws ReflectiveOperationException {
        Field field = FrameDecoder.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(decoder);
    }

    /**
     * Decodes the independent substream of a stream with every gain at one, in bit stream channel
     * order, and counts the frames that fail to parse.
     */
    private static float[][] decode(byte[] stream, int maxFrames, int[] invalid) {
        var decoder = new FrameDecoder(Ac3Kernels.scalar());
        var header = new FrameHeader();
        var gains = new float[6][2];
        for (var row : gains) {
            java.util.Arrays.fill(row, 1);
        }
        float[][] pcm = null;
        var position = 0;
        var frame = 0;
        while (position + 8 <= stream.length && frame < maxFrames) {
            var available = stream.length - position;
            if (!header.parse(new MsbBitReader(stream, position, available), available)) {
                position++;
                continue;
            }
            decoder.parse(stream, position, header.frameBytes, false, true);
            invalid[0] += decoder.valid() ? 0 : 1;
            if (pcm == null) {
                pcm = new float[header.channels + (header.lfe ? 1 : 0)][maxFrames * 256 * header.blocks];
            }
            decoder.reconstruct();
            decoder.synthesize(gains, 1, pcm, frame * 256 * header.blocks);
            position += header.frameBytes;
            frame++;
        }
        return pcm;
    }

    /**
     * Computes the transform coefficients of the block of a channel that starts at a sample.
     */
    private static float[] coefficients(Transform transform, float[] plane, int start) {
        var window = Ac3Tables.WINDOW;
        var windowed = new float[512];
        for (var n = 0; n < 256; n++) {
            windowed[n] = plane[start + n] * window[n];
            windowed[256 + n] = plane[start + 256 + n] * window[255 - n];
        }
        var out = new float[256];
        transform.forward(windowed, false, out);
        return out;
    }

    /**
     * Synthesizes the six block values of one vector of a pointer: the inverse DCT of the vector
     * times the exponent scale.
     */
    private static float[] blocks(int hebap, int index, float scale) {
        var vector = new float[6];
        Aht.vector(hebap, index, vector);
        var out = new float[6];
        Aht.inverse(vector, out);
        for (var m = 0; m < 6; m++) {
            out[m] *= scale;
        }
        return out;
    }

    @Test
    @DisplayName("reads the vectors of every pointer as the tables print them, as Dolby's encoder codes them")
    void vectorTables() throws ReflectiveOperationException {
        var zip = kit();
        var test = entry(zip, "audio/ChID_voices_6ch_256kbps_ddp.ec3");
        var reference = decode(entry(zip, "audio/ChID_voices_6ch_640kbps_dd.ac3"), FRAMES + 1, new int[1]);
        var transform = new Transform(Ac3Kernels.scalar());
        var decoder = new FrameDecoder(Ac3Kernels.scalar());
        var header = new FrameHeader();
        // [pointer][0 as printed, 1 shifted by one row], and the reference energy
        var error = new double[8][2];
        var energy = new double[8];
        var position = 0;
        var frame = 0;
        while (position + 8 <= test.length && frame < FRAMES) {
            var available = test.length - position;
            assertTrue(header.parse(new MsbBitReader(test, position, available), available));
            decoder.parse(test, position, header.frameBytes, false, true);
            var parsed = (float[][][]) field(decoder, "coefficients");
            var pointers = (int[][]) field(decoder, "pointers");
            var exponents = (int[][]) field(decoder, "exponents");
            var hybrid = (int[]) field(decoder, "channelAht");
            var ends = (int[]) field(decoder, "endMantissa");
            for (var ch = 0; ch < header.channels; ch++) {
                if (hybrid[ch] != -1) {
                    continue;
                }
                var expected = new float[6][];
                for (var b = 0; b < 6; b++) {
                    expected[b] = coefficients(transform, reference[ch], 256 * (6 * frame + b));
                }
                for (var bin = 0; bin < ends[ch]; bin++) {
                    var hebap = pointers[ch][bin];
                    if (hebap < 1 || hebap > 7) {
                        continue;
                    }
                    var scale = Ac3Tables.POW2_NEG[exponents[ch][bin]];
                    var count = 1 << Ac3Tables.VQ_BITS[hebap];
                    var index = -1;
                    for (var i = 0; i < count && index < 0; i++) {
                        var candidate = blocks(hebap, i, scale);
                        var match = true;
                        for (var m = 0; m < 6 && match; m++) {
                            match = Math.abs(candidate[m] - parsed[m][ch][bin]) <= 1e-6f * scale;
                        }
                        index = match ? i : -1;
                    }
                    assertTrue(index >= 0, "no vector of pointer " + hebap + " matches bin " + bin);
                    var shifted = blocks(hebap, (index + 1) % count, scale);
                    for (var m = 0; m < 6; m++) {
                        var value = expected[m][bin];
                        energy[hebap] += value * (double) value;
                        error[hebap][0] += Math.pow(value - parsed[m][ch][bin], 2);
                        error[hebap][1] += Math.pow(value - shifted[m], 2);
                    }
                }
            }
            position += header.frameBytes;
            frame++;
        }
        var snr = new double[8][2];
        for (var hebap = 1; hebap < 8; hebap++) {
            for (var reading = 0; reading < 2; reading++) {
                snr[hebap][reading] = 10 * Math.log10(energy[hebap] / error[hebap][reading]);
            }
            assertTrue(snr[hebap][0] > snr[hebap][1] + 2, "pointer " + hebap + ": " + snr[hebap][0] + " against "
                    + snr[hebap][1] + " dB shifted");
        }
        // the vectors of pointer 4 code the program as well as their place between 3 and 5 predicts
        assertTrue(snr[4][0] > snr[3][0] && snr[4][0] < snr[5][0], "pointer 4: " + snr[4][0] + " dB, pointer 3: "
                + snr[3][0] + " dB, pointer 5: " + snr[5][0] + " dB");
        assertTrue(snr[4][0] > snr[4][1] + 4, "pointer 4: " + snr[4][0] + " against " + snr[4][1] + " dB shifted");
    }

    @Test
    @DisplayName("parses every frame of a Dolby stream coupled with enhanced coupling from mantissa 13")
    void enhancedCouplingFrames() throws ReflectiveOperationException {
        var stream = ticket("88", CODED);
        var decoder = new FrameDecoder(Ac3Kernels.scalar());
        var header = new FrameHeader();
        var blocksField = FrameDecoder.class.getDeclaredField("blocks");
        blocksField.setAccessible(true);
        int position = 0, frames = 0, invalid = 0, enhanced = 0;
        while (position + 8 <= stream.length) {
            var available = stream.length - position;
            assertTrue(header.parse(new MsbBitReader(stream, position, available), available));
            decoder.parse(stream, position, header.frameBytes, false, true);
            invalid += decoder.valid() ? 0 : 1;
            for (var b = 0; b < header.blocks; b++) {
                enhanced += ((BlockState[]) blocksField.get(decoder))[b].enhancedCoupling ? 1 : 0;
            }
            position += header.frameBytes;
            frames++;
        }
        assertEquals(3745, frames);
        assertEquals(0, invalid);
        assertTrue(enhanced > 6 * frames * 9 / 10, enhanced + " blocks with enhanced coupling");
    }

    @Test
    @DisplayName("rebuilds the enhanced coupling band close to an encode of the same song without it")
    void enhancedCouplingBand() {
        var invalid = new int[1];
        var coupled = decode(DitherClearing.clear(ticket("88", CODED)), 3745, invalid);
        var plain = decode(DitherClearing.clear(ticket("96", PLAIN)), 3745, invalid);
        assertEquals(0, invalid[0]);
        var transform = new Transform(Ac3Kernels.scalar());
        double signal = 0, noise = 0;
        for (var ch = 0; ch < 2; ch++) {
            for (var start = 0; start + 512 <= coupled[ch].length; start += 256) {
                var a = coefficients(transform, plain[ch], start);
                var b = coefficients(transform, coupled[ch], start);
                // the coupled band, from mantissa 13 to the extension at 97
                for (var bin = 13; bin < 97; bin++) {
                    signal += a[bin] * (double) a[bin];
                    noise += (a[bin] - b[bin]) * (double) (a[bin] - b[bin]);
                }
            }
        }
        var snr = 10 * Math.log10(signal / noise);
        // the two encodes, at 44 and 48 kbit/s per channel and without dither, agree to about 7 dB
        // in the band; a misparse that conceals the frames it loses leaves them near 0 dB
        assertTrue(snr > 5, snr + " dB");
    }
}
