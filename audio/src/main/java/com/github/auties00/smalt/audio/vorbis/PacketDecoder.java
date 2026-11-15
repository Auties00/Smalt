package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes Vorbis audio packets into PCM, holding the lapping state between packets (Vorbis I
 * section 4.3).
 *
 * <p>Each packet runs the whole synthesis pipeline: mode and window decode, floor decode,
 * nonzero propagation, residue decode, inverse coupling, floor/residue product, inverse MDCT,
 * windowing and overlap-add with the right half of the previous block. The returned samples span
 * from the center of the previous block to the center of the current one; the first packet after
 * construction or {@link #reset()} only primes the lapping state and returns none.
 *
 * <p>State changes happen only after a packet decoded successfully, so a packet rejected with
 * {@link MediaException.Malformed} leaves the decoder ready for the next one. Instances are not
 * thread-safe.
 */
final class PacketDecoder {
    /**
     * The value {@link #decode} returns for a packet that produced no samples and left the state
     * untouched.
     */
    static final int IGNORED = -1;

    /**
     * The seed of the generator of the signs of concealed spectra.
     */
    private static final int NOISE_SEED = 0x2545F491;

    /**
     * The number of concealed packets in a row that are heard; later ones are silent.
     */
    private static final int AUDIBLE_CONCEALMENT = 4;

    /**
     * The stream configuration.
     */
    private final VorbisSetup setup;

    /**
     * The DSP kernel.
     */
    private final VorbisKernel kernel;

    /**
     * The inverse MDCT tables of the short ({@code [0]}) and long ({@code [1]}) block sizes.
     */
    private final Imdct[] transforms;

    /**
     * The spectrum of each channel: residue, then audio spectrum, at least {@code blocksize1/2}
     * long.
     */
    private final float[][] spectra;

    /**
     * The windowed right half of the previous block of each channel.
     */
    private final float[][] saved;

    /**
     * The time-domain samples of the current block of one channel.
     */
    private final float[] block;

    /**
     * The FFT scratch for real parts.
     */
    private final float[] re;

    /**
     * The FFT scratch for imaginary parts.
     */
    private final float[] im;

    /**
     * The decoded floor values of each channel.
     */
    private final FloorData[] floorData;

    /**
     * The floor used by each channel in the current packet.
     */
    private final Floor[] channelFloors;

    /**
     * Whether each channel's floor is unused in the current packet.
     */
    private final boolean[] unused;

    /**
     * Whether each channel's residue is not coded in the current packet.
     */
    private final boolean[] noResidue;

    /**
     * The residue vectors of the submap being decoded.
     */
    private final float[][] bundle;

    /**
     * The do-not-decode flags of the submap being decoded.
     */
    private final boolean[] bundleSkip;

    /**
     * The residue class scratch for formats 0 and 1, one row per channel.
     */
    private final int[][] classes;

    /**
     * The residue class scratch for format 2, a single row.
     */
    private final int[][] interleavedClasses;

    /**
     * The block size of the previous packet, or {@code 0} before the first packet.
     */
    private int previousSize;

    /**
     * The audio spectrum of each channel of the last decoded packet, which concealment repeats.
     */
    private final float[][] lastSpectra;

    /**
     * Whether each channel's floor was unused in the last decoded packet.
     */
    private final boolean[] lastUnused;

    /**
     * The next window flag of the last packet: whether the block after it is long.
     */
    private boolean lastNextLong;

    /**
     * The number of packets concealed since the last decoded one.
     */
    private int concealedRun;

    /**
     * The state of the generator of the signs of concealed spectra.
     */
    private int noise = NOISE_SEED;

    /**
     * Allocates the decoding state of a stream.
     *
     * @param setup  the stream configuration
     * @param kernel the DSP kernel
     * @param budget the allocation budget of the stream
     * @throws MediaException.LimitExceeded if the buffers exceed the allocation budget
     */
    PacketDecoder(VorbisSetup setup, VorbisKernel kernel, AllocationBudget budget) {
        this.setup = setup;
        this.kernel = kernel;
        var channels = setup.channels;
        var blocksize1 = setup.blocksizes[1];
        var half = blocksize1 / 2;
        budget.charge(4L * channels * half + 2L * blocksize1, 4);
        this.transforms = new Imdct[]{new Imdct(setup.blocksizes[0]), new Imdct(blocksize1)};
        this.spectra = new float[channels][half];
        this.saved = new float[channels][half];
        this.lastSpectra = new float[channels][half];
        this.lastUnused = new boolean[channels];
        this.block = new float[blocksize1];
        this.re = new float[blocksize1 / 4];
        this.im = new float[blocksize1 / 4];
        var ints = 0;
        var floats = 0;
        for (var floor : setup.floors) {
            ints = Math.max(ints, floor.intCapacity());
            floats = Math.max(floats, floor.floatCapacity());
        }
        this.floorData = new FloorData[channels];
        for (var i = 0; i < channels; i++) {
            floorData[i] = new FloorData(ints, floats);
        }
        this.channelFloors = new Floor[channels];
        this.unused = new boolean[channels];
        this.noResidue = new boolean[channels];
        this.bundle = new float[channels][];
        this.bundleSkip = new boolean[channels];
        var rows = 0;
        var interleaved = 0;
        for (var residue : setup.residues) {
            if (residue.type == 2) {
                interleaved = Math.max(interleaved, residue.partitions(half, channels));
            } else {
                rows = Math.max(rows, residue.partitions(half, 1));
            }
        }
        budget.charge((long) rows * channels + interleaved, 4);
        this.classes = new int[channels][rows];
        this.interleavedClasses = new int[][]{new int[interleaved]};
    }

    /**
     * Returns the largest number of samples one packet can return.
     *
     * @return {@code blocksize1 / 2}
     */
    int maxSamples() {
        return setup.blocksizes[1] / 2;
    }

    /**
     * Forgets the lapping state, so the next packet only primes the decoder.
     */
    void reset() {
        previousSize = 0;
        concealedRun = 0;
    }

    /**
     * Decodes one audio packet.
     *
     * <p>Empty packets and packets whose type bit marks a header are ignored, as the
     * specification requires.
     *
     * @param data   the packet
     * @param output one array per channel, in stream channel order, receiving the samples from
     *               index zero; each at least {@link #maxSamples()} long
     * @return the number of samples written per channel, or {@link #IGNORED}
     * @throws MediaException.Malformed if the packet ends before its window flags or selects a
     *                                  missing mode
     */
    int decode(byte[] data, float[][] output) {
        if (data.length == 0) {
            return IGNORED;
        }
        var reader = new BitReader(data);
        if (reader.readBit() != 0) {
            return IGNORED;
        }
        var modeNumber = reader.read(setup.modeBits);
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated Vorbis audio packet header");
        }
        if (modeNumber >= setup.modes.length) {
            throw new MediaException.Malformed("Vorbis audio packet selects missing mode " + modeNumber);
        }
        var mode = setup.modes[modeNumber];
        var longBlock = mode.longBlock();
        var previousLong = false;
        var nextLong = false;
        if (longBlock) {
            previousLong = reader.readBit() == 1;
            nextLong = reader.readBit() == 1;
            if (reader.overrun()) {
                throw new MediaException.Malformed("truncated Vorbis audio packet header");
            }
        }
        var transform = transforms[longBlock ? 1 : 0];
        var size = transform.size;
        var half = transform.half;
        var mapping = setup.mappings[mode.mapping()];
        var channels = setup.channels;
        decodeSpectra(reader, mapping, half, longBlock);
        // the inverse MDCT may use the spectra as scratch, so they are kept before it runs
        for (var ch = 0; ch < channels; ch++) {
            System.arraycopy(spectra[ch], 0, lastSpectra[ch], 0, half);
        }
        System.arraycopy(unused, 0, lastUnused, 0, channels);
        var samples = synthesize(transform, size, half, longBlock, previousLong, nextLong, output, channels);
        lastNextLong = !longBlock || nextLong;
        concealedRun = 0;
        return samples;
    }

    /**
     * {@return the number of samples one concealed packet returns: half the block size of the previous packet, or
     * half the short block size before the first}
     */
    int concealmentUnit() {
        return (previousSize == 0 ? setup.blocksizes[0] : previousSize) / 2;
    }

    /**
     * Conceals one lost packet with a block of the previous packet's size, whose spectrum repeats the last decoded
     * one with random signs: at full amplitude for the first packet lost in a row, halved for each further one, and
     * silent from the fifth on.
     *
     * <p>A long block starts with the slope the previous block ended with and ends with a long slope.
     *
     * @param output one array per channel, in stream channel order, receiving the samples from index zero; each at
     *               least {@link #maxSamples()} long
     * @return the number of samples written per channel, {@link #concealmentUnit()}, or {@link #IGNORED} before the
     *         first packet, when nothing is written
     */
    int conceal(float[][] output) {
        if (previousSize == 0) {
            return IGNORED;
        }
        concealedRun = Math.min(concealedRun + 1, AUDIBLE_CONCEALMENT + 1);
        var gain = concealedRun <= AUDIBLE_CONCEALMENT ? 1.0f / (1 << (concealedRun - 1)) : 0;
        var longBlock = previousSize == setup.blocksizes[1] && setup.blocksizes[1] != setup.blocksizes[0];
        var transform = transforms[longBlock ? 1 : 0];
        var half = transform.half;
        var channels = setup.channels;
        var state = noise;
        for (var ch = 0; ch < channels; ch++) {
            unused[ch] = lastUnused[ch] || gain == 0;
            if (unused[ch]) {
                continue;
            }
            var source = lastSpectra[ch];
            var target = spectra[ch];
            for (var k = 0; k < half; k++) {
                state = state * 1664525 + 1013904223;
                target[k] = state < 0 ? -gain * source[k] : gain * source[k];
            }
        }
        noise = state;
        var samples = synthesize(transform, transform.size, half, longBlock, lastNextLong, true, output, channels);
        lastNextLong = true;
        return samples;
    }

    /**
     * Decodes floors and residues, undoes the coupling and multiplies each channel's residue by
     * its floor, leaving the audio spectra in {@link #spectra}.
     *
     * @param reader    the packet reader, positioned after the window flags
     * @param mapping   the packet's mapping
     * @param half      the half block size
     * @param longBlock whether the block is long
     */
    private void decodeSpectra(BitReader reader, Mapping mapping, int half, boolean longBlock) {
        var channels = setup.channels;
        var mux = mapping.mux();
        for (var ch = 0; ch < channels; ch++) {
            var floor = setup.floors[mapping.submapFloor()[mux[ch]]];
            channelFloors[ch] = floor;
            unused[ch] = !floor.decode(reader, floorData[ch]);
        }
        if (reader.overrun()) {
            // Vorbis I 4.3.2: end-of-packet during floor decode zeroes every channel.
            for (var ch = 0; ch < channels; ch++) {
                unused[ch] = true;
                Arrays.fill(spectra[ch], 0, half, 0.0f);
            }
            return;
        }
        System.arraycopy(unused, 0, noResidue, 0, channels);
        var magnitude = mapping.magnitude();
        var angle = mapping.angle();
        for (var i = 0; i < magnitude.length; i++) {
            if (!noResidue[magnitude[i]] || !noResidue[angle[i]]) {
                noResidue[magnitude[i]] = false;
                noResidue[angle[i]] = false;
            }
        }
        for (var submap = 0; submap < mapping.submaps(); submap++) {
            var count = 0;
            for (var ch = 0; ch < channels; ch++) {
                if (mux[ch] == submap) {
                    bundle[count] = spectra[ch];
                    bundleSkip[count] = noResidue[ch];
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            var residue = setup.residues[mapping.submapResidue()[submap]];
            residue.decode(reader, bundle, count, bundleSkip, half, residue.type == 2 ? interleavedClasses : classes);
        }
        for (var i = magnitude.length - 1; i >= 0; i--) {
            kernel.inverseCoupling(spectra[magnitude[i]], spectra[angle[i]], half);
        }
        for (var ch = 0; ch < channels; ch++) {
            if (!unused[ch]) {
                channelFloors[ch].apply(floorData[ch], spectra[ch], half, longBlock);
            }
        }
    }

    /**
     * Transforms the spectra, windows the blocks and laps them with the previous ones.
     *
     * @param transform    the inverse MDCT tables of the block size
     * @param size         the block size
     * @param half         the half block size
     * @param longBlock    whether the block is long
     * @param previousLong the packet's previous window flag
     * @param nextLong     the packet's next window flag
     * @param output       the per-channel output arrays
     * @param channels     the channel count
     * @return the number of samples written per channel
     */
    private int synthesize(Imdct transform, int size, int half, boolean longBlock, boolean previousLong,
                           boolean nextLong, float[][] output, int channels) {
        var shortSize = setup.blocksizes[0];
        var shortTransform = transforms[0];
        int leftStart;
        int leftEnd;
        float[] leftSlope;
        if (longBlock && !previousLong) {
            leftStart = size / 4 - shortSize / 4;
            leftEnd = size / 4 + shortSize / 4;
            leftSlope = shortTransform.slope;
        } else {
            leftStart = 0;
            leftEnd = half;
            leftSlope = transform.slope;
        }
        int rightStart;
        int rightEnd;
        float[] rightSlope;
        if (longBlock && !nextLong) {
            rightStart = size / 4 - shortSize / 4;
            rightEnd = size / 4 + shortSize / 4;
            rightSlope = shortTransform.slopeDown;
        } else {
            rightStart = 0;
            rightEnd = half;
            rightSlope = transform.slopeDown;
        }
        var previous = previousSize;
        var outputLength = previous == 0 ? 0 : previous / 4 + size / 4;
        var offset = previous / 4 - size / 4;
        var savedLength = previous / 2;
        var block = this.block;
        for (var ch = 0; ch < channels; ch++) {
            if (unused[ch]) {
                Arrays.fill(block, 0, size, 0.0f);
            } else {
                kernel.imdct(transform, spectra[ch], block, re, im);
            }
            var saved = this.saved[ch];
            if (outputLength > 0) {
                var out = output[ch];
                var copy = Math.min(savedLength, outputLength);
                System.arraycopy(saved, 0, out, 0, copy);
                Arrays.fill(out, copy, outputLength, 0.0f);
                var from = Math.max(leftStart, -offset);
                var slopeEnd = Math.min(leftEnd, outputLength - offset);
                if (from < slopeEnd) {
                    kernel.multiplyAdd(block, from, leftSlope, from - leftStart, out, from + offset, slopeEnd - from);
                }
                var flatFrom = Math.max(leftEnd, -offset);
                var flatEnd = Math.min(half, outputLength - offset);
                if (flatFrom < flatEnd) {
                    kernel.add(block, flatFrom, out, flatFrom + offset, flatEnd - flatFrom);
                }
            }
            System.arraycopy(block, half, saved, 0, rightStart);
            kernel.multiply(block, half + rightStart, rightSlope, 0, saved, rightStart, rightEnd - rightStart);
            Arrays.fill(saved, rightEnd, half, 0.0f);
        }
        previousSize = size;
        return outputLength;
    }
}
