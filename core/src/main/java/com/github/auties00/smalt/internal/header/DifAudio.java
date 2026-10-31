package com.github.auties00.smalt.internal.header;

/**
 * Reads and writes the audio of DIF frames: the shuffled samples of the audio blocks and the AAUX source and
 * source control packs that describe them (IEC 61834-2, SMPTE ST 314 clause 4.6, SMPTE ST 370 clause 3.6).
 *
 * <p>The audio of a DIF channel is two audio blocks, the first and the second half of its DIF sequences. At 16
 * bits each audio block carries one channel, so a DIF channel carries one stereo pair; at 12 bits (the 32 kHz
 * four-channel mode of IEC 61834) each audio block carries a stereo pair. A pair is identified by its index:
 * the DIF channel at 16 bits, twice the DIF channel plus the audio block at 12 bits. Samples are interleaved
 * left and right, as signed 16-bit values.
 *
 * <p>This class is thread-safe.
 */
public final class DifAudio {
    /**
     * The sampling frequency code of 48 kHz.
     */
    public static final int SMP_48000 = 0;

    /**
     * The sampling frequency code of 44.1 kHz.
     */
    public static final int SMP_44100 = 1;

    /**
     * The sampling frequency code of 32 kHz.
     */
    public static final int SMP_32000 = 2;

    /**
     * The fewest samples a frame carries at each sampling frequency code, for 60 Hz and 50 Hz systems; the
     * {@code AF SIZE} field of the source pack adds to it.
     */
    private static final int[][] MIN_SAMPLES = {{1580, 1452, 1053}, {1896, 1742, 1264}};

    /**
     * The rates of the sampling frequency codes.
     */
    private static final int[] RATES = {48000, 44100, 32000};

    /**
     * Prevents instantiation.
     */
    private DifAudio() {
        throw new AssertionError();
    }

    /**
     * Describes the audio of a pair of a frame.
     *
     * @param sampleRate the sampling frequency in hertz
     * @param samples the number of samples per channel the frame carries
     * @param twelveBit whether the samples are coded at 12 bits (nonlinear)
     * @param valid whether the audio mode of the source pack marks the audio as valid
     */
    public record Format(int sampleRate, int samples, boolean twelveBit, boolean valid) {
    }

    /**
     * Returns the rate of a sampling frequency code.
     *
     * @param code the code, from 0 to 2
     * @return the rate in hertz
     */
    public static int rate(int code) {
        return RATES[code];
    }

    /**
     * Returns the sampling frequency code of a rate.
     *
     * @param rate the rate in hertz
     * @return the code, or -1 when DV cannot carry the rate
     */
    public static int code(int rate) {
        for (var i = 0; i < RATES.length; i++) {
            if (RATES[i] == rate) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the number of samples an audio block holds at most.
     *
     * @param profile the profile of the frame
     * @param twelveBit whether the samples are coded at 12 bits
     * @return 1620 or 1944 at 16 bits, 1080 or 1296 at 12 bits
     */
    public static int capacity(DifProfile profile, boolean twelveBit) {
        var half = profile.sequences() / 2;
        return 9 * half * (twelveBit ? 24 : 36);
    }

    /**
     * Returns the number of samples per channel an audio frame carries at a sampling frequency, from the index of
     * the audio frame in the stream.
     *
     * <p>An audio frame lasts one frame of the 525/625 and 1080-line systems and two frames of the 720-line
     * systems (SMPTE ST 370 clause 3.1.4). At 48 kHz in 60 Hz systems the five-frame sequence 1600, 1602, 1602,
     * 1602, 1602 of SMPTE ST 314 clause 4.6.2.1.5 applies; otherwise the count is the difference of the
     * rounded-down sample positions at the audio frame boundaries.
     *
     * @param profile the profile of the frames
     * @param rate the sampling frequency in hertz
     * @param frame the index of the audio frame in the stream, from 0
     * @return the count
     */
    public static int samplesPerFrame(DifProfile profile, int rate, long frame) {
        if (rate == 48000 && !profile.fiftyHz()) {
            return frame % 5 == 0 ? 1600 : 1602;
        }
        var frameRate = profile.frameRate();
        var numerator = frameRate.numerator();
        var denominator = frameRate.denominator() * (profile.progressive() ? 2 : 1);
        var start = Math.floorDiv(frame * rate * denominator, numerator);
        var end = Math.floorDiv((frame + 1) * rate * denominator, numerator);
        return (int) (end - start);
    }

    /**
     * Returns the number of pairs a frame of a profile may carry: one per DIF channel at 16 bits, two at 12 bits.
     *
     * @param profile the DV format
     * @param twelveBit whether the samples are coded at 12 bits
     * @return the pair count
     */
    public static int pairs(DifProfile profile, boolean twelveBit) {
        return profile.channels() * (twelveBit ? 2 : 1);
    }

    /**
     * Reads the source pack of the first audio block of a DIF channel.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param channel the DIF channel
     * @return the format, or {@code null} when the channel has no source pack or names a rate or quantization DV does
     * not define
     */
    public static Format format(byte[] data, int offset, DifProfile profile, int channel) {
        return format(data, offset, profile, channel, 0);
    }

    /**
     * Reads the source pack of an audio block of a DIF channel.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param channel the DIF channel
     * @param half the audio block, 0 for the first half of the sequences, 1 for the second
     * @return the format, or {@code null} when the block has no source pack or names a rate or quantization DV does
     * not define
     */
    public static Format format(byte[] data, int offset, DifProfile profile, int channel, int half) {
        var sequences = profile.sequences() / 2;
        for (var sequence = half * sequences; sequence < (half + 1) * sequences; sequence++) {
            for (var block = 0; block < 9; block++) {
                var at = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(block));
                if (at + DifProfile.BLOCK > data.length) {
                    return null;
                }
                if ((data[at + 3] & 0xFF) != DifProfile.PACK_AAUX_SOURCE) {
                    continue;
                }
                var size = data[at + 4] & 0x3F;
                var mode = data[at + 5] & 0x0F;
                var fifty = (data[at + 6] & 0x20) != 0 ? 1 : 0;
                var smp = (data[at + 7] >>> 3) & 0x07;
                var quantization = data[at + 7] & 0x07;
                if (smp > 2 || quantization > 1 || (quantization == 1 && smp != SMP_32000)) {
                    return null;
                }
                var twelve = quantization == 1;
                var samples = Math.min(MIN_SAMPLES[fifty][smp] + size, capacity(profile, twelve));
                return new Format(RATES[smp], samples, twelve, mode != 0x0F);
            }
        }
        return null;
    }

    /**
     * Copies the samples of a pair out of a frame.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param pair the pair index
     * @param twelveBit whether the samples are coded at 12 bits
     * @param samples the number of samples per channel to copy, at most {@link #capacity}
     * @param out receives the interleaved samples, {@code 2 * samples} values from {@code outOffset}
     * @param outOffset the index of the first value written
     */
    public static void read(byte[] data, int offset, DifProfile profile, int pair, boolean twelveBit, int samples,
                            short[] out, int outOffset) {
        var half = profile.sequences() / 2;
        var perBlock = 9 * half;
        var third = 3 * half;
        if (twelveBit) {
            var channel = pair >> 1;
            var base = (pair & 1) * half;
            for (var n = 0; n < samples; n++) {
                var sequence = (n / 3 + 2 * (n % 3)) % half + base;
                var block = 3 * (n % 3) + (n % perBlock) / third;
                var at = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(block))
                        + 8 + 3 * (n / perBlock);
                var low = data[at + 2] & 0xFF;
                out[outOffset + 2 * n] = (short) expand(((data[at] & 0xFF) << 4) | (low >>> 4));
                out[outOffset + 2 * n + 1] = (short) expand(((data[at + 1] & 0xFF) << 4) | (low & 0x0F));
            }
            return;
        }
        for (var n = 0; n < samples; n++) {
            var sequence = (n / 3 + 2 * (n % 3)) % half;
            var block = DifProfile.audioBlock(3 * (n % 3) + (n % perBlock) / third);
            var position = 8 + 2 * (n / perBlock);
            var left = offset + profile.blockOffset(pair, sequence, block) + position;
            var right = offset + profile.blockOffset(pair, sequence + half, block) + position;
            out[outOffset + 2 * n] = (short) (((data[left] & 0xFF) << 8) | (data[left + 1] & 0xFF));
            out[outOffset + 2 * n + 1] = (short) (((data[right] & 0xFF) << 8) | (data[right + 1] & 0xFF));
        }
    }

    /**
     * Stores the samples of a pair into a frame and writes the source and source control packs of its audio
     * blocks.
     *
     * <p>A sample of {@code 0x8000}, the audio error code, is stored as {@code 0x8001} at 16 bits. The space past
     * the samples is zeroed.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param pair the pair index
     * @param rate the sampling frequency in hertz, one {@link #code(int)} accepts
     * @param twelveBit whether to code the samples at 12 bits
     * @param in the interleaved samples
     * @param inOffset the index of the first sample value
     * @param samples the number of samples per channel, at most {@link #capacity}
     */
    public static void write(byte[] data, int offset, DifProfile profile, int pair, int rate, boolean twelveBit,
                             short[] in, int inOffset, int samples) {
        var half = profile.sequences() / 2;
        var perBlock = 9 * half;
        var third = 3 * half;
        var channel = twelveBit ? pair >> 1 : pair;
        var firstHalf = twelveBit ? pair & 1 : 0;
        var lastHalf = twelveBit ? firstHalf : 1;
        for (var h = firstHalf; h <= lastHalf; h++) {
            for (var sequence = h * half; sequence < (h + 1) * half; sequence++) {
                for (var block = 0; block < 9; block++) {
                    var at = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(block));
                    java.util.Arrays.fill(data, at + 8, at + DifProfile.BLOCK, (byte) 0);
                }
            }
            writePacks(data, offset, profile, channel, h, rate, twelveBit, samples,
                    twelveBit ? 0 : h);
        }
        if (twelveBit) {
            var base = (pair & 1) * half;
            for (var n = 0; n < samples; n++) {
                var sequence = (n / 3 + 2 * (n % 3)) % half + base;
                var block = 3 * (n % 3) + (n % perBlock) / third;
                var at = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(block))
                        + 8 + 3 * (n / perBlock);
                var left = compress(in[inOffset + 2 * n]);
                var right = compress(in[inOffset + 2 * n + 1]);
                data[at] = (byte) (left >>> 4);
                data[at + 1] = (byte) (right >>> 4);
                data[at + 2] = (byte) (((left & 0x0F) << 4) | (right & 0x0F));
            }
            return;
        }
        for (var n = 0; n < samples; n++) {
            var sequence = (n / 3 + 2 * (n % 3)) % half;
            var block = DifProfile.audioBlock(3 * (n % 3) + (n % perBlock) / third);
            var position = 8 + 2 * (n / perBlock);
            var left = offset + profile.blockOffset(channel, sequence, block) + position;
            var right = offset + profile.blockOffset(channel, sequence + half, block) + position;
            var l = in[inOffset + 2 * n] == Short.MIN_VALUE ? Short.MIN_VALUE + 1 : in[inOffset + 2 * n];
            var r = in[inOffset + 2 * n + 1] == Short.MIN_VALUE ? Short.MIN_VALUE + 1 : in[inOffset + 2 * n + 1];
            data[left] = (byte) (l >> 8);
            data[left + 1] = (byte) l;
            data[right] = (byte) (r >> 8);
            data[right + 1] = (byte) r;
        }
    }

    /**
     * Marks a DIF channel of a frame as carrying no audio: every audio block's pack is set to "no information" and
     * its samples are zeroed.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param channel the DIF channel within the frame
     */
    public static void clear(byte[] data, int offset, DifProfile profile, int channel) {
        for (var sequence = 0; sequence < profile.sequences(); sequence++) {
            for (var block = 0; block < 9; block++) {
                var at = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(block));
                java.util.Arrays.fill(data, at + 3, at + 8, (byte) 0xFF);
                java.util.Arrays.fill(data, at + 8, at + DifProfile.BLOCK, (byte) 0);
            }
        }
    }

    /**
     * Writes the source and source control packs of an audio block: in the fourth and fifth audio blocks of an
     * even sequence and the first and second of an odd one (SMPTE ST 314 table 15).
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param profile the profile of the frame
     * @param channel the DIF channel
     * @param half the audio block, 0 or 1
     * @param rate the sampling frequency in hertz
     * @param twelveBit whether the samples are coded at 12 bits
     * @param samples the number of samples per channel of the frame
     * @param mode the audio mode: the channel the block carries within its pair
     */
    private static void writePacks(byte[] data, int offset, DifProfile profile, int channel, int half, int rate,
                                   boolean twelveBit, int samples, int mode) {
        var sequences = profile.sequences() / 2;
        var fifty = profile.fiftyHz() ? 1 : 0;
        var smp = code(rate);
        var size = samples - MIN_SAMPLES[fifty][smp];
        var locked = rate == 48000 ? 0 : 0x80;
        int type;
        if (profile.highDefinition()) {
            type = profile.channels() == 4 ? 0x03 : 0x02;
        } else {
            type = profile.channels() == 2 ? 0x02 : 0x00;
        }
        for (var sequence = half * sequences; sequence < (half + 1) * sequences; sequence++) {
            var first = sequence % 2 == 0 ? 3 : 0;
            var source = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(first)) + 3;
            data[source] = (byte) DifProfile.PACK_AAUX_SOURCE;
            data[source + 1] = (byte) (locked | 0x40 | (size & 0x3F));
            data[source + 2] = (byte) ((twelveBit ? 0x20 : 0x00) | mode);
            data[source + 3] = (byte) (0xC0 | fifty << 5 | type);
            data[source + 4] = (byte) (0x80 | smp << 3 | (twelveBit ? 1 : 0));
            var control = offset + profile.blockOffset(channel, sequence, DifProfile.audioBlock(first + 1)) + 3;
            data[control] = (byte) DifProfile.PACK_AAUX_CONTROL;
            data[control + 1] = (byte) 0x3C;
            data[control + 2] = (byte) 0xCF;
            data[control + 3] = (byte) (0x80 | (profile.fiftyHz() ? 0x64 : 0x78));
            data[control + 4] = (byte) 0xFF;
        }
    }

    /**
     * Expands a 12-bit nonlinear sample to 16 bits.
     *
     * <p>Codes below 512 in magnitude are linear; each further group of 256 codes doubles the step, up to 64 for
     * the last. A negative code {@code x} maps to {@code -expand(-x - 1) - 1}.
     *
     * @param code the 12-bit two's complement code, from 0 to 4095
     * @return the 16-bit sample
     */
    public static int expand(int code) {
        var x = code >= 2048 ? code - 4096 : code;
        if (x >= 0) {
            return magnitude(x);
        }
        return -magnitude(-x - 1) - 1;
    }

    /**
     * Expands a non-negative 12-bit code.
     *
     * @param x the code, from 0 to 2047
     * @return the magnitude
     */
    private static int magnitude(int x) {
        if (x < 512) {
            return x;
        }
        var segment = x >>> 8;
        return ((x & 0xFF) + 256) << (segment - 1);
    }

    /**
     * Compresses a 16-bit sample to a 12-bit nonlinear code, the largest code whose expansion does not exceed the
     * sample's magnitude.
     *
     * @param sample the 16-bit sample
     * @return the 12-bit two's complement code, from 0 to 4095
     */
    public static int compress(int sample) {
        if (sample >= 0) {
            return code12(sample);
        }
        return (-code12(-sample - 1) - 1) & 0xFFF;
    }

    /**
     * Compresses a non-negative 16-bit magnitude.
     *
     * @param y the magnitude, from 0 to 32767
     * @return the code, from 0 to 2047
     */
    private static int code12(int y) {
        if (y < 512) {
            return y;
        }
        var segment = 2;
        while (y >= (512 << (segment - 1))) {
            segment++;
        }
        return (segment << 8) | ((y >>> (segment - 1)) - 256);
    }
}
