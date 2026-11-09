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

        ChannelSet(int channels, int mask) {
            this.channels = channels;
            this.mask = mask;
        }
    }

    /**
     * Writes an XLL component of two segments, or four above 1024 samples per channel, which ffmpeg's limit on the
     * samples of a segment asks for.
     *
     * @param sets the channel sets
     * @param frameSamples the number of samples per channel of the frame
     * @return the component bytes
     */
    static byte[] component(List<ChannelSet> sets, int frameSamples) {
        var segments = frameSamples > 1024 ? 4 : 2;
        var segmentSamples = frameSamples / segments;
        var maskBits = 1;
        for (var set : sets) {
            maskBits = Math.max(maskBits, bitsNeeded(set.mask));
        }
        var body = new ByteArrayOutputStream();
        for (var set : sets) {
            body.writeBytes(setHeader(set, maskBits));
        }
        var sizes = new int[segments][sets.size()];
        var data = new ByteArrayOutputStream();
        var largest = 1;
        for (var segment = 0; segment < segments; segment++) {
            for (var s = 0; s < sets.size(); s++) {
                var band = bandData(sets.get(s), segment, segmentSamples, sets.size());
                sizes[segment][s] = band.length;
                largest = Math.max(largest, band.length);
                data.writeBytes(band);
            }
        }
        var sizeBits = bitsNeeded(largest - 1);
        var navigation = new DtsBitWriter();
        for (var row : sizes) {
            for (var size : row) {
                navigation.write(size - 1, sizeBits);
            }
        }
        navigation.alignTo(8);
        body.writeBytes(withCrc(navigation.toByteArray(), 0));
        body.writeBytes(data.toByteArray());
        var frameSizeBits = 6;
        int headerSize;
        while (true) {
            headerSize = componentHeader(sets.size(), segments, segmentSamples, sizeBits, maskBits, frameSizeBits, 0, 0)
                    .length + 2;
            if (headerSize + body.size() - 1 < 1 << frameSizeBits) {
                break;
            }
            frameSizeBits++;
        }
        var header = componentHeader(sets.size(), segments, segmentSamples, sizeBits, maskBits, frameSizeBits,
                headerSize, headerSize + body.size());
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
                                          int frameSizeBits, int headerSize, int frameSize) {
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
        w.write(0, 2);
        w.write(0, 1);
        w.write(maskBits - 1, 5);
        w.alignTo(8);
        return w.toByteArray();
    }

    private static byte[] setHeader(ChannelSet set, int maskBits) {
        var measure = new DtsBitWriter();
        setFields(set, maskBits, measure);
        var size = (int) ((measure.position() + 10 + 7) / 8) + 2;
        var w = new DtsBitWriter();
        w.write(size - 1, 10);
        setFields(set, maskBits, w);
        w.alignTo(8);
        return withCrc(w.toByteArray(), 0);
    }

    private static void setFields(ChannelSet set, int maskBits, DtsBitWriter w) {
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
        w.write(0, 1);
        for (var ch = 0; ch < set.channels; ch++) {
            w.write(0, 4);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            w.write(0, 2);
        }
    }

    private static byte[] bandData(ChannelSet set, int segment, int segmentSamples, int sets) {
        var abitBits = set.width > 16 ? 5 : set.width > 8 ? 4 : 3;
        if (sets > 1 && abitBits < 5) {
            abitBits++;
        }
        var w = new DtsBitWriter();
        if (segment > 0) {
            w.write(0, 1);
        }
        w.write(0, 1);
        var start = segment * segmentSamples;
        if (set.riceBits >= 0) {
            return riceBandData(set, segment, start, segmentSamples, abitBits, w);
        }
        for (var ch = 0; ch < set.channels; ch++) {
            w.write(0, 1);
        }
        var widths = new int[set.channels];
        for (var ch = 0; ch < set.channels; ch++) {
            var maximum = 0;
            for (var n = start; n < start + segmentSamples; n++) {
                maximum = Math.max(maximum, Math.abs(set.residuals[ch][n]));
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
                    var value = set.residuals[ch][n];
                    w.write(value >= 0 ? 2L * value : -2L * value - 1, widths[ch]);
                }
            }
        }
        w.alignTo(8);
        var bytes = w.toByteArray();
        return bytes.length > 0 ? bytes : new byte[1];
    }

    /**
     * Writes the rest of the band data of a segment whose residuals are Rice coded, after its segment type.
     */
    private static byte[] riceBandData(ChannelSet set, int segment, int start, int segmentSamples, int abitBits,
                                       DtsBitWriter w) {
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
                var value = set.residuals[ch][n];
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
