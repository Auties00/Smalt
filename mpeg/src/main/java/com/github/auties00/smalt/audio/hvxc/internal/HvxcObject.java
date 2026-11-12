/* Ports the HVXC frame assembly of DecHvxcInit and DecHvxcFrame (dec_par.c) of the ISO/IEC 14496-3 reference
 * software (MPEG-4 Audio, subpart 2). Copyright University of Hannover, Sony Corporation; ISO/IEC 14496-3 reference
 * software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.ep.ErrorProtectionDecoder;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes an HVXC audio object from the access units of its elementary streams: the base layer and the optional
 * enhancement layer that raises a 2 kbit/s object to 4 kbit/s, each as one access unit or, for ER HVXC with
 * {@code epConfig} 1, as one instance per error sensitivity category (ISO/IEC 14496-3 subclauses 2.3 and 2.4).
 *
 * <p>The output lags the coded speech by the decoder's delay, {@link #delay()} samples.
 *
 * <p>Instances are not thread-safe.
 */
public final class HvxcObject {
    /**
     * The number of error sensitivity categories of each mode of {@link #mode(int)} ({@code hvxcNumEsc}).
     */
    private static final int[] CATEGORIES = {4, 5, 3, 4, 5, 4, 3, 5};

    /**
     * The bits of each error sensitivity category ({@code numBitsEsc}), by mode, voicing row and category.
     */
    private static final int[][][] CATEGORY_BITS = {
            {{22, 4, 4, 10, 0}, {22, 4, 4, 10, 0}, {22, 4, 4, 10, 0}, {22, 4, 4, 10, 0}},
            {{33, 22, 4, 4, 17}, {33, 22, 4, 4, 17}, {33, 22, 4, 4, 17}, {33, 22, 4, 4, 17}},
            {{12, 19, 9, 0, 0}, {12, 19, 9, 0, 0}, {12, 19, 9, 0, 0}, {12, 19, 9, 0, 0}},
            {{22, 4, 2, 0, 0}, {2, 0, 0, 0, 0}, {22, 4, 4, 10, 0}, {22, 4, 4, 10, 0}},
            {{22, 18, 0, 0, 0}, {3, 0, 0, 0, 0}, {33, 22, 4, 4, 17}, {19, 6, 0, 0, 0}},
            {{22, 4, 4, 10, 0}, {3, 0, 0, 0, 0}, {22, 4, 4, 10, 0}, {19, 6, 0, 0, 0}},
            {{0, 0, 0, 0, 0}, {0, 0, 0, 0, 0}, {12, 19, 9, 0, 0}, {12, 19, 9, 0, 0}},
            {{31, 21, 4, 4, 12}, {32, 17, 4, 4, 17}, {32, 17, 4, 4, 17}, {32, 17, 4, 4, 17}}};

    /**
     * The largest number of classes the error protection tool reports for a frame.
     */
    private static final int MAX_CLASSES = 16;

    private final HvxcConfig base;

    private final boolean twoLayers;

    private final int[] layerStreams = new int[2];

    private final int streams;

    private final ErrorProtectionDecoder[] protection;

    private final HvxcCore core;

    private final int delay;

    private final byte[] frame = new byte[10];

    private final float[] samples;

    private final boolean[] classErrors = new boolean[MAX_CLASSES];

    private final byte[][] sources = new byte[2 * 5][];

    private final int[] sourceBits = new int[2 * 5];

    private byte[][] concatenated;

    private int written;

    /**
     * Creates the decoder of an object.
     *
     * @param configs the {@code AudioSpecificConfig} of each elementary stream, in stream order: the base layer
     * (with {@code epConfig} 1, its category streams), then the enhancement layer
     * @param maxLayer the highest layer to decode, 0 for the base layer alone, -1 for every layer
     * @param lowDelay whether to decode in the low delay mode
     * @param testMode the test mode bits of {@link HvxcCore}, 0 for normal operation
     * @param speed the speed change factor, at least 0.2
     * @param pitch the pitch change factor
     * @param limits the resource limits
     * @throws MediaException.Malformed if a configuration is invalid or the configurations do not describe the
     * layers of one object
     * @throws MediaException.Unsupported if a configuration is not of an HVXC object, or maps error protection
     * classes without direct mapping
     */
    public HvxcObject(List<byte[]> configs, int maxLayer, boolean lowDelay, int testMode, float speed, float pitch,
                      MediaLimits limits) {
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("no configuration");
        }
        var parsed = new HvxcConfig[configs.size()];
        for (var i = 0; i < parsed.length; i++) {
            parsed[i] = HvxcConfig.parse(configs.get(i));
            if (i > 0 && (parsed[i].objectType() != parsed[0].objectType()
                    || parsed[i].epConfig() != parsed[0].epConfig())) {
                throw new MediaException.Malformed("HVXC layers of different object types or epConfig");
            }
        }
        this.base = parsed[0];
        if (!base.base()) {
            throw new MediaException.Malformed("the first HVXC stream is not a base layer");
        }
        var categoryStreams = base.epConfig() == 1;
        layerStreams[0] = categoryStreams ? CATEGORIES[mode(0)] : 1;
        for (var i = 1; i < layerStreams[0]; i++) {
            if (i >= parsed.length || !parsed[i].base()) {
                throw new MediaException.Malformed("ER HVXC base layer with epConfig 1 in fewer than "
                        + layerStreams[0] + " streams");
            }
        }
        layerStreams[1] = categoryStreams ? CATEGORIES[mode(1)] : 1;
        var enhancement = parsed.length >= layerStreams[0] + layerStreams[1];
        for (var i = layerStreams[0]; i < Math.min(parsed.length, layerStreams[0] + layerStreams[1]); i++) {
            if (parsed[i].base()) {
                throw new MediaException.Malformed("more than one HVXC base layer");
            }
        }
        this.twoLayers = enhancement && maxLayer != 0 && base.rateMode() == HvxcCore.RATE_2K;
        this.streams = layerStreams[0] + (twoLayers ? layerStreams[1] : 0);
        if (base.epConfig() >= 2) {
            this.protection = new ErrorProtectionDecoder[streams];
            for (var i = 0; i < streams; i++) {
                var config = parsed[i].errorProtection();
                if (config == null || config.concatenatedFrames() != base.errorProtection().concatenatedFrames()) {
                    throw new MediaException.Malformed("HVXC streams with different error protection framing");
                }
                protection[i] = new ErrorProtectionDecoder(config, limits);
            }
            this.concatenated = new byte[streams][16];
        } else {
            this.protection = null;
        }
        var scalable = base.extension() && (base.variableScalable() || twoLayers);
        this.core = new HvxcCore(base.variable(), base.rateMode(), base.extension(), scalable, base.epConfig() >= 2,
                lowDelay, testMode, speed, pitch);
        this.delay = lowDelay ? 60 : 80;
        this.samples = new float[core.maxSamples()];
    }

    /**
     * {@return the delay of the synthesis, in samples: 80 in the normal delay mode, 60 in the low delay mode}
     */
    public int delay() {
        return delay;
    }

    /**
     * {@return the number of elementary streams of the object the decoder reads, the access units of a frame}
     */
    public int streams() {
        return streams;
    }

    /**
     * {@return the number of layers decoded, 1 or 2}
     */
    public int layers() {
        return twoLayers ? 2 : 1;
    }

    /**
     * {@return the source coder frames one access unit of each stream carries, more than 1 when the error protection
     * tool concatenates frames}
     */
    public int framesPerUnit() {
        return protection == null ? 1 : base.errorProtection().concatenatedFrames();
    }

    /**
     * {@return the largest number of samples one call of {@link #decode} writes}
     */
    public int maxSamples() {
        return framesPerUnit() * core.maxSamples();
    }

    /**
     * Returns the error sensitivity category mode of a layer ({@code get_hvxc_mode}).
     */
    private int mode(int layer) {
        if (!base.variable()) {
            return switch (base.rateMode()) {
                case HvxcCore.RATE_4K -> 1;
                case HvxcCore.RATE_3K -> 7;
                default -> layer == 0 ? 0 : 2;
            };
        }
        if (base.rateMode() == HvxcCore.RATE_4K) {
            return 4;
        }
        if (layer == 0) {
            return base.rateMode() == HvxcCore.RATE_2K && base.variableScalable() ? 5 : 3;
        }
        return 6;
    }

    /**
     * Decodes the access units of one frame of each elementary stream, or of one EP frame with the error protection
     * tool.
     *
     * @param data the access units, in stream order; fewer than {@link #streams()} decode the base layer alone, and
     * fewer than the base layer streams decode nothing
     * @param lengths the length of each access unit
     * @param count the number of access units
     * @param out receives the speech, scaled so that 16-bit full scale spans {@code [-1, 1]}, at least
     * {@link #maxSamples()} samples
     * @return the number of samples written
     * @throws MediaException.LimitExceeded if an EP frame needs more memory than the limits allow
     */
    public int decode(byte[][] data, int[] lengths, int count, float[] out) {
        count = Math.min(count, streams);
        written = 0;
        if (count < layerStreams[0]) {
            return 0;
        }
        var enhanced = twoLayers && count >= streams;
        if (protection == null) {
            for (var i = 0; i < count; i++) {
                sources[i] = data[i];
                sourceBits[i] = 8 * lengths[i];
            }
            Arrays.fill(classErrors, false);
            decodeFrame(enhanced, base.epConfig() == 1, out);
            return written;
        }
        for (var i = 0; i < count; i++) {
            protection[i].decode(data[i], 0, lengths[i]);
        }
        var frames = protection[0].frames();
        for (var f = 0; f < frames; f++) {
            var first = protection[0];
            Arrays.fill(classErrors, true);
            for (var c = 0; c < Math.min(first.classCount(), MAX_CLASSES); c++) {
                classErrors[c] = !first.classIntact(f, c);
            }
            if (base.epConfig() == 3) {
                var unit = 0;
                for (var layer = 0; layer < (enhanced ? 2 : 1); layer++) {
                    var stream = protection[layer];
                    var expected = CATEGORIES[mode(layer)];
                    for (var c = 0; c < expected; c++) {
                        if (c < stream.classCount()) {
                            sources[unit] = stream.classData(f, c);
                            sourceBits[unit] = stream.classBits(f, c);
                        } else {
                            sources[unit] = null;
                            sourceBits[unit] = 0;
                        }
                        unit++;
                    }
                }
                decodeFrame(enhanced, true, out);
            } else {
                for (var i = 0; i < count; i++) {
                    var stream = protection[i];
                    var bytes = (int) Math.min((stream.frameBits(f) + 7) >>> 3, Integer.MAX_VALUE - 8);
                    if (concatenated[i].length < bytes) {
                        concatenated[i] = new byte[bytes];
                    }
                    stream.concatenate(f, concatenated[i]);
                    sources[i] = concatenated[i];
                    sourceBits[i] = (int) Math.min(stream.frameBits(f), Integer.MAX_VALUE);
                }
                decodeFrame(enhanced, false, out);
            }
        }
        return written;
    }

    /**
     * Assembles one frame from {@link #sources}, decodes it and appends its samples to the output.
     *
     * @param enhanced whether the enhancement layer is present for this frame
     * @param categories whether the sources are the instances of the error sensitivity categories
     */
    private void decodeFrame(boolean enhanced, boolean categories, float[] out) {
        Arrays.fill(frame, (byte) 0);
        if (categories) {
            mergeCategories(enhanced);
        } else {
            parse(enhanced);
        }
        var mode = enhanced ? HvxcCore.RATE_4K : base.rateMode();
        var count = core.decode(frame, mode, classErrors, samples, 0);
        for (var i = 0; i < count; i++) {
            out[written++] = samples[i] / 32768.0f;
        }
    }

    /**
     * Reads a frame from the base layer access unit and the enhancement layer one, as the syntax of the fixed and the
     * variable rate frames lays them out.
     */
    private void parse(boolean enhanced) {
        var rate = base.rateMode();
        var wide = rate != HvxcCore.RATE_2K || base.variableScalable() || twoLayers;
        var tailFromEnhancement = twoLayers;
        var tail = rate == HvxcCore.RATE_4K || twoLayers;
        if (!base.variable()) {
            copy(0, 0, 0, 40);
            if (rate != HvxcCore.RATE_2K || twoLayers) {
                copyTail(tailFromEnhancement, enhanced, rate == HvxcCore.RATE_3K);
            }
            return;
        }
        var vuv = bit(0, 0) << 1 | bit(0, 1);
        if (wide) {
            switch (vuv) {
                case 0 -> copy(0, 0, 0, 40);
                case 1 -> copy(0, 0, 0, bit(0, 2) != 0 ? 25 : 3);
                default -> {
                    copy(0, 0, 0, 40);
                    if (tail) {
                        copyTail(tailFromEnhancement, enhanced, rate == HvxcCore.RATE_3K);
                    }
                }
            }
        } else {
            copy(0, 0, 0, switch (vuv) {
                case 0 -> 28;
                case 1 -> 2;
                default -> 40;
            });
        }
    }

    /**
     * Copies the 34 or 40 bits after the first 40 of a frame: from the enhancement layer, or from the base layer of a
     * 3.7 or 4 kbit/s object.
     */
    private void copyTail(boolean fromEnhancement, boolean enhanced, boolean threeKbit) {
        var bits = threeKbit ? 34 : 40;
        if (!fromEnhancement) {
            copy(0, 40, 40, bits);
        } else if (enhanced) {
            copy(layerStreams[0], 0, 40, bits);
        }
    }

    /**
     * Joins the instances of the error sensitivity categories into one frame.
     */
    private void mergeCategories(boolean enhanced) {
        var position = 0;
        var vuv = 0;
        var update = 0;
        var unit = 0;
        for (var layer = 0; layer < (enhanced ? 2 : 1); layer++) {
            var mode = mode(layer);
            for (var esc = 0; esc < CATEGORIES[mode]; esc++, unit++) {
                var read = 0;
                if (layer == 0 && esc == 0) {
                    vuv = bit(unit, 0) << 1 | bit(unit, 1);
                    read = 2;
                    if ((mode == 4 || mode == 5) && vuv == 1) {
                        update = bit(unit, 2);
                        read = 3;
                    }
                }
                int row;
                if (mode == 4 || mode == 5) {
                    row = vuv == 3 ? 2 : vuv == 1 && update == 1 ? 3 : vuv;
                } else {
                    row = vuv;
                }
                var bits = CATEGORY_BITS[mode][row][esc];
                var total = Math.max(bits, read);
                total = Math.min(total, 80 - position);
                copy(unit, 0, position, total);
                position += total;
            }
        }
    }

    /**
     * {@return a bit of a source, 0 past its end}
     */
    private int bit(int source, int position) {
        var data = sources[source];
        if (data == null || position >= sourceBits[source] || (position >>> 3) >= data.length) {
            return 0;
        }
        return (data[position >>> 3] >>> (7 - (position & 7))) & 1;
    }

    /**
     * Copies bits of a source into {@link #frame}, reading 0 past the end of the source.
     */
    private void copy(int source, int from, int to, int count) {
        for (var i = 0; i < count && to + i < 80; i++) {
            if (bit(source, from + i) != 0) {
                frame[(to + i) >>> 3] |= (byte) (0x80 >>> ((to + i) & 7));
            }
        }
    }
}
