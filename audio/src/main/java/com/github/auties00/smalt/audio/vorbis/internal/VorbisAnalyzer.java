/* Ports libvorbis 1.3.7 (lib/block.c, mapping0.c, lpc.c, window.c).
   Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns PCM into Vorbis audio packets: buffers the input, places block boundaries with the
 * envelope detector, and for every block windows, transforms, models, fits the floors, quantizes,
 * couples and codes the residue.
 *
 * <p>Input is appended with {@link #write(float[][], int, int)}; {@link #finish()} marks the end
 * of the stream, which is extrapolated with linear prediction rather than padded with silence.
 * {@link #nextPacket()} returns the next complete packet, or {@code null} when more input is
 * needed. Granule positions count samples from the first input sample; the last packet's
 * granule position is the input length.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation covers only the unmanaged (quality) mode, which codes one packet blob.
 * It takes the window values from libvorbis's own tables (carried in the setup resource) rather than
 * recomputing them, so the coded packets match libvorbis's bit for bit when both round exact halves to
 * even.
 */
public final class VorbisAnalyzer {
    /**
     * One analyzed packet.
     *
     * @param data the packet
     * @param granule the granule position after the packet
     * @param endOfStream whether it is the last packet
     */
    public record AudioPacket(byte[] data, long granule, boolean endOfStream) {
    }

    /**
     * The setup.
     */
    private final CodecSetup setup;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The block sizes.
     */
    private final int[] blockSizes;

    /**
     * The codebooks.
     */
    private final List<EncoderCodebook> books = new ArrayList<>();

    /**
     * The floor encoders.
     */
    private final Floor1Encoder[] floors;

    /**
     * The residue encoders.
     */
    private final ResidueEncoder[] residues;

    /**
     * The psychoacoustic models of the four block types.
     */
    private final Psy[] psy;

    /**
     * The MDCTs of the short and long blocks.
     */
    private final ForwardMdct[] mdct;

    /**
     * The FFTs of the short and long blocks.
     */
    private final PowerSpectrum[] fft;

    /**
     * The rising half-windows of the short and long blocks.
     */
    private final float[][] windows;

    /**
     * The envelope detector.
     */
    private final Envelope envelope;

    /**
     * The bits of the mode number.
     */
    private final int modeBits;

    /**
     * The buffered input of each channel.
     */
    private float[][] pcm;

    /**
     * The number of buffered samples.
     */
    private int pcmCurrent;

    /**
     * The center of the current window in the buffer.
     */
    private int centerW;

    /**
     * The previous, current and next window sizes.
     */
    private int lW;

    /**
     * The current window size.
     */
    private int w;

    /**
     * The next window size.
     */
    private int nW;

    /**
     * The end-of-stream position in the buffer, {@code 0} before the end, {@code -1} when done.
     */
    private int eofFlag;

    /**
     * Whether the start of the stream was extrapolated backwards.
     */
    private boolean preExtrapolated;

    /**
     * The granule position of the current block.
     */
    private long granulePosition;

    /**
     * The global amplitude maximum.
     */
    private float globalAmpmax = -9999.f;

    /**
     * The amplitude maximum carried by the block.
     */
    private float blockAmpmax = -9999.f;

    /**
     * The scratch of the coupling.
     */
    private final Psy.CouplingScratch[] couplingScratch = new Psy.CouplingScratch[4];

    /**
     * Constructs an analyzer.
     *
     * @param setup the encoder configuration, from the setup templates
     */
    public VorbisAnalyzer(CodecSetup setup) {
        this.setup = setup;
        this.channels = setup.channels;
        this.blockSizes = setup.blockSizes;
        for (var book : setup.books) {
            books.add(new EncoderCodebook(book));
        }
        floors = new Floor1Encoder[setup.floors.size()];
        for (var i = 0; i < floors.length; i++) {
            floors[i] = new Floor1Encoder(setup.floors.get(i), books);
        }
        residues = new ResidueEncoder[setup.residueCount];
        for (var i = 0; i < residues.length; i++) {
            residues[i] = new ResidueEncoder(setup.residues[i], setup.residueTypes[i], books);
        }
        psy = new Psy[setup.psys];
        for (var i = 0; i < setup.psys; i++) {
            psy[i] = new Psy(setup.psy[i], setup.psyGlobal, blockSizes[setup.psy[i].blockFlag] / 2, setup.rate);
        }
        mdct = new ForwardMdct[]{new ForwardMdct(blockSizes[0]), new ForwardMdct(blockSizes[1])};
        fft = new PowerSpectrum[]{new PowerSpectrum(blockSizes[0]), new PowerSpectrum(blockSizes[1])};
        windows = new float[][]{window(blockSizes[0]), window(blockSizes[1])};
        envelope = new Envelope(setup.psyGlobal, channels, blockSizes[1]);
        modeBits = Ilog.ilog(setup.modes - 1);
        pcm = new float[channels][blockSizes[1]];
        centerW = blockSizes[1] / 2;
        pcmCurrent = centerW;
    }

    /**
     * Returns the rising half of the Vorbis window of a block size,
     * {@code sin(pi/2 sin^2((i + 0.5) / n pi))}, from libvorbis's tables.
     *
     * @param n the block size, a power of two from 64 to 8192
     * @return the {@code n/2} window values
     */
    private static float[] window(int n) {
        return VorbisSetupData.get().windows[Integer.numberOfTrailingZeros(n) - 6];
    }

    /**
     * Makes room for input ({@code vorbis_analysis_buffer}).
     *
     * @param values the number of samples per channel to append
     */
    private void reserve(int values) {
        if (pcmCurrent + values >= pcm[0].length) {
            var storage = pcmCurrent + values * 2;
            for (var i = 0; i < channels; i++) {
                pcm[i] = Arrays.copyOf(pcm[i], storage);
            }
        }
    }

    /**
     * Appends input ({@code vorbis_analysis_wrote}); NaN and infinite samples are stored as
     * silence.
     *
     * @param planes the samples of each channel, in Vorbis channel order
     * @param offset the index of the first sample
     * @param count the number of samples per channel, positive
     */
    public void write(float[][] planes, int offset, int count) {
        reserve(count);
        for (var c = 0; c < channels; c++) {
            var target = pcm[c];
            System.arraycopy(planes[c], offset, target, pcmCurrent, count);
            for (int i = pcmCurrent, end = pcmCurrent + count; i < end; i++) {
                var x = target[i];
                if (x - x != 0) {
                    target[i] = 0;
                }
            }
        }
        pcmCurrent += count;
        if (!preExtrapolated && pcmCurrent - centerW > blockSizes[1]) {
            preExtrapolate();
        }
    }

    /**
     * Marks the end of the input and extrapolates three long blocks past it.
     */
    public void finish() {
        var order = 32;
        if (!preExtrapolated) {
            preExtrapolate();
        }
        reserve(blockSizes[1] * 3);
        eofFlag = pcmCurrent;
        pcmCurrent += blockSizes[1] * 3;
        var lpc = new float[order];
        for (var i = 0; i < channels; i++) {
            if (eofFlag > order * 2) {
                var n = Math.min(eofFlag, blockSizes[1]);
                lpcFromData(pcm[i], eofFlag - n, lpc, n, order);
                lpcPredict(lpc, pcm[i], eofFlag - order, order, pcm[i], eofFlag, pcmCurrent - eofFlag);
            } else {
                Arrays.fill(pcm[i], eofFlag, pcmCurrent, 0);
            }
        }
    }

    /**
     * Extrapolates the start of the stream backwards with linear prediction
     * ({@code _preextrapolate_helper}).
     */
    private void preExtrapolate() {
        var order = 16;
        var lpc = new float[order];
        var work = new float[pcmCurrent];
        preExtrapolated = true;
        if (pcmCurrent - centerW > order * 2) {
            for (var i = 0; i < channels; i++) {
                for (var j = 0; j < pcmCurrent; j++) {
                    work[j] = pcm[i][pcmCurrent - j - 1];
                }
                lpcFromData(work, 0, lpc, pcmCurrent - centerW, order);
                lpcPredict(lpc, work, pcmCurrent - centerW - order, order, work, pcmCurrent - centerW, centerW);
                for (var j = 0; j < pcmCurrent; j++) {
                    pcm[i][pcmCurrent - j - 1] = work[j];
                }
            }
        }
    }

    /**
     * Computes linear prediction coefficients by the Levinson-Durbin recursion
     * ({@code vorbis_lpc_from_data}).
     *
     * @param data the samples
     * @param offset the index of the first sample
     * @param lpci receives the coefficients
     * @param n the number of samples
     * @param m the order
     */
    static void lpcFromData(float[] data, int offset, float[] lpci, int n, int m) {
        var aut = new double[m + 1];
        var lpc = new double[m];
        var j = m + 1;
        while (j-- > 0) {
            double d = 0;
            for (var i = j; i < n; i++) {
                d += (double) data[offset + i] * data[offset + i - j];
            }
            aut[j] = d;
        }
        var error = aut[0] * (1. + 1e-10);
        var epsilon = 1e-9 * aut[0] + 1e-10;
        for (var i = 0; i < m; i++) {
            var r = -aut[i + 1];
            if (error < epsilon) {
                Arrays.fill(lpc, i, m, 0);
                break;
            }
            for (j = 0; j < i; j++) {
                r -= lpc[j] * aut[i - j];
            }
            r /= error;
            lpc[i] = r;
            for (j = 0; j < i / 2; j++) {
                var tmp = lpc[j];
                lpc[j] += r * lpc[i - 1 - j];
                lpc[i - 1 - j] += r * tmp;
            }
            if ((i & 1) != 0) {
                lpc[j] += lpc[j] * r;
            }
            error *= 1. - r * r;
        }
        var g = .99;
        var damp = g;
        for (j = 0; j < m; j++) {
            lpc[j] *= damp;
            damp *= g;
        }
        for (j = 0; j < m; j++) {
            lpci[j] = (float) lpc[j];
        }
    }

    /**
     * Extends a signal with a linear predictor ({@code vorbis_lpc_predict}).
     *
     * @param coeff the coefficients
     * @param prime the source of the first {@code m} samples
     * @param primeOff the index of the first sample in {@code prime}
     * @param m the order
     * @param data receives the predicted samples
     * @param dataOff the index of the first predicted sample
     * @param n the number of samples to predict
     */
    static void lpcPredict(float[] coeff, float[] prime, int primeOff, int m, float[] data, int dataOff, int n) {
        var work = new float[m + n];
        System.arraycopy(prime, primeOff, work, 0, m);
        for (var i = 0; i < n; i++) {
            var y = 0.f;
            var o = i;
            var p = m;
            for (var j = 0; j < m; j++) {
                y -= work[o++] * coeff[--p];
            }
            data[dataOff + i] = work[o] = y;
        }
    }

    /**
     * Returns the next packet, or {@code null} when more input is needed.
     *
     * @return the packet
     */
    public AudioPacket nextPacket() {
        if (!preExtrapolated || eofFlag == -1) {
            return null;
        }
        var beginW = centerW - blockSizes[w] / 2;
        var bp = envelope.search(pcm, pcmCurrent, centerW, blockSizes, w);
        if (bp == -1) {
            if (eofFlag == 0) {
                return null;
            }
            nW = 0;
        } else {
            nW = blockSizes[0] == blockSizes[1] ? 0 : bp;
        }
        var centerNext = centerW + blockSizes[w] / 4 + blockSizes[nW] / 4;
        var blockBound = centerNext + blockSizes[nW] / 2;
        if (pcmCurrent < blockBound) {
            return null;
        }
        int blockType;
        if (w != 0) {
            blockType = lW == 0 || nW == 0 ? 0 : 1;
        } else {
            blockType = envelope.marked(centerW, blockSizes, lW, w, nW) ? 0 : 1;
        }
        var granule = granulePosition;
        if (blockAmpmax > globalAmpmax) {
            globalAmpmax = blockAmpmax;
        }
        globalAmpmax = Psy.ampmaxDecay(globalAmpmax, setup.psyGlobal, blockSizes[w], setup.rate);
        blockAmpmax = globalAmpmax;
        var n = blockSizes[w];
        var block = new float[channels][];
        for (var i = 0; i < channels; i++) {
            block[i] = Arrays.copyOfRange(pcm[i], beginW, beginW + n);
        }
        var blockLW = lW;
        var blockW = w;
        var blockNW = nW;
        var endOfStream = false;
        if (eofFlag != 0 && centerW >= eofFlag) {
            eofFlag = -1;
            endOfStream = true;
        } else {
            var newCenterNext = blockSizes[1] / 2;
            var movement = centerNext - newCenterNext;
            if (movement > 0) {
                envelope.shift(movement);
                pcmCurrent -= movement;
                for (var i = 0; i < channels; i++) {
                    System.arraycopy(pcm[i], movement, pcm[i], 0, pcmCurrent);
                }
                lW = w;
                w = nW;
                centerW = newCenterNext;
                if (eofFlag != 0) {
                    eofFlag -= movement;
                    if (eofFlag <= 0) {
                        eofFlag = -1;
                    }
                    if (centerW >= eofFlag) {
                        granulePosition += movement - (centerW - eofFlag);
                    } else {
                        granulePosition += movement;
                    }
                } else {
                    granulePosition += movement;
                }
            }
        }
        var data = analyze(block, blockLW, blockW, blockNW, blockType);
        return new AudioPacket(data, granule, endOfStream);
    }

    /**
     * Applies the Vorbis window of a block ({@code _vorbis_apply_window}).
     *
     * @param d the block
     * @param lW the previous window size
     * @param w the window size
     * @param nW the next window size
     */
    private void applyWindow(float[] d, int lW, int w, int nW) {
        lW = w != 0 ? lW : 0;
        nW = w != 0 ? nW : 0;
        var windowLW = windows[lW];
        var windowNW = windows[nW];
        var n = blockSizes[w];
        var ln = blockSizes[lW];
        var rn = blockSizes[nW];
        var leftBegin = n / 4 - ln / 4;
        var leftEnd = leftBegin + ln / 2;
        var rightBegin = n / 2 + n / 4 - rn / 4;
        var rightEnd = rightBegin + rn / 2;
        var i = 0;
        for (; i < leftBegin; i++) {
            d[i] = 0.f;
        }
        for (var p = 0; i < leftEnd; i++, p++) {
            d[i] *= windowLW[p];
        }
        i = rightBegin;
        for (var p = rn / 2 - 1; i < rightEnd; i++, p--) {
            d[i] *= windowNW[p];
        }
        for (; i < n; i++) {
            d[i] = 0.f;
        }
    }

    /**
     * Codes one block ({@code mapping0_forward} for the middle packet blob).
     *
     * @param pcm the block of each channel, overwritten
     * @param lW the previous window size
     * @param w the window size
     * @param nW the next window size
     * @param blockType the block type: 0 impulse or transition, 1 padding or long
     * @return the packet
     */
    private byte[] analyze(float[][] pcm, int lW, int w, int nW, int blockType) {
        var n = blockSizes[w];
        var half = n / 2;
        var info = setup.maps[w];
        var look = psy[blockType + (w != 0 ? 2 : 0)];
        var globalMax = blockAmpmax;
        var localMax = new float[channels];
        var gmdct = new float[channels][half];
        var iwork = new int[channels][half];
        var logFft = new float[channels][half];
        var floorPosts = new int[channels][];
        var nonzero = new int[channels];
        for (var i = 0; i < channels; i++) {
            var scale = 4.f / n;
            var scaleDb = (float) (Scales.todB(scale) + .345);
            var data = pcm[i];
            applyWindow(data, lW, w, nW);
            mdct[w].forward(data, 0, gmdct[i], 0);
            var power = fft[w].compute(data);
            var log = logFft[i];
            log[0] = (float) (scaleDb + Scales.todB(power[0]) + .345);
            localMax[i] = log[0];
            for (var j = 1; j < half; j++) {
                var temp = power[j];
                temp = log[j] = (float) (scaleDb + .5f * Scales.todB(temp) + .345);
                if (temp > localMax[i]) {
                    localMax[i] = temp;
                }
            }
            if (localMax[i] > 0.f) {
                localMax[i] = 0.f;
            }
            if (localMax[i] > globalMax) {
                globalMax = localMax[i];
            }
        }
        var noise = new float[half];
        var tone = new float[half];
        var logMdct = new float[half];
        var logMask = new float[half];
        for (var i = 0; i < channels; i++) {
            var submap = info.channelMux[i];
            var spectrum = gmdct[i];
            for (var j = 0; j < half; j++) {
                logMdct[j] = (float) (Scales.todB(spectrum[j]) + .345);
            }
            look.noiseMask(logMdct, noise);
            look.toneMask(logFft[i], tone, globalMax, localMax[i]);
            look.offsetAndMix(noise, tone, 1, logMask, spectrum, logMdct);
            floorPosts[i] = floors[info.floorSubmap[submap]].fit(logMdct, logMask);
        }
        blockAmpmax = globalMax;
        var out = new LsbBitWriter();
        out.write(0, 1);
        out.write(w, modeBits);
        if (w != 0) {
            out.write(lW, 1);
            out.write(nW, 1);
        }
        for (var i = 0; i < channels; i++) {
            var submap = info.channelMux[i];
            nonzero[i] = floors[info.floorSubmap[submap]].encode(out, floorPosts[i], iwork[i], half) ? 1 : 0;
        }
        var blob = PsyGlobal.PACKET_BLOBS / 2;
        var partition = look.vi.normalP != 0 ? look.vi.normalPartition : 16;
        var scratchIndex = blockType + (w != 0 ? 2 : 0);
        var scratch = couplingScratch[scratchIndex];
        if (scratch == null) {
            scratch = couplingScratch[scratchIndex] = new Psy.CouplingScratch(channels, partition, info.couplingSteps);
        }
        look.coupleQuantizeNormalize(blob, setup.psyGlobal, info, gmdct, iwork, nonzero,
                setup.psyGlobal.slidingLowpass[w][blob], channels, scratch);
        var bundle = new int[channels][];
        var zero = new boolean[channels];
        for (var i = 0; i < info.submaps; i++) {
            var inBundle = 0;
            var residueNumber = info.residueSubmap[i];
            for (var j = 0; j < channels; j++) {
                if (info.channelMux[j] == i) {
                    zero[inBundle] = nonzero[j] != 0;
                    bundle[inBundle++] = iwork[j];
                }
            }
            var classes = residues[residueNumber].classify(bundle, zero, inBundle);
            residues[residueNumber].forward(out, bundle, zero, inBundle, classes, half);
        }
        return out.toByteArray();
    }
}
