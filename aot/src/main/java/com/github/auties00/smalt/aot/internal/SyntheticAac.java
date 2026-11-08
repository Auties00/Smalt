package com.github.auties00.smalt.aot.internal;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds synthetic AAC streams of the object types no Smalt encoder writes, bit by bit: ER BSAC frames, the layers
 * of scalable AAC, and the configuration and spatial frames of an MPEG Surround elementary stream.
 *
 * <p>The BSAC frames carry valid headers over pseudo-random arithmetic coded layers, which the decoder reads
 * whatever their bits; the scalable layers code zero, noise and intensity bands with long term prediction, TNS,
 * M/S and the frequency selective switch; the spatial frames code the default parameters of every box.
 */
final class SyntheticAac {
    /**
     * Prevents instantiation.
     */
    private SyntheticAac() {
        throw new AssertionError();
    }

    /**
     * Returns an ER BSAC {@code AudioSpecificConfig} (object type 22, one subframe, {@code epConfig} 0), with the
     * sync extension that signals spectral band replication at twice the rate when {@code sbr} is set.
     *
     * @param rateIndex the sampling frequency index
     * @param channels the channel configuration, 1 or 2
     * @param sbr whether spectral band replication is signaled
     * @return the configuration
     */
    static byte[] bsacConfig(int rateIndex, int channels, boolean sbr) {
        var bits = new Bits();
        bits.write(22, 5);
        bits.write(rateIndex, 4);
        bits.write(channels, 4);
        bits.write(0, 1);
        bits.write(0, 1);
        bits.write(1, 1);
        bits.write(1, 5);
        bits.write(0, 11);
        bits.write(0, 1);
        bits.write(0, 2);
        if (sbr) {
            bits.write(0x2B7, 11);
            bits.write(22, 5);
            bits.write(1, 1);
            bits.write(3, 4);
            bits.write(channels, 4);
        }
        return bits.toByteArray();
    }

    /**
     * Returns an ER BSAC frame: a base element and, for {@code extensions}, the zero code and sync word, a spectral
     * band replication payload of the base element, then a center channel and a surround pair extension element,
     * each with its spectral band replication payload.
     *
     * @param frame the frame number, which varies the window sequence, the layers and the tools
     * @param channels the channels of the base element, 1 or 2
     * @param segmented whether the arithmetic coded data is segmented
     * @param extensions whether the frame carries the extension elements
     * @return the frame
     */
    static byte[] bsacFrame(int frame, int channels, boolean segmented, boolean extensions) {
        var bits = new Bits();
        bits.writeBytes(bsacElement(frame, channels, segmented, -1, extensions ? 480 : 160));
        if (extensions) {
            bits.writeBytes(new byte[]{0, 0, 0, 0, (byte) 0xF0});
            bits.writeBytes(bsacSbrPayload(frame));
            bits.write(0x0E, 4);
            bits.writeBytes(bsacElement(frame + 1, 1, segmented, 0, 64));
            bits.writeBytes(bsacSbrPayload(frame + 1));
            bits.write(0, 4);
            bits.write(0x0E, 4);
            bits.writeBytes(bsacElement(frame + 2, 2, segmented, 3, 96));
            bits.writeBytes(bsacSbrPayload(frame + 2));
        }
        var result = bits.toByteArray();
        // the frame length of the base element's header counts the whole frame
        result[0] = (byte) (result.length >> 3);
        result[1] = (byte) ((result[1] & 0x1F) | ((result.length & 7) << 5));
        return result;
    }

    /**
     * Returns the spectral band replication payload of an ER BSAC element ({@code extended_bsac_sbr_data}): a
     * count of 6, an SBR header and pseudo-random SBR data, 48 bits in all.
     */
    private static byte[] bsacSbrPayload(int seed) {
        var bits = new Bits();
        bits.write(6, 4);
        bits.write(1, 1);
        bits.write(0, 1);
        bits.write(5, 4);
        bits.write(9, 4);
        bits.write(0, 3);
        bits.write(0, 2);
        bits.write(0, 1);
        bits.write(0, 1);
        var state = 0x2545F491 + seed * 7919;
        while (bits.bitCount() < 48) {
            state = state * 1103515245 + 12345;
            bits.write((state >>> 16) & 1, 1);
        }
        return bits.toByteArray();
    }

    /**
     * Returns one ER BSAC element of {@code bytes} bytes: its {@code bsac_header} and {@code general_header}, then
     * pseudo-random layer data; a channel configuration index of -1 builds a base element.
     */
    private static byte[] bsacElement(int frame, int channels, boolean segmented, int channelIndex, int bytes) {
        var bits = new Bits();
        bits.write(bytes, 11);
        if (channelIndex >= 0) {
            bits.write(channelIndex, 4);
        }
        var shortWindows = frame % 4 == 2;
        bits.write(0, 4);
        bits.write(segmented ? 1 : 0, 1);
        bits.write(12, 6);
        bits.write(frame % 3, 2);
        for (var ch = 0; ch < channels; ch++) {
            bits.write(60 + 4 * ch, 8);
        }
        bits.write(shortWindows ? 4 : 10, 5);
        for (var ch = 0; ch < channels; ch++) {
            bits.write(9 + frame % 12, 5);
            bits.write(1 + ch, 3);
            bits.write(2, 3);
            bits.write(5, 4);
        }
        bits.write(0, 1);
        bits.write(shortWindows ? 2 : frame % 2 == 0 ? 0 : 1, 2);
        bits.write(frame & 1, 1);
        if (shortWindows) {
            bits.write(10, 4);
            bits.write(0b0101100, 7);
        } else {
            bits.write(28, 6);
        }
        var noise = frame % 3 == 1;
        bits.write(noise ? 1 : 0, 1);
        if (noise) {
            bits.write(shortWindows ? 6 : 18, 6);
        }
        if (channels == 2) {
            bits.write(1 + frame % 3, 2);
        }
        for (var ch = 0; ch < channels; ch++) {
            var tns = ch == 0 && !shortWindows;
            bits.write(tns ? 1 : 0, 1);
            if (tns) {
                bits.write(1, 2);
                bits.write(1, 1);
                bits.write(12, 6);
                bits.write(4, 5);
                bits.write(0, 1);
                bits.write(0, 1);
                for (var k = 0; k < 4; k++) {
                    bits.write((k * 5 + frame) & 15, 4);
                }
            }
            bits.write(0, 1);
        }
        var state = 0x1F2E3D4C + frame * 104729 + channels;
        while (bits.bitCount() < 8L * bytes) {
            state = state * 1664525 + 1013904223;
            bits.write((state >>> 24) & 0xFF, 8);
        }
        return Arrays.copyOf(bits.toByteArray(), bytes);
    }

    /**
     * Returns the {@code AudioSpecificConfig}s of the four layers of a scalable object at 48 kHz: two mono layers
     * under two stereo layers, of object type 6, or 20 with {@code epConfig} 0.
     *
     * @param errorResilient whether the layers are of object type 20
     * @return the configuration of each layer, the base layer first
     */
    static List<byte[]> scalableConfigs(boolean errorResilient) {
        var configs = new ArrayList<byte[]>();
        for (var layer = 0; layer < 4; layer++) {
            var bits = new Bits();
            bits.write(errorResilient ? 20 : 6, 5);
            bits.write(3, 4);
            bits.write(layer < 2 ? 1 : 2, 4);
            bits.write(0, 1);
            bits.write(0, 1);
            bits.write(errorResilient ? 1 : 0, 1);
            bits.write(layer, 3);
            if (errorResilient) {
                bits.write(0, 3);
                bits.write(0, 1);
                bits.write(0, 2);
            }
            configs.add(bits.toByteArray());
        }
        return configs;
    }

    /**
     * Returns the access units of one frame of the four layers of {@link #scalableConfigs}: long term prediction,
     * TNS and noise substitution in the first mono layer, M/S, the frequency selective switch, TNS, intensity
     * stereo and noise in the stereo layers, cycling through the four window sequences.
     *
     * @param frame the frame number
     * @param errorResilient whether the layers are of object type 20
     * @return the access unit of each layer, the base layer first
     */
    static List<byte[]> scalableFrame(int frame, boolean errorResilient) {
        var sequence = frame % 4;
        var eightShort = sequence == 2;
        var bands = eightShort ? 12 : 40;
        var units = new ArrayList<byte[]>();
        for (var layer = 0; layer < 4; layer++) {
            var stereo = layer >= 2;
            var channels = stereo ? 2 : 1;
            var header = new Bits();
            var flags = new Bits[]{new Bits(), new Bits()};
            var diff = new Bits[]{new Bits(), new Bits()};
            var stream = new Bits[]{new Bits(), new Bits()};
            var tns = new Bits[]{new Bits(), new Bits()};
            if (layer == 0) {
                header.write(0, 1);
                header.write(sequence, 2);
                header.write(frame & 1, 1);
                header.write(bands, eightShort ? 4 : 6);
                if (eightShort) {
                    header.write(0b1010110, 7);
                }
            } else {
                header.write(bands, eightShort ? 4 : 6);
            }
            if (stereo) {
                header.write(layer == 2 ? 1 : 2, 2);
                if (layer == 2) {
                    for (var group = 0; group < (eightShort ? 4 : 1); group++) {
                        for (var band = 0; band < bands; band++) {
                            header.write(band % 3 == 0 ? 1 : 0, 1);
                        }
                    }
                }
            }
            for (var channel = 0; channel < channels; channel++) {
                var tnsData = layer == 0 || (layer == 2 && channel == 0);
                if (layer == 0 || layer == 2) {
                    flags[channel].write(tnsData ? 1 : 0, 1);
                    if (tnsData) {
                        scalableTns(errorResilient ? tns[channel] : flags[channel], eightShort);
                    }
                }
                if (layer == 0) {
                    flags[channel].write(eightShort ? 0 : 1, 1);
                    if (!eightShort) {
                        flags[channel].write((300 + frame * 53) % 2048, 11);
                        flags[channel].write(frame & 7, 3);
                        for (var band = 0; band < bands; band++) {
                            flags[channel].write(band & 1, 1);
                        }
                    }
                }
                if (layer == 2) {
                    if (eightShort) {
                        diff[channel].write(frame % 2 == 0 ? 0b10110100 : 0b01001011, 8);
                    } else {
                        for (var band = 0; band < bands; band++) {
                            if (band % 3 != 0) {
                                diff[channel].write(band & 1, 1);
                            }
                        }
                    }
                }
                scalableStream(stream[channel], eightShort, stereo && channel == 1, layer);
            }
            var unit = new Bits();
            unit.append(header);
            if (errorResilient) {
                for (var channel = 0; channel < channels; channel++) {
                    unit.append(flags[channel]);
                    unit.append(diff[channel]);
                    unit.append(stream[channel]);
                    unit.append(tns[channel]);
                }
            } else {
                for (var part : List.of(flags, diff, stream)) {
                    for (var channel = 0; channel < channels; channel++) {
                        unit.append(part[channel]);
                    }
                }
            }
            units.add(unit.toByteArray());
        }
        return units;
    }

    /**
     * Writes the {@code tns_data} of a scalable layer: one filter over the upper bands of a long window, or of the
     * first of eight short windows.
     */
    private static void scalableTns(Bits bits, boolean eightShort) {
        if (!eightShort) {
            bits.write(1, 2);
            bits.write(1, 1);
            bits.write(40, 6);
            bits.write(4, 5);
            bits.write(0, 2);
            bits.write(0x5C39, 16);
            return;
        }
        bits.write(1, 1);
        bits.write(1, 1);
        bits.write(8, 4);
        bits.write(2, 3);
        bits.write(0, 2);
        bits.write(0x5C, 8);
        for (var window = 1; window < 8; window++) {
            bits.write(0, 1);
        }
    }

    /**
     * Writes the {@code individual_channel_stream(1, 1)} of a scalable layer: global gain, sections of zero bands
     * with a noise band at the top of every group, and an intensity band below it in the right channel of a stereo
     * layer, with their scalefactors.
     */
    private static void scalableStream(Bits bits, boolean eightShort, boolean right, int layer) {
        var bands = eightShort ? 12 : 40;
        var groups = eightShort ? 4 : 1;
        var lengthBits = eightShort ? 3 : 5;
        bits.write(100, 8);
        for (var group = 0; group < groups; group++) {
            var zero = right ? bands - 2 : bands - 1;
            bits.write(0, 4);
            sectionLength(bits, zero, lengthBits);
            if (right) {
                bits.write(layer == 3 ? 14 : 15, 4);
                sectionLength(bits, 1, lengthBits);
            }
            bits.write(13, 4);
            sectionLength(bits, 1, lengthBits);
        }
        var firstNoise = true;
        for (var group = 0; group < groups; group++) {
            if (right) {
                bits.write(0, 1);
            }
            if (firstNoise) {
                bits.write(256 + 40 + layer, 9);
                firstNoise = false;
            } else {
                bits.write(0, 1);
            }
        }
    }

    /**
     * Writes a section length with its escapes.
     */
    private static void sectionLength(Bits bits, int length, int width) {
        var escape = (1 << width) - 1;
        while (length >= escape) {
            bits.write(escape, width);
            length -= escape;
        }
        bits.write(length, width);
    }

    /**
     * Returns the {@code AudioSpecificConfig} of a separate MPEG Surround elementary stream (object type 30) at
     * 48 kHz over a mono downmix: the 5-1-5 tree with 28 parameter bands, spatial frames of 16 slots (one 1024-sample
     * block), the decorrelators of configuration 0 and subband domain temporal processing.
     *
     * @return the configuration
     */
    static byte[] surroundConfig() {
        var bits = new Bits();
        bits.write(30, 5);
        bits.write(3, 4);
        bits.write(0, 4);
        bits.write(0, 1);
        bits.write(3, 4);
        bits.write(15, 7);
        bits.write(1, 3);
        bits.write(0, 4);
        bits.write(0, 2);
        bits.write(0, 1);
        bits.write(0, 1);
        bits.write(0, 3);
        bits.write(0, 3);
        bits.write(0, 3);
        bits.write(1, 1);
        bits.write(1, 2);
        bits.write(0, 2);
        bits.write(0, 1);
        // the parameter bands of the LFE box, then the byte alignment of the SpatialSpecificConfig
        bits.write(3, 5);
        bits.write(0, 6);
        return bits.toByteArray();
    }

    /**
     * Returns one independent spatial frame of {@link #surroundConfig}: a single parameter set with the default
     * channel level differences and coherences of every box, no smoothing, and temporal processing in the
     * output channels of every other frame.
     *
     * @param frame the frame number
     * @return the frame
     */
    static byte[] surroundFrame(int frame) {
        var bits = new Bits();
        bits.write(0, 1);
        bits.write(0, 3);
        bits.write(1, 1);
        // the data modes of the level differences of the five boxes, then of the coherences of the four boxes that
        // are not the LFE box
        for (var box = 0; box < 9; box++) {
            bits.write(0, 2);
        }
        bits.write(0, 2);
        var shaping = frame % 2 == 0;
        bits.write(shaping ? 1 : 0, 1);
        if (shaping) {
            for (var channel = 0; channel < 5; channel++) {
                bits.write(1, 1);
            }
        }
        return bits.toByteArray();
    }

    /**
     * Collects bits, most significant first.
     */
    private static final class Bits {
        /**
         * The bytes completed so far.
         */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /**
         * The pending bits, in the low {@link #count} bits.
         */
        private long pending;

        /**
         * The number of pending bits, below 8 between calls.
         */
        private int count;

        /**
         * Appends the low bits of a value.
         *
         * @param value the value, of which only the low {@code width} bits are written
         * @param width the number of bits, from 1 to 32
         */
        void write(long value, int width) {
            pending = pending << width | value & ((1L << width) - 1);
            count += width;
            while (count >= 8) {
                count -= 8;
                bytes.write((int) (pending >>> count));
            }
        }

        /**
         * Appends whole bytes.
         *
         * @param data the bytes
         */
        void writeBytes(byte[] data) {
            for (var b : data) {
                write(b & 0xFF, 8);
            }
        }

        /**
         * Appends the bits another writer collected.
         *
         * @param other the writer, left unchanged
         */
        void append(Bits other) {
            var data = other.bytes.toByteArray();
            for (var b : data) {
                write(b & 0xFF, 8);
            }
            if (other.count > 0) {
                write(other.pending, other.count);
            }
        }

        /**
         * {@return the number of bits written}
         */
        long bitCount() {
            return 8L * bytes.size() + count;
        }

        /**
         * {@return the bytes written, the last one padded with zero bits}
         */
        byte[] toByteArray() {
            if (count > 0) {
                write(0, 8 - count);
            }
            return bytes.toByteArray();
        }
    }
}
