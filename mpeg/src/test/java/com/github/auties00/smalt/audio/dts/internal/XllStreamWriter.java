package com.github.auties00.smalt.audio.dts.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes synthetic XLL components and the extension substream frame that carries them (ETSI TS 102 114 clauses 7 and
 * 8), with binary coded residuals and neither prediction nor decorrelation: a frame with zero residuals coded against
 * the core makes a reference decoder's lossless output its fixed-point core.
 */
final class XllStreamWriter {
    private XllStreamWriter() {
    }

    /**
     * One channel set of an XLL component.
     */
    static final class ChannelSet {
        final int channels;
        final int mask;
        int residualType;
        int resolution = 24;
        int width = 24;
        int rateCode = 12;
        boolean primary = true;
        boolean hierarchical = true;
        boolean embedded = true;
        int[] downmix;
        int[][] residuals;
        /**
         * The binary part of the Rice codes of the residuals, or -1 for binary codes.
         */
        int riceBits = -1;
        /**
         * The coding tools of frequency band 0.
         */
        final Band low = new Band();
        /**
         * The coding tools of frequency band 1.
         */
        final Band high = new Band();
        /**
         * The residuals of frequency band 1, or {@code null} for a set with band 0 alone.
         */
        int[][] highResiduals;
        /**
         * The decimator history of each channel, or {@code null} for zeros.
         */
        int[][] history;
        int historyBits = 32;
        /**
         * The history width the band data claims when it differs from the width written, or 0 for that width.
         */
        int codedHistoryBits;
        /**
         * The value of {@code bXtraFreqBands}.
         */
        boolean extraBands;
        /**
         * The value of {@code bEmbDMixInExtBand}.
         */
        boolean downmixInHigh = true;

        ChannelSet(int channels, int mask) {
            this.channels = channels;
            this.mask = mask;
        }
    }

    /**
     * The coding tools of one frequency band of a channel set.
     */
    static final class Band {
        /**
         * The original channel order under pairwise decorrelation, or {@code null} without it.
         */
        int[] order;
        int[] pairCoefficients;
        /**
         * The fixed predictor order of each channel, or {@code null} for none.
         */
        int[] fixedOrders;
        /**
         * The adaptive predictor order of each channel and its quantized reflection coefficients, or {@code null}.
         */
        int[] adaptiveOrders;
        int[][] reflection;
        /**
         * The least significant bits of each channel, or {@code null} without an LSB section.
         */
        int[][] lsbs;
        int lsbBits;
        /**
         * The bit width adjustment of each channel, or {@code null} for none.
         */
        int[] adjust;
    }

    /**
     * Writes an XLL component of two segments, or four above 1024 samples per channel, which ffmpeg's limit on the
     * samples of a segment asks for.
     *
     * @param sets the channel sets
     * @param frameSamples the number of samples per channel and frequency band of the frame
     * @return the component bytes
     */
    static byte[] component(List<ChannelSet> sets, int frameSamples) {
        return component(sets, frameSamples, 0);
    }

    /**
     * Writes an XLL component.
     *
     * @param sets the channel sets
     * @param frameSamples the number of samples per channel and frequency band of the frame
     * @param crc the value of {@code nBandDataCRCEn}
     * @return the component bytes
     */
    static byte[] component(List<ChannelSet> sets, int frameSamples, int crc) {
        return component(sets, frameSamples, crc, 0);
    }

    /**
     * Writes an XLL component.
     *
     * @param sets the channel sets
     * @param frameSamples the number of samples per channel and frequency band of the frame
     * @param crc the value of {@code nBandDataCRCEn}
     * @param fixedLsbWidth the value of {@code nuFixedLSBWidth}, written when a set has least significant bits in
     *        band 0
     * @return the component bytes
     */
    static byte[] component(List<ChannelSet> sets, int frameSamples, int crc, int fixedLsbWidth) {
        var segments = frameSamples > 1024 ? 4 : 2;
        var segmentSamples = frameSamples / segments;
        var maskBits = 1;
        var bands = 1;
        var scalable = false;
        for (var set : sets) {
            maskBits = Math.max(maskBits, bitsNeeded(set.mask));
            bands = Math.max(bands, set.highResiduals != null ? 2 : 1);
            scalable |= set.low.lsbs != null;
        }
        var sizes = new int[bands * segments * sets.size()];
        var lsbSizes = new int[sets.size()][2];
        var data = new ByteArrayOutputStream();
        var largest = 1;
        var entry = 0;
        for (var band = 0; band < bands; band++) {
            for (var segment = 0; segment < segments; segment++) {
                for (var s = 0; s < sets.size(); s++) {
                    var set = sets.get(s);
                    if (band == 1 && set.highResiduals == null) {
                        continue;
                    }
                    var section = bandData(set, band, segment, segmentSamples, sets.size());
                    var tools = band == 0 ? set.low : set.high;
                    var lsb = lsbSection(tools, set.channels, segment, segmentSamples);
                    lsbSizes[s][band] = lsb.length;
                    var covered = band == 0 || crc == 3;
                    var whole = new ByteArrayOutputStream();
                    whole.writeBytes(section);
                    if (crc >= 1 && covered) {
                        whole.writeBytes(crcBytes(section));
                    }
                    whole.writeBytes(lsb);
                    if (lsb.length > 0 && crc >= 2 && covered) {
                        whole.writeBytes(crcBytes(lsb));
                    }
                    sizes[entry++] = whole.size();
                    largest = Math.max(largest, whole.size());
                    data.writeBytes(whole.toByteArray());
                }
            }
        }
        var sizeBits = bitsNeeded(largest - 1);
        var body = new ByteArrayOutputStream();
        for (var s = 0; s < sets.size(); s++) {
            body.writeBytes(setHeader(sets.get(s), maskBits, sizeBits, lsbSizes[s], scalable));
        }
        var navigation = new DtsBitWriter();
        for (var i = 0; i < entry; i++) {
            navigation.write(sizes[i] - 1, sizeBits);
        }
        navigation.alignTo(8);
        body.writeBytes(withCrc(navigation.toByteArray(), 0));
        body.writeBytes(data.toByteArray());
        var frameSizeBits = 6;
        int headerSize;
        while (true) {
            headerSize = componentHeader(sets.size(), segments, segmentSamples, sizeBits, maskBits, frameSizeBits, 0, 0,
                    crc, scalable, fixedLsbWidth).length + 2;
            if (headerSize + body.size() - 1 < 1 << frameSizeBits) {
                break;
            }
            frameSizeBits++;
        }
        var header = componentHeader(sets.size(), segments, segmentSamples, sizeBits, maskBits, frameSizeBits,
                headerSize, headerSize + body.size(), crc, scalable, fixedLsbWidth);
        var out = new ByteArrayOutputStream();
        out.writeBytes(withCrc(header, 4));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /**
     * A core extension component of an extension substream frame, before the XLL one.
     *
     * @param mask the component's bit of {@code nuCoreExtensionMask}
     * @param sizeBits the width of its size field
     * @param data the component bytes
     */
    record Extension(int mask, int sizeBits, byte[] data) {
    }

    /**
     * Writes an extension substream frame holding one XLL component.
     *
     * @param component the XLL component
     * @param totalChannels the channel count of the asset
     * @param speakerMask the speaker activity mask of the asset (Table 7-10), 8 bits
     * @param rateCode the sample rate code of the asset (Table 7-6)
     * @return the frame bytes
     */
    static byte[] substream(byte[] component, int totalChannels, int speakerMask, int rateCode) {
        return substream(component, totalChannels, speakerMask, rateCode, 0, List.of());
    }

    /**
     * Writes an extension substream frame holding core extension components and one XLL component.
     *
     * @param component the XLL component
     * @param totalChannels the channel count of the asset
     * @param speakerMask the speaker activity mask of the asset (Table 7-10), 8 bits
     * @param rateCode the sample rate code of the asset (Table 7-6)
     * @param peakBuffer the 4-bit {@code nuPeakBRCntrlBuffSzkB} code
     * @param extensions the core extension components, in the order of their mask bits
     * @return the frame bytes
     */
    static byte[] substream(byte[] component, int totalChannels, int speakerMask, int rateCode, int peakBuffer,
                            List<Extension> extensions) {
        var payload = component.length;
        for (var extension : extensions) {
            payload += extension.data().length;
        }
        var size = substreamHeader(component.length, payload, totalChannels, speakerMask, rateCode, peakBuffer,
                extensions, 0, 0).length + 2;
        var header = substreamHeader(component.length, payload, totalChannels, speakerMask, rateCode, peakBuffer,
                extensions, size, size + payload);
        var out = new ByteArrayOutputStream();
        out.writeBytes(withCrc(header, 5));
        for (var extension : extensions) {
            out.writeBytes(extension.data());
        }
        out.writeBytes(component);
        return out.toByteArray();
    }

    private static byte[] componentHeader(int sets, int segments, int segmentSamples, int sizeBits, int maskBits,
                                          int frameSizeBits, int headerSize, int frameSize, int crc,
                                          boolean scalable, int fixedLsbWidth) {
        var w = new DtsBitWriter();
        w.write(0x41A29547L, 32);
        w.write(0, 4);
        w.write(Math.max(headerSize - 1, 0), 8);
        w.write(frameSizeBits - 1, 5);
        w.write(Math.max(frameSize - 1, 0), frameSizeBits);
        w.write(sets - 1, 4);
        w.write(Integer.numberOfTrailingZeros(segments), 4);
        w.write(Integer.numberOfTrailingZeros(segmentSamples), 4);
        w.write(sizeBits - 1, 5);
        w.write(crc, 2);
        w.write(scalable ? 1 : 0, 1);
        w.write(maskBits - 1, 5);
        if (scalable) {
            w.write(fixedLsbWidth, 4);
        }
        w.alignTo(8);
        return w.toByteArray();
    }

    private static byte[] setHeader(ChannelSet set, int maskBits, int sizeBits, int[] lsbSizes, boolean scalable) {
        var measure = new DtsBitWriter();
        setFields(set, maskBits, sizeBits, lsbSizes, scalable, measure);
        var size = (int) ((measure.position() + 10 + 7) / 8) + 2;
        var w = new DtsBitWriter();
        w.write(size - 1, 10);
        setFields(set, maskBits, sizeBits, lsbSizes, scalable, w);
        w.alignTo(8);
        return withCrc(w.toByteArray(), 0);
    }

    private static void setFields(ChannelSet set, int maskBits, int sizeBits, int[] lsbSizes, boolean scalable,
                                  DtsBitWriter w) {
        w.write(set.channels - 1, 4);
        w.write(set.residualType, set.channels);
        w.write(set.resolution - 1, 5);
        w.write(set.width - 1, 5);
        w.write(set.rateCode, 4);
        w.write(0, 2);
        w.write(0, 2);
        w.write(set.primary ? 1 : 0, 1);
        w.write(set.downmix != null ? 1 : 0, 1);
        if (set.downmix != null) {
            w.write(set.embedded ? 1 : 0, 1);
            if (set.primary) {
                w.write(1, 3);
            }
        }
        w.write(set.hierarchical ? 1 : 0, 1);
        if (set.downmix != null) {
            for (var code : set.downmix) {
                w.write(code, 9);
            }
        }
        w.write(1, 1);
        w.write(set.mask, maskBits);
        if (aboveBaseRate(set.rateCode)) {
            w.write(set.extraBands ? 1 : 0, 1);
        }
        bandFields(set, set.low, w);
        if (scalable) {
            w.write(lsbSizes[0], sizeBits);
            for (var ch = 0; ch < set.channels; ch++) {
                w.write(set.low.lsbs != null ? set.low.lsbBits : 0, 4);
            }
            for (var ch = 0; ch < set.channels; ch++) {
                w.write(set.low.adjust != null ? set.low.adjust[ch] : 0, 4);
            }
        }
        // the fields of the two further bands of bXtraFreqBands repeat those of band 1
        for (var band = 1; set.highResiduals != null && band < (set.extraBands ? 4 : 2); band++) {
            bandFields(set, set.high, w);
            if (set.downmix != null && set.embedded) {
                w.write(set.downmixInHigh ? 1 : 0, 1);
            }
            w.write(set.high.lsbs != null ? 1 : 0, 1);
            if (set.high.lsbs != null) {
                w.write(lsbSizes[1], sizeBits);
                for (var ch = 0; ch < set.channels; ch++) {
                    w.write(set.high.lsbBits, 4);
                }
            }
            w.write(set.high.adjust != null ? 1 : 0, 1);
            if (set.high.adjust != null) {
                for (var ch = 0; ch < set.channels; ch++) {
                    w.write(set.high.adjust[ch], 4);
                }
            }
        }
    }

    private static void bandFields(ChannelSet set, Band band, DtsBitWriter w) {
        w.write(band.order != null ? 1 : 0, 1);
        if (band.order != null) {
            var orderBits = 0;
            while (1 << orderBits < set.channels) {
                orderBits++;
            }
            for (var ch = 0; ch < set.channels; ch++) {
                w.write(band.order[ch], orderBits);
            }
            for (var coefficient : band.pairCoefficients) {
                w.write(coefficient != 0 ? 1 : 0, 1);
                if (coefficient != 0) {
                    w.write(coefficient >= 0 ? 2L * coefficient : -2L * coefficient - 1, 7);
                }
            }
        }
        for (var ch = 0; ch < set.channels; ch++) {
            w.write(band.adaptiveOrders != null ? band.adaptiveOrders[ch] : 0, 4);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            if (band.adaptiveOrders == null || band.adaptiveOrders[ch] == 0) {
                w.write(band.fixedOrders != null ? band.fixedOrders[ch] : 0, 2);
            }
        }
        for (var ch = 0; ch < set.channels; ch++) {
            var order = band.adaptiveOrders != null ? band.adaptiveOrders[ch] : 0;
            for (var n = 0; n < order; n++) {
                var value = band.reflection[ch][n];
                w.write(value >= 0 ? 2L * value : -2L * value - 1, 8);
            }
        }
    }

    private static byte[] lsbSection(Band band, int channels, int segment, int segmentSamples) {
        if (band.lsbs == null) {
            return new byte[0];
        }
        var w = new DtsBitWriter();
        for (var ch = 0; ch < channels; ch++) {
            for (var n = segment * segmentSamples; n < (segment + 1) * segmentSamples; n++) {
                w.write(band.lsbs[ch][n], band.lsbBits);
            }
        }
        w.alignTo(8);
        return w.toByteArray();
    }

    private static byte[] crcBytes(byte[] bytes) {
        var whole = withCrc(bytes, 0);
        return new byte[]{whole[whole.length - 2], whole[whole.length - 1]};
    }

    private static void writeHistory(ChannelSet set, DtsBitWriter w) {
        w.write((set.codedHistoryBits != 0 ? set.codedHistoryBits : set.historyBits) - 1, 5);
        for (var ch = 0; ch < set.channels; ch++) {
            for (var n = 0; n < 7; n++) {
                var value = set.history != null ? set.history[ch][n] : 0;
                w.write(value & (1L << set.historyBits) - 1, set.historyBits);
            }
        }
    }

    private static byte[] bandData(ChannelSet set, int band, int segment, int segmentSamples, int sets) {
        var residuals = band == 0 ? set.residuals : set.highResiduals;
        var abitBits = set.width > 16 ? 5 : set.width > 8 ? 4 : 3;
        if ((sets > 1 || set.highResiduals != null) && abitBits < 5) {
            abitBits++;
        }
        var w = new DtsBitWriter();
        if (segment > 0) {
            w.write(0, 1);
        }
        w.write(0, 1);
        var start = segment * segmentSamples;
        if (set.riceBits >= 0) {
            return riceBandData(set, residuals, band, segment, start, segmentSamples, abitBits, w);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            w.write(0, 1);
        }
        var widths = new int[set.channels];
        for (var ch = 0; ch < set.channels; ch++) {
            var maximum = 0;
            for (var n = start; n < start + segmentSamples; n++) {
                maximum = Math.max(maximum, Math.abs(residuals[ch][n]));
            }
            widths[ch] = maximum > 0 ? Math.max(bitsNeeded(2 * maximum + 1), 2) : 0;
            if (segment == 0) {
                w.write(widths[ch] > 0 ? widths[ch] - 1 : 0, abitBits);
            }
            w.write(widths[ch] > 0 ? widths[ch] - 1 : 0, abitBits);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            if (widths[ch] > 0) {
                for (var n = start; n < start + segmentSamples; n++) {
                    var value = residuals[ch][n];
                    w.write(value >= 0 ? 2L * value : -2L * value - 1, widths[ch]);
                }
            }
        }
        if (band == 1 && segment == 0) {
            writeHistory(set, w);
        }
        w.alignTo(8);
        var bytes = w.toByteArray();
        return bytes.length > 0 ? bytes : new byte[1];
    }

    /**
     * Writes the rest of the band data of a segment whose residuals are Rice coded, after its segment type.
     */
    private static byte[] riceBandData(ChannelSet set, int[][] residuals, int band, int segment, int start,
                                       int segmentSamples, int abitBits, DtsBitWriter w) {
        for (var ch = 0; ch < set.channels; ch++) {
            w.write(1, 1);
            w.write(0, 1);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            if (segment == 0) {
                w.write(set.riceBits, abitBits);
            }
            w.write(set.riceBits, abitBits);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            for (var n = start; n < start + segmentSamples; n++) {
                var value = residuals[ch][n];
                var code = value >= 0 ? 2L * value : -2L * value - 1;
                for (var unary = code >> set.riceBits; unary > 0; unary--) {
                    w.write(0, 1);
                }
                w.write(1, 1);
                if (set.riceBits > 0) {
                    w.write(code & (1L << set.riceBits) - 1, set.riceBits);
                }
            }
        }
        if (band == 1 && segment == 0) {
            writeHistory(set, w);
        }
        w.alignTo(8);
        return w.toByteArray();
    }

    private static byte[] substreamHeader(int componentSize, int payload, int totalChannels, int speakerMask,
                                          int rateCode, int peakBuffer, List<Extension> extensions, int headerSize,
                                          int frameSize) {
        var descriptor = descriptor(componentSize, totalChannels, speakerMask, rateCode, peakBuffer, extensions);
        var w = new DtsBitWriter();
        w.write(0x64582025L, 32);
        w.write(0, 8);
        w.write(0, 2);
        w.write(0, 1);
        w.write(Math.max(headerSize - 1, 0), 8);
        w.write(Math.max(frameSize - 1, 0), 16);
        w.write(1, 1);
        w.write(2, 2);
        w.write(0, 3);
        w.write(0, 1);
        w.write(0, 3);
        w.write(0, 3);
        w.write(1, 1);
        w.write(1, 8);
        w.write(0, 1);
        w.write(payload - 1, 16);
        for (var bit : descriptor) {
            w.write(bit, 1);
        }
        w.write(1, 1);
        w.write(0, 2);
        w.write(0, 3);
        w.alignTo(8);
        return w.toByteArray();
    }

    /**
     * Writes the asset descriptor as bits, because it does not start on a byte boundary of the header.
     */
    private static int[] descriptor(int componentSize, int totalChannels, int speakerMask, int rateCode,
                                    int peakBuffer, List<Extension> extensions) {
        var w = new BitList();
        w.write(0, 9);
        w.write(0, 3);
        w.write(0, 1);
        w.write(0, 1);
        w.write(0, 1);
        w.write(23, 5);
        w.write(rateCode, 4);
        w.write(totalChannels - 1, 8);
        w.write(1, 1);
        if (totalChannels > 2) {
            w.write(0, 1);
        }
        if (totalChannels > 6) {
            w.write(0, 1);
        }
        w.write(1, 1);
        w.write(1, 2);
        w.write(speakerMask, 8);
        w.write(0, 3);
        w.write(0, 1);
        w.write(0, 1);
        w.write(0, 2);
        var mask = 0x201;
        for (var extension : extensions) {
            mask |= extension.mask();
        }
        w.write(mask, 12);
        for (var extension : extensions) {
            w.write(extension.data().length - 1, extension.sizeBits());
        }
        w.write(componentSize - 1, 16);
        w.write(1, 1);
        w.write(peakBuffer, 4);
        w.write(0, 5);
        w.write(0, 1);
        w.write(0, 16);
        while (w.bits.size() % 8 != 0) {
            w.write(0, 1);
        }
        w.write(0, 8);
        var size = w.bits.size() / 8;
        var bits = new int[w.bits.size()];
        for (var i = 0; i < bits.length; i++) {
            bits[i] = i < 9 ? (size - 1) >> (8 - i) & 1 : w.bits.get(i);
        }
        return bits;
    }

    private static final class BitList {
        final List<Integer> bits = new ArrayList<>();

        void write(long value, int count) {
            for (var i = count - 1; i >= 0; i--) {
                bits.add((int) (value >>> i & 1));
            }
        }
    }

    /**
     * {@return whether a sample rate code of Table 7-6 stands for a rate above 96 kHz}
     */
    private static boolean aboveBaseRate(int code) {
        return code == 4 || code == 8 || code == 9 || code == 14 || code == 15;
    }

    private static int bitsNeeded(int value) {
        var bits = 1;
        while (1L << bits <= value) {
            bits++;
        }
        return bits;
    }

    /**
     * Appends the two CRC bytes of a header.
     *
     * @param bytes the header without its CRC
     * @param from the index of the first covered byte
     * @return the header with its CRC
     */
    private static byte[] withCrc(byte[] bytes, int from) {
        var crc = 0xFFFF;
        for (var i = from; i < bytes.length; i++) {
            crc ^= (bytes[i] & 0xFF) << 8;
            for (var b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? crc << 1 ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        var out = new byte[bytes.length + 2];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        out[bytes.length] = (byte) (crc >> 8);
        out[bytes.length + 1] = (byte) crc;
        return out;
    }
}
