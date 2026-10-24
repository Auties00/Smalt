package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Builds synthetic MPEG-1 Layer II streams carrying the ISO/IEC 13818-3 multichannel extension, for the decoder
 * tests and the fuzz seeds: random allocations, scalefactors and samples in the base frame and the extension, a
 * transmission channel allocation per subband group, the low-frequency enhancement channel, and, on request, an
 * extension frame after each base frame. The CRC words are computed as the standard defines them, so the frames
 * are valid; the sound is noise.
 */
final class Mp2MultichannelStreams {
    /** The allocation band groups of ISO/IEC 11172-3 Table B.2a: table offset, code width and band count. */
    private static final int[] GROUPS = Layer12.ALLOC_LAYER2_MPEG1;

    /** The number of subbands of Table B.2a, which 48 kHz uses. */
    private static final int BANDS = 27;

    private Mp2MultichannelStreams() {
    }

    /** The channel configuration of a synthetic stream. */
    record Configuration(int center, int surround, boolean lfe, int dematrix) {
        /** {@return the number of transmission channels} */
        int transmission() {
            var hasCenter = center != 0;
            return switch (surround) {
                case 1 -> hasCenter ? 2 : 1;
                case 2, 3 -> hasCenter ? 3 : 2;
                default -> hasCenter ? 1 : 0;
            };
        }

        /** {@return the width of a transmission channel allocation} */
        int allocationBits() {
            var hasCenter = center != 0;
            return switch (surround) {
                case 3 -> hasCenter ? 2 : 0;
                case 2 -> hasCenter ? 3 : 2;
                case 1 -> hasCenter ? 3 : 2;
                default -> hasCenter ? 2 : 0;
            };
        }

        /** {@return the largest transmission channel allocation of the configuration} */
        int maxTc() {
            var hasCenter = center != 0;
            if (surround == 2 && hasCenter) {
                return 7;
            }
            if (surround == 1 && hasCenter) {
                return 5;
            }
            if (surround == 2) {
                return 3;
            }
            return surround == 1 || hasCenter ? 2 : 0;
        }
    }

    /** {@return the 3/2 configuration with the low-frequency enhancement channel and dematrixing procedure 0} */
    static Configuration surround51() {
        return new Configuration(1, 2, true, 0);
    }

    /**
     * Builds a stream.
     *
     * @param configuration the channel configuration
     * @param frames the number of frames
     * @param seed the seed of the random content
     * @param extensionFrames whether each frame's extension continues in an extension frame after it
     * @return the packets: each base frame, followed by its extension frame when there is one
     */
    static List<byte[]> build(Configuration configuration, int frames, long seed, boolean extensionFrames) {
        var random = new SplittableRandom(seed);
        var packets = new ArrayList<byte[]>(frames);
        for (var f = 0; f < frames; f++) {
            packets.add(frame(configuration, random, extensionFrames));
        }
        return packets;
    }

    /** Concatenates packets into one stream, as a raw file holds them. */
    static byte[] concatenate(List<byte[]> packets) {
        var size = packets.stream().mapToInt(packet -> packet.length).sum();
        var out = new byte[size];
        var at = 0;
        for (var packet : packets) {
            System.arraycopy(packet, 0, out, at, packet.length);
            at += packet.length;
        }
        return out;
    }

    /** Builds one base frame, and the extension frame after it when asked for. */
    private static byte[] frame(Configuration configuration, SplittableRandom random, boolean extensionFrame) {
        // 112 kbit/s leaves less than 256 ancillary bytes after the audio data, so an extension frame can take the rest
        var kbps = extensionFrame ? 112 : 384;
        var bitrateIndex = extensionFrame ? 7 : 14;
        var frameBytes = 144 * kbps * 1000 / 48000;
        var base = new MsbBitWriter(frameBytes);
        base.write(0xFFFD, 16);
        base.write(bitrateIndex << 4 | 1 << 2, 8);
        base.write(0, 8);
        var allocation = new int[2][BANDS];
        for (var band = 0; band < BANDS; band++) {
            for (var ch = 0; ch < 2; ch++) {
                var maximum = (1 << width(band)) - 1;
                allocation[ch][band] = band < (extensionFrame ? 4 : 10) ? 1 + random.nextInt(Math.min(maximum, 5)) : 0;
                base.write(allocation[ch][band], width(band));
            }
        }
        writeScalefactorsAndSamples(base, random, allocation, 0, 2);
        var capacity = frameBytes * 8 - (int) base.bitCount();
        // the base frame keeps the first 64 to 71 bits of the extension; the ancillary bytes fill the rest
        var ancillary = extensionFrame ? Math.max(0, (capacity - 64) / 8) : 0;
        if (ancillary > 255) {
            throw new IllegalStateException("the base frame leaves " + capacity + " bits for the extension");
        }
        var mc = new MsbBitWriter(4096);
        writeExtension(mc, new int[]{0xFFFF}, configuration, random, extensionFrame, ancillary);
        var mcBits = mc.toByteArray();
        var mcLength = (int) mc.bitCount();
        int inFrame;
        if (extensionFrame) {
            inFrame = capacity - ancillary * 8;
        } else {
            if (mcLength > capacity) {
                throw new IllegalStateException("the extension of " + mcLength + " bits does not fit " + capacity);
            }
            inFrame = mcLength;
        }
        writeBits(base, mcBits, 0, Math.min(inFrame, mcLength));
        while (base.bitCount() < frameBytes * 8L) {
            base.write(0, (int) Math.min(16, frameBytes * 8L - base.bitCount()));
        }
        var frame = Arrays.copyOf(base.toByteArray(), frameBytes);
        if (!extensionFrame) {
            return frame;
        }
        var rest = Math.max(0, mcLength - inFrame);
        var extension = new MsbBitWriter(1024);
        var extensionBytes = 5 + (rest + 7) / 8;
        extension.write(Mp2Multichannel.EXTENSION_SYNC, 12);
        extension.write(0, 16);
        extension.write(extensionBytes, 11);
        extension.write(0, 1);
        writeBits(extension, mcBits, inFrame, rest);
        while (extension.bitCount() < extensionBytes * 8L) {
            extension.write(0, 1);
        }
        var extensionData = Arrays.copyOf(extension.toByteArray(), extensionBytes);
        var covered = Math.min(128, extensionBytes * 8 - 28);
        var extensionCrc = MpegAudioCrc.update(0xFFFF, (extensionData[3] & 0x0F) << 7 | (extensionData[4] & 0xFE) >> 1, 11);
        for (var i = 11; i < covered; i++) {
            var position = 28 + i;
            extensionCrc = MpegAudioCrc.update(extensionCrc, (extensionData[position >> 3] >> (7 - (position & 7))) & 1, 1);
        }
        extensionData[1] = (byte) (extensionData[1] & 0xF0 | extensionCrc >> 12);
        extensionData[2] = (byte) (extensionCrc >> 4);
        extensionData[3] = (byte) ((extensionCrc & 0x0F) << 4 | extensionData[3] & 0x0F);
        var packet = Arrays.copyOf(frame, frame.length + extensionData.length);
        System.arraycopy(extensionData, 0, packet, frame.length, extensionData.length);
        return packet;
    }

    /** Returns the allocation width of a subband. */
    private static int width(int band) {
        var start = 0;
        for (var g = 0; g < GROUPS.length; g += 3) {
            if (band < start + GROUPS[g + 2]) {
                return GROUPS[g + 1];
            }
            start += GROUPS[g + 2];
        }
        return 0;
    }

    /** Returns the quantizer class of an allocation index of a subband. */
    private static int quantizerClass(int band, int index) {
        var start = 0;
        for (var g = 0; g < GROUPS.length; g += 3) {
            if (band < start + GROUPS[g + 2]) {
                return Mp3Tables.BIT_ALLOCATION_CODES[GROUPS[g] + index];
            }
            start += GROUPS[g + 2];
        }
        return 0;
    }

    /** Writes the scalefactor selection, scalefactors and samples of channels {@code [first, last)}. */
    private static void writeScalefactorsAndSamples(MsbBitWriter out, SplittableRandom random, int[][] allocation,
                                                    int first, int last) {
        var scfsi = new int[last][BANDS];
        for (var band = 0; band < BANDS; band++) {
            for (var ch = first; ch < last; ch++) {
                if (allocation[ch][band] != 0) {
                    scfsi[ch][band] = random.nextInt(4);
                    out.write(scfsi[ch][band], 2);
                }
            }
        }
        writeScalefactors(out, random, allocation, scfsi, first, last);
        for (var gr = 0; gr < 12; gr++) {
            writeGranule(out, random, allocation, first, last);
        }
    }

    /** Writes the scalefactors of channels {@code [first, last)}. */
    private static void writeScalefactors(MsbBitWriter out, SplittableRandom random, int[][] allocation, int[][] scfsi,
                                          int first, int last) {
        for (var band = 0; band < BANDS; band++) {
            for (var ch = first; ch < last; ch++) {
                if (allocation[ch][band] == 0) {
                    continue;
                }
                var count = new int[]{3, 2, 1, 2}[scfsi[ch][band]];
                for (var k = 0; k < count; k++) {
                    out.write(8 + random.nextInt(30), 6);
                }
            }
        }
    }

    /** Writes the samples of one granule of channels {@code [first, last)}. */
    private static void writeGranule(MsbBitWriter out, SplittableRandom random, int[][] allocation, int first, int last) {
        for (var band = 0; band < BANDS; band++) {
            for (var ch = first; ch < last; ch++) {
                var index = allocation[ch][band];
                if (index == 0) {
                    continue;
                }
                var type = quantizerClass(band, index);
                if (type >= 17) {
                    var levels = type == 17 ? 3 : type == 18 ? 5 : 9;
                    out.write(random.nextInt(levels * levels * levels), type == 17 ? 5 : type == 18 ? 7 : 10);
                } else {
                    for (var k = 0; k < 3; k++) {
                        out.write(random.nextInt((1 << type) - 1), type);
                    }
                }
            }
        }
    }

    /** Writes one value and feeds it into the CRC. */
    private static void protect(MsbBitWriter out, int[] crc, int value, int bits) {
        out.write(value, bits);
        crc[0] = MpegAudioCrc.update(crc[0], value, bits);
    }

    /** Writes the extension of one frame, its CRC word included. */
    private static void writeExtension(MsbBitWriter out, int[] crc, Configuration configuration,
                                       SplittableRandom random, boolean extensionFrame, int ancillary) {
        protect(out, crc, extensionFrame ? 1 : 0, 1);
        if (extensionFrame) {
            protect(out, crc, ancillary, 8);
        }
        protect(out, crc, configuration.center(), 2);
        protect(out, crc, configuration.surround(), 2);
        protect(out, crc, configuration.lfe() ? 1 : 0, 1);
        protect(out, crc, 0, 1);
        protect(out, crc, configuration.dematrix(), 2);
        protect(out, crc, 0, 3);
        protect(out, crc, 0, 1);
        protect(out, crc, 0, 1);
        protect(out, crc, 0, 2);
        var crcPosition = (int) out.bitCount();
        out.write(0, 16);
        protect(out, crc, 0, 1);
        protect(out, crc, 0, 1);
        protect(out, crc, 0, 1);
        for (var g = 0; g < 12; g++) {
            protect(out, crc, random.nextInt(configuration.maxTc() + 1), configuration.allocationBits());
        }
        var first = 2;
        var last = 2 + configuration.transmission();
        var lfeAllocation = 0;
        if (configuration.lfe()) {
            lfeAllocation = 1 + random.nextInt(3);
            protect(out, crc, lfeAllocation, 4);
        }
        var allocation = new int[last][BANDS];
        for (var band = 0; band < BANDS; band++) {
            for (var ch = first; ch < last; ch++) {
                allocation[ch][band] = band < 10 ? random.nextInt(4) : 0;
                protect(out, crc, allocation[ch][band], width(band));
            }
        }
        var scfsi = new int[last][BANDS];
        for (var band = 0; band < BANDS; band++) {
            for (var ch = first; ch < last; ch++) {
                if (allocation[ch][band] != 0) {
                    scfsi[ch][band] = random.nextInt(4);
                    protect(out, crc, scfsi[ch][band], 2);
                }
            }
        }
        if (lfeAllocation != 0) {
            out.write(10 + random.nextInt(20), 6);
        }
        writeScalefactors(out, random, allocation, scfsi, first, last);
        for (var gr = 0; gr < 12; gr++) {
            if (lfeAllocation != 0) {
                var levels = lfeAllocation == 1 ? 3 : (2 << lfeAllocation) - 1;
                out.write(random.nextInt(levels), lfeAllocation + 1);
            }
            writeGranule(out, random, allocation, first, last);
        }
        storeCrc(out, crcPosition, crc[0]);
    }

    /** Overwrites the CRC word reserved at a bit position of a writer's output. */
    private static void storeCrc(MsbBitWriter out, int position, int crc) {
        var bytes = out.array();
        for (var i = 0; i < 16; i++) {
            var bit = (crc >> (15 - i)) & 1;
            var at = position + i;
            if (bit != 0) {
                bytes[at >> 3] |= (byte) (0x80 >>> (at & 7));
            } else {
                bytes[at >> 3] &= (byte) ~(0x80 >>> (at & 7));
            }
        }
    }

    /** Reads bits from a byte array, most significant first. */
    private static int readBits(byte[] data, int position, int bits) {
        var value = 0;
        for (var i = 0; i < bits; i++) {
            var at = position + i;
            value = value << 1 | ((data[at >> 3] >> (7 - (at & 7))) & 1);
        }
        return value;
    }

    /** Appends a run of bits of a byte array to a writer. */
    private static void writeBits(MsbBitWriter out, byte[] data, int from, int count) {
        for (var i = 0; i < count; i++) {
            out.write(readBits(data, from + i, 1), 1);
        }
    }
}
