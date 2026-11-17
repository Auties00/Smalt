/* Ports libvorbis 1.3.7 (lib/bitrate.c). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Chooses, for every block of a bitrate managed stream, which of its {@link PsyGlobal#PACKET_BLOBS}
 * packet blobs becomes the packet, and pads or truncates it, so that the stream keeps its average
 * bitrate and its hard limits through a bit reservoir.
 *
 * <p>The blobs of a block code the same audio at rising bitrates. An average tracker (a floating
 * blob index, slewing at most the whole range in the damping time) picks the blob that moves the
 * average reservoir toward its preferred fill; the hard limits then move the choice up or down
 * until the minimum and maximum reservoir stays between empty and full. When even the smallest blob
 * is over the maximum the packet is truncated, and when even the largest is under the minimum it is
 * padded with zero bytes.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation never truncates a packet below one byte. libvorbis truncates to
 * the size the reservoir leaves, which is zero bytes for a short block when the maximum allows it
 * fewer than eight bits and the reservoir is full; decoders drop an empty packet, which loses the
 * block and shifts the audio after it. One byte holds the packet type, mode and window flags, so
 * the block still decodes in its place. A bitrate so low that it rounds to no bits per short
 * block counts as one bit, where libvorbis would ignore the limit.
 */
final class BitrateManager {
    /**
     * The average bits of a short block, {@code 0} without an average.
     */
    private final long averageBitsPer;

    /**
     * The minimum bits of a short block, {@code 0} without a minimum.
     */
    private final long minimumBitsPer;

    /**
     * The maximum bits of a short block, {@code 0} without a maximum.
     */
    private final long maximumBitsPer;

    /**
     * The number of short blocks a long block spans.
     */
    private final long shortPerLong;

    /**
     * The reservoir size in bits.
     */
    private final long reservoirBits;

    /**
     * The preferred reservoir fill in bits.
     */
    private final long desiredFill;

    /**
     * The largest change of the average tracker, in blobs per second.
     */
    private final double slewLimit;

    /**
     * The sampling rate.
     */
    private final int rate;

    /**
     * The block sizes.
     */
    private final int[] blockSizes;

    /**
     * The fill of the minimum and maximum reservoir in bits.
     */
    private long minmaxReservoir;

    /**
     * The fill of the average reservoir in bits.
     */
    private long averageReservoir;

    /**
     * The floating blob index of the average tracker.
     */
    private double averageFloat;

    /**
     * Prepares the manager of a stream ({@code vorbis_bitrate_init}).
     *
     * @param setup the managed setup
     */
    BitrateManager(CodecSetup setup) {
        var info = setup.management;
        rate = setup.rate;
        blockSizes = setup.blockSizes;
        var halfSamples = blockSizes[0] >> 1;
        shortPerLong = blockSizes[1] / blockSizes[0];
        averageBitsPer = bitsPer(info.averageRate(), halfSamples, rate);
        minimumBitsPer = bitsPer(info.minimumRate(), halfSamples, rate);
        maximumBitsPer = bitsPer(info.maximumRate(), halfSamples, rate);
        reservoirBits = info.reservoirBits();
        desiredFill = (long) (info.reservoirBits() * info.reservoirBias());
        slewLimit = 15. / info.slewDamp();
        averageFloat = PsyGlobal.PACKET_BLOBS / 2;
        minmaxReservoir = desiredFill;
        averageReservoir = desiredFill;
    }

    /**
     * Returns the bits a bitrate allows a short block, rounded to a whole bit.
     *
     * @param bitrate the bitrate in bits per second, {@code 0} or less for none
     * @param halfSamples the samples a short block's packet stands for
     * @param rate the sampling rate
     * @return the bits, at least {@code 1} for a set bitrate, or {@code 0} for none
     */
    private static long bitsPer(long bitrate, int halfSamples, int rate) {
        return bitrate > 0 ? Math.max(1, (long) Math.rint(1. * bitrate * halfSamples / rate)) : 0;
    }

    /**
     * Chooses the packet of a block and updates the reservoirs ({@code vorbis_bitrate_addblock}).
     *
     * <p>The packet is the chosen blob's bytes cut or extended with zero bytes to
     * {@code result[1]} bytes, at least one.
     *
     * @param blobBytes the size in bytes of each blob, indexed by blob
     * @param longBlock whether the block is a long block
     * @param result receives the chosen blob at index 0 and the packet size in bytes at index 1
     */
    void choose(int[] blobBytes, boolean longBlock, int[] result) {
        var blobs = PsyGlobal.PACKET_BLOBS;
        var choice = (int) Math.rint(averageFloat);
        long thisBits = blobBytes[choice] * 8L;
        var minTargetBits = longBlock ? minimumBitsPer * shortPerLong : minimumBitsPer;
        var maxTargetBits = longBlock ? maximumBitsPer * shortPerLong : maximumBitsPer;
        var samples = blockSizes[longBlock ? 1 : 0] >> 1;
        if (averageBitsPer > 0) {
            var averageTargetBits = longBlock ? averageBitsPer * shortPerLong : averageBitsPer;
            if (averageReservoir + (thisBits - averageTargetBits) > desiredFill) {
                while (choice > 0 && thisBits > averageTargetBits
                        && averageReservoir + (thisBits - averageTargetBits) > desiredFill) {
                    choice--;
                    thisBits = blobBytes[choice] * 8L;
                }
            } else if (averageReservoir + (thisBits - averageTargetBits) < desiredFill) {
                while (choice + 1 < blobs && thisBits < averageTargetBits
                        && averageReservoir + (thisBits - averageTargetBits) < desiredFill) {
                    choice++;
                    thisBits = blobBytes[choice] * 8L;
                }
            }
            var slew = Math.rint(choice - averageFloat) / samples * rate;
            if (slew < -slewLimit) {
                slew = -slewLimit;
            }
            if (slew > slewLimit) {
                slew = slewLimit;
            }
            averageFloat += slew / rate * samples;
            choice = (int) Math.rint(averageFloat);
            thisBits = blobBytes[choice] * 8L;
        }
        if (minimumBitsPer > 0 && thisBits < minTargetBits) {
            while (minmaxReservoir - (minTargetBits - thisBits) < 0) {
                choice++;
                if (choice >= blobs) {
                    break;
                }
                thisBits = blobBytes[choice] * 8L;
            }
        }
        if (maximumBitsPer > 0 && thisBits > maxTargetBits) {
            while (minmaxReservoir + (thisBits - maxTargetBits) > reservoirBits) {
                choice--;
                if (choice < 0) {
                    break;
                }
                thisBits = blobBytes[choice] * 8L;
            }
        }
        long bytes;
        if (choice < 0) {
            var maxSize = (maxTargetBits + (reservoirBits - minmaxReservoir)) / 8;
            choice = 0;
            bytes = Math.max(1, Math.min(blobBytes[0], maxSize));
        } else {
            var minSize = (minTargetBits - minmaxReservoir + 7) / 8;
            if (choice >= blobs) {
                choice = blobs - 1;
            }
            bytes = Math.max(blobBytes[choice], minSize);
        }
        thisBits = bytes * 8;
        if (minimumBitsPer > 0 || maximumBitsPer > 0) {
            if (maxTargetBits > 0 && thisBits > maxTargetBits) {
                minmaxReservoir += thisBits - maxTargetBits;
            } else if (minTargetBits > 0 && thisBits < minTargetBits) {
                minmaxReservoir += thisBits - minTargetBits;
            } else if (minmaxReservoir > desiredFill) {
                if (maxTargetBits > 0) {
                    minmaxReservoir += thisBits - maxTargetBits;
                    if (minmaxReservoir < desiredFill) {
                        minmaxReservoir = desiredFill;
                    }
                } else {
                    minmaxReservoir = desiredFill;
                }
            } else if (minTargetBits > 0) {
                minmaxReservoir += thisBits - minTargetBits;
                if (minmaxReservoir > desiredFill) {
                    minmaxReservoir = desiredFill;
                }
            } else {
                minmaxReservoir = desiredFill;
            }
        }
        if (averageBitsPer > 0) {
            var averageTargetBits = longBlock ? averageBitsPer * shortPerLong : averageBitsPer;
            averageReservoir += thisBits - averageTargetBits;
        }
        result[0] = choice;
        result[1] = (int) bytes;
    }
}
