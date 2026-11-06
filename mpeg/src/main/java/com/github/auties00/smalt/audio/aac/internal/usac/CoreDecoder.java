/* Ports the ISO/IEC 23003-3:2019 reference software (decode_chan_ele.c decodeChannelElement, usac_cplx_pred.c,
   stereo.c usac_synt). Copyright Sony Corporation, Fraunhofer IIS, VoiceAge Corp. and ISO/IEC 2008; ISO/IEC
   reference software licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernel;
import com.github.auties00.smalt.audio.aac.internal.Codebooks;

import java.util.Arrays;

/**
 * Decodes the core coder of the single channel, channel pair and LFE elements of USAC
 * ({@code UsacCoreCoderData}, ISO/IEC 23003-3 subclause 5.3.2): frequency domain and linear prediction domain
 * channel streams, M/S and complex prediction stereo, TNS and the filterbank transitions.
 *
 * <p>Instances are not thread-safe.
 */
final class CoreDecoder {
    private final int frameLength;
    private final Imdct imdct;
    private final FdSynthesis synthesis;
    private final LpdDecoder lpd;
    private TimeWarpedSynthesis warpedSynthesis;
    private float[] warpedTransform;

    private final int[] msMask = new int[8 * FdChannel.MAX_BANDS];
    private final int[][] alphaRe = new int[8][FdChannel.MAX_BANDS];
    private final int[][] alphaIm = new int[8][FdChannel.MAX_BANDS];
    private final int[][] predictionUsed = new int[8][FdChannel.MAX_BANDS];

    private final float[] downmixRe;
    private final float[] downmixIm;
    private final float[] lineAlphaRe;
    private final float[] lineAlphaIm;
    private final boolean[] lineUsed;
    private final float[] fac;

    /**
     * Constructs the decoder of a frame length.
     *
     * @param frameLength the core frame length, 768 or 1024
     * @param coreRate the sampling frequency of the core coder
     * @param kernel the kernel running the transforms
     */
    CoreDecoder(int frameLength, int coreRate, AacKernel kernel) {
        this.frameLength = frameLength;
        this.imdct = new Imdct(kernel);
        this.synthesis = new FdSynthesis(frameLength, imdct);
        this.lpd = new LpdDecoder(frameLength, coreRate, imdct);
        this.downmixRe = new float[frameLength];
        this.downmixIm = new float[frameLength];
        this.lineAlphaRe = new float[frameLength];
        this.lineAlphaIm = new float[frameLength];
        this.lineUsed = new boolean[frameLength];
        this.fac = new float[2 * frameLength / 8];
    }

    /**
     * Holds the complex prediction state of one channel pair element across frames.
     */
    static final class PairState {
        final int[] previousAlphaRe = new int[FdChannel.MAX_BANDS];
        final int[] previousAlphaIm = new int[FdChannel.MAX_BANDS];
        final float[] previousDownmix;

        PairState(int frameLength) {
            previousDownmix = new float[frameLength];
        }
    }

    /**
     * Decodes a single channel or LFE element into the channel's output.
     *
     * @param reader the bit source
     * @param channel the state of the channel
     * @param lfe whether the element is an LFE element
     * @param element the element configuration
     * @param independent whether the frame is independent
     * @throws MediaException.Malformed if the element is corrupt
     */
    void decodeSingle(AacBitReader reader, UsacChannel channel, boolean lfe, UsacConfig.Element element,
                      boolean independent) {
        channel.lastLpd = channel.lpd;
        var lpdMode = !lfe && reader.readFlag();
        channel.lpd = lpdMode;
        if (lpdMode) {
            checkLpd(element);
            lpd.decode(reader, channel, independent);
            return;
        }
        var tns = !lfe && reader.readFlag();
        readFdStream(reader, channel, element, independent, false, false, tns ? 1 : 0);
        channel.fd.applyTns();
        synthesize(channel, element);
    }

    /**
     * Rejects a linear prediction domain frame of an element that uses the time-warped MDCT.
     *
     * @param element the element configuration
     * @throws MediaException.Unsupported if the element uses the time-warped MDCT
     */
    private static void checkLpd(UsacConfig.Element element) {
        if (element.timeWarping()) {
            // TODO: the transitions between the time-warped filterbank and the LPD coder (usac_past_tw,
            //  usac_td2buffer and the time-warped reset of the LPD decoder of ISO/IEC 23003-3) are not decoded; the
            //  LPD coder of this package follows libxaac, which has no time-warped filterbank.
            throw new MediaException.Unsupported("usac tw-mdct lpd",
                    "the USAC time-warped MDCT next to linear prediction domain frames is not supported");
        }
    }

    /**
     * Decodes a channel pair element into the two channels' outputs.
     *
     * @param reader the bit source
     * @param left the first channel
     * @param right the second channel
     * @param state the element's complex prediction state
     * @param element the element configuration
     * @param independent whether the frame is independent
     * @throws MediaException.Malformed if the element is corrupt
     */
    void decodePair(AacBitReader reader, UsacChannel left, UsacChannel right, PairState state,
                    UsacConfig.Element element, boolean independent) {
        left.lastLpd = left.lpd;
        right.lastLpd = right.lpd;
        left.lpd = reader.readFlag();
        right.lpd = reader.readFlag();
        if (!left.lpd && !right.lpd) {
            decodeFdPair(reader, left, right, state, element, independent);
            return;
        }
        checkLpd(element);
        for (var index = 0; index < 2; index++) {
            var channel = index == 0 ? left : right;
            if (channel.lpd) {
                lpd.decode(reader, channel, independent);
            } else {
                var tns = reader.readFlag();
                readFdStream(reader, channel, element, independent, false, false, tns ? 1 : 0);
                channel.fd.applyTns();
                synthesize(channel, element);
            }
        }
    }

    private void decodeFdPair(AacBitReader reader, UsacChannel left, UsacChannel right, PairState state,
                              UsacConfig.Element element, boolean independent) {
        var l = left.fd;
        var r = right.fd;
        var tnsActive = reader.readFlag();
        var commonWindow = reader.readFlag();
        var mask = 0;
        var predictionDirection = 0;
        var complex = false;
        var usePrevious = false;
        var maxSfbSte = 0;
        if (commonWindow) {
            l.readInfo(reader, left.lastSequence);
            var rightMaxSfb = l.maxSfb;
            if (!reader.readFlag()) {
                rightMaxSfb = reader.read(l.shortWindows() ? 4 : 6);
            }
            r.copyInfo(l, rightMaxSfb);
            maxSfbSte = Math.max(l.maxSfb, rightMaxSfb);
            mask = readMask(reader, l, maxSfbSte);
            if (mask == 3 && element.stereoConfigIndex() == 0) {
                var flags = readPrediction(reader, state, l.groupCount, maxSfbSte, independent);
                predictionDirection = flags & 1;
                complex = (flags & 2) != 0;
                usePrevious = (flags & 4) != 0;
            }
        }
        var commonTw = false;
        if (element.timeWarping()) {
            commonTw = reader.readFlag();
            if (commonTw) {
                l.readTimeWarping(reader);
                r.copyTimeWarping(l);
            }
        }
        var tnsLeft = 0;
        var tnsRight = 0;
        var tnsOnLr = true;
        if (tnsActive) {
            var commonTns = commonWindow && reader.readFlag();
            tnsOnLr = reader.readFlag();
            if (commonTns) {
                l.readTns(reader);
                r.copyTns(l);
                tnsLeft = -1;
                tnsRight = -1;
            } else if (reader.readFlag()) {
                tnsLeft = 1;
                tnsRight = 1;
            } else {
                tnsRight = reader.read(1);
                tnsLeft = 1 - tnsRight;
            }
        }
        readFdStream(reader, left, element, independent, commonWindow, commonTw, tnsLeft);
        readFdStream(reader, right, element, independent, commonWindow, commonTw, tnsRight);
        var info = commonWindow ? l : r;
        if (mask == 3 && element.stereoConfigIndex() == 0) {
            previousDownmix(info, left.savedSpectrum, right.savedSpectrum, state.previousDownmix,
                    predictionDirection);
        }
        for (var k = 0; k < 2; k++) {
            if (k != (tnsOnLr ? 1 : 0)) {
                if (mask != 3) {
                    Arrays.fill(state.previousAlphaRe, 0);
                    Arrays.fill(state.previousAlphaIm, 0);
                }
                if (mask == 3 && element.stereoConfigIndex() == 0) {
                    predict(l, l.spectrum, r.spectrum, state.previousDownmix, predictionDirection, complex,
                            usePrevious, left.previousShape);
                } else if (mask != 0) {
                    applyMidSide(l, l.spectrum, r.spectrum);
                }
            } else {
                l.applyTns();
                r.applyTns();
            }
        }
        saveSpectrum(info, l.spectrum, left.savedSpectrum);
        saveSpectrum(info, r.spectrum, right.savedSpectrum);
        synthesize(left, element);
        synthesize(right, element);
    }

    /**
     * Reads one {@code fd_channel_stream}.
     *
     * @param tnsPresent 1 to read TNS data, 0 to clear it, -1 when common TNS data was read
     */
    private void readFdStream(AacBitReader reader, UsacChannel channel, UsacConfig.Element element,
                              boolean independent, boolean commonWindow, boolean commonTw, int tnsPresent) {
        var fd = channel.fd;
        fd.globalGain = reader.read(8);
        fd.noiseLevel = element.noiseFilling() ? reader.read(8) : 0;
        if (!commonWindow) {
            fd.readInfo(reader, channel.lastSequence);
        }
        if (!commonTw) {
            if (element.timeWarping()) {
                fd.readTimeWarping(reader);
            } else {
                fd.twPresent = false;
            }
        }
        fd.readScalefactors(reader);
        if (tnsPresent == 0) {
            fd.clearTns();
        } else if (tnsPresent == 1) {
            fd.readTns(reader);
        }
        fd.readSpectrum(reader, channel.coder, independent, element.noiseFilling(), channel.seed);
        fd.facPresent = reader.readFlag();
        if (fd.facPresent) {
            var lfac = fd.shortWindows() ? frameLength / 16 : frameLength / 8;
            lpd.readFac(reader, lfac, fd.facData);
        }
        reader.checkNotOverrun();
    }

    private void synthesize(UsacChannel channel, UsacConfig.Element element) {
        var fd = channel.fd;
        if (element.timeWarping()) {
            synthesizeWarped(channel);
            return;
        }
        if (channel.lastLpd) {
            lpd.finishBeforeFd(channel);
        }
        float[] facSignal = null;
        if (fd.facPresent && channel.lastLpd) {
            var lfac = fd.shortWindows() ? frameLength / 16 : frameLength / 8;
            lpd.decodeFdFac(channel, fd.facData, lfac, fac);
            facSignal = fac;
        }
        synthesis.synthesize(fd.spectrum, fd.windowSequence, fd.windowShape, channel.previousShape, channel.lastLpd,
                facSignal, channel.overlap, channel.output, 0);
        if (channel.lastLpd) {
            lpd.postFilterTransition(channel, fd.shortWindows());
        }
        channel.previousShape = fd.windowShape;
        channel.lastSequence = fd.windowSequence;
    }

    /**
     * Synthesizes a frequency domain frame of an element that uses the time-warped MDCT, after a frequency domain
     * frame.
     */
    private void synthesizeWarped(UsacChannel channel) {
        var fd = channel.fd;
        if (warpedSynthesis == null) {
            warpedSynthesis = new TimeWarpedSynthesis(frameLength);
            warpedTransform = new float[2 * frameLength];
        }
        if (channel.warp == null) {
            channel.warp = new TimeWarpedSynthesis.State(frameLength);
        }
        warpedSynthesis.prepare(channel.warp, fd.twPresent, fd.twRatio, fd.windowSequence, channel.lastSequence);
        if (fd.shortWindows()) {
            var shortLength = frameLength / 8;
            for (var k = 0; k < 8; k++) {
                imdct.inverse(fd.spectrum, k * shortLength, shortLength, warpedTransform, 2 * k * shortLength);
            }
        } else {
            imdct.inverse(fd.spectrum, 0, frameLength, warpedTransform, 0);
        }
        warpedSynthesis.synthesize(channel.warp, warpedTransform, fd.windowSequence, channel.lastSequence,
                fd.windowShape, channel.previousShape, channel.overlap, channel.output, 0);
        channel.previousShape = fd.windowShape;
        channel.lastSequence = fd.windowSequence;
    }

    /**
     * Reads {@code ms_mask_present} and the band flags.
     *
     * @return {@code ms_mask_present}
     */
    private int readMask(AacBitReader reader, FdChannel info, int maxSfbSte) {
        var present = reader.read(2);
        var bands = info.bandCount();
        Arrays.fill(msMask, 0);
        if (present == 1) {
            for (var g = 0; g < info.groupCount; g++) {
                for (var b = 0; b < maxSfbSte; b++) {
                    msMask[g * FdChannel.MAX_BANDS + b] = reader.read(1);
                }
            }
        } else if (present == 2) {
            for (var g = 0; g < info.groupCount; g++) {
                for (var b = 0; b < bands; b++) {
                    msMask[g * FdChannel.MAX_BANDS + b] = 1;
                }
            }
        }
        return present;
    }

    /**
     * Reads {@code cplx_pred_data} ({@code usac_get_cplx_pred_data}).
     *
     * @return {@code pred_dir} in bit 0, {@code complex_coef} in bit 1, {@code use_prev_frame} in bit 2
     */
    private int readPrediction(AacBitReader reader, PairState state, int groups, int maxSfbSte,
                               boolean independent) {
        var all = reader.readFlag();
        for (var g = 0; g < groups; g++) {
            Arrays.fill(predictionUsed[g], 0);
            for (var sfb = 0; sfb < maxSfbSte; sfb += 2) {
                var used = all ? 1 : reader.read(1);
                predictionUsed[g][sfb] = used;
                if (sfb + 1 < maxSfbSte) {
                    predictionUsed[g][sfb + 1] = used;
                }
            }
        }
        var direction = reader.read(1);
        var complex = reader.readFlag();
        var usePrevious = complex && !independent && reader.readFlag();
        var deltaTime = !independent && reader.readFlag();
        for (var g = 0; g < groups; g++) {
            for (var sfb = 0; sfb < maxSfbSte; sfb += 2) {
                int lastRe;
                int lastIm;
                if (deltaTime) {
                    lastRe = state.previousAlphaRe[sfb];
                    lastIm = state.previousAlphaIm[sfb];
                } else if (sfb > 0) {
                    lastRe = alphaRe[g][sfb - 1];
                    lastIm = alphaIm[g][sfb - 1];
                } else {
                    lastRe = 0;
                    lastIm = 0;
                }
                if (predictionUsed[g][sfb] == 1) {
                    alphaRe[g][sfb] = -Codebooks.decodeScalefactor(reader) + lastRe;
                    alphaIm[g][sfb] = complex ? -Codebooks.decodeScalefactor(reader) + lastIm : 0;
                } else {
                    alphaRe[g][sfb] = 0;
                    alphaIm[g][sfb] = 0;
                }
                if (sfb + 1 < maxSfbSte) {
                    alphaRe[g][sfb + 1] = alphaRe[g][sfb];
                    alphaIm[g][sfb + 1] = alphaIm[g][sfb];
                }
                state.previousAlphaRe[sfb] = alphaRe[g][sfb];
                state.previousAlphaIm[sfb] = alphaIm[g][sfb];
            }
            for (var sfb = maxSfbSte; sfb < FdChannel.MAX_BANDS; sfb++) {
                alphaRe[g][sfb] = 0;
                alphaIm[g][sfb] = 0;
                state.previousAlphaRe[sfb] = 0;
                state.previousAlphaIm[sfb] = 0;
            }
        }
        return direction | (complex ? 2 : 0) | (usePrevious ? 4 : 0);
    }

    /**
     * Applies M/S stereo to the bands the mask selects ({@code usac_synt}).
     */
    private void applyMidSide(FdChannel info, float[] left, float[] right) {
        var offsets = info.offsets();
        var bands = info.bandCount();
        var window = info.windowLength();
        var w = 0;
        for (var g = 0; g < info.groupCount; g++) {
            for (var gw = 0; gw < info.groupLength[g]; gw++, w++) {
                var base = w * window;
                for (var b = 0; b < bands; b++) {
                    if (msMask[g * FdChannel.MAX_BANDS + b] == 0) {
                        continue;
                    }
                    for (var i = base + offsets[b]; i < base + offsets[b + 1]; i++) {
                        var m = left[i];
                        var s = right[i];
                        left[i] = s + m;
                        right[i] = m - s;
                    }
                }
            }
        }
    }

    /**
     * Computes the previous frame's downmix from its saved spectra ({@code usac_cplx_pred_prev_dmx}).
     */
    private void previousDownmix(FdChannel info, float[] left, float[] right, float[] out, int direction) {
        var window = info.windowLength();
        var offset = frameLength - window;
        for (var i = 0; i < window; i++) {
            out[i] = direction == 0 ? 0.5f * (left[i + offset] + right[i + offset])
                    : 0.5f * (left[i + offset] - right[i + offset]);
        }
    }

    /**
     * Saves the last window of a final spectrum ({@code usac_cplx_save_prev}).
     */
    private void saveSpectrum(FdChannel info, float[] spectrum, float[] saved) {
        var window = info.windowLength();
        var offset = frameLength - window;
        System.arraycopy(spectrum, offset, saved, offset, window);
    }

    /**
     * Applies complex prediction stereo ({@code usac_decode_cplx_pred}).
     */
    private void predict(FdChannel info, float[] left, float[] right, float[] previousDownmix, int direction,
                         boolean complex, boolean usePrevious, int previousShape) {
        var offsets = info.offsets();
        var window = info.windowLength();
        var windows = info.windowCount();
        var i = 0;
        var group = 0;
        var groupEnd = info.groupLength[0];
        for (var w = 0; w < windows; w++) {
            if (w >= groupEnd) {
                group++;
                groupEnd += info.groupLength[group];
            }
            var sfb = 0;
            for (var bin = 0; bin < window; bin++) {
                if (bin >= offsets[sfb + 1]) {
                    sfb++;
                }
                lineUsed[i] = predictionUsed[group][sfb] == 1;
                lineAlphaRe[i] = alphaRe[group][sfb] * 0.1f;
                lineAlphaIm[i] = complex ? alphaIm[group][sfb] * 0.1f : 0f;
                i++;
            }
        }
        for (i = 0; i < frameLength; i++) {
            if (lineUsed[i]) {
                downmixRe[i] = left[i];
            } else if (direction == 0) {
                downmixRe[i] = 0.5f * (left[i] + right[i]);
            } else {
                downmixRe[i] = 0.5f * (left[i] - right[i]);
            }
        }
        if (complex) {
            var shape = info.windowShape;
            var previous = previousShape;
            for (var w = 0; w < windows; w++) {
                var base = w * window;
                var previousBase = w > 0 ? base - window : usePrevious ? -1 : -2;
                mdst(base, previousBase, previousDownmix, window, info.windowSequence, shape, previous);
                previous = shape;
            }
        } else {
            Arrays.fill(downmixIm, 0f);
        }
        for (i = 0; i < frameLength; i++) {
            if (!lineUsed[i]) {
                continue;
            }
            if (direction == 0) {
                var side = right[i] - lineAlphaRe[i] * left[i] - lineAlphaIm[i] * downmixIm[i];
                right[i] = left[i] - side;
                left[i] = left[i] + side;
            } else {
                var mid = right[i] - lineAlphaRe[i] * left[i] - lineAlphaIm[i] * downmixIm[i];
                right[i] = mid - left[i];
                left[i] = mid + left[i];
            }
        }
    }

    private static final float[] LONG_SINE_CURRENT = {-0.000000f, -0.000000f, 0.500000f, 0.000000f, -0.500000f,
            0.000000f, 0.000000f};
    private static final float[] LONG_KBD_CURRENT = {0.091497f, -0.000000f, 0.581427f, 0.000000f, -0.581427f,
            0.000000f, -0.091497f};
    private static final float[] LONG_SINE_KBD_CURRENT = {0.045748f, 0.057238f, 0.540714f, 0.000000f, -0.540714f,
            -0.057238f, -0.045748f};
    private static final float[] LONG_KBD_SINE_CURRENT = {0.045748f, -0.057238f, 0.540714f, 0.000000f, -0.540714f,
            0.057238f, -0.045748f};
    private static final float[] START_SINE_CURRENT = {0.102658f, 0.103791f, 0.567149f, 0.000000f, -0.567149f,
            -0.103791f, -0.102658f};
    private static final float[] START_KBD_CURRENT = {0.150512f, 0.047969f, 0.608574f, 0.000000f, -0.608574f,
            -0.047969f, -0.150512f};
    private static final float[] START_SINE_KBD_CURRENT = {0.104763f, 0.105207f, 0.567861f, 0.000000f, -0.567861f,
            -0.105207f, -0.104763f};
    private static final float[] START_KBD_SINE_CURRENT = {0.148406f, 0.046553f, 0.607863f, 0.000000f, -0.607863f,
            -0.046553f, -0.148406f};
    private static final float[] STOP_SINE_CURRENT = {0.102658f, -0.103791f, 0.567149f, 0.000000f, -0.567149f,
            0.103791f, -0.102658f};
    private static final float[] STOP_KBD_CURRENT = {0.150512f, -0.047969f, 0.608574f, 0.000000f, -0.608574f,
            0.047969f, -0.150512f};
    private static final float[] STOP_SINE_KBD_CURRENT = {0.148406f, -0.046553f, 0.607863f, 0.000000f, -0.607863f,
            0.046553f, -0.148406f};
    private static final float[] STOP_KBD_SINE_CURRENT = {0.104763f, -0.105207f, 0.567861f, 0.000000f, -0.567861f,
            0.105207f, -0.104763f};
    private static final float[] STOP_START_SINE_CURRENT = {0.205316f, -0.000000f, 0.634298f, 0.000000f,
            -0.634298f, 0.000000f, -0.205316f};
    private static final float[] STOP_START_KBD_CURRENT = {0.209526f, -0.000000f, 0.635722f, 0.000000f, -0.635722f,
            0.000000f, -0.209526f};
    private static final float[] STOP_START_SINE_KBD_CURRENT = {0.207421f, 0.001416f, 0.635010f, 0.000000f,
            -0.635010f, -0.001416f, -0.207421f};
    private static final float[] STOP_START_KBD_SINE_CURRENT = {0.207421f, -0.001416f, 0.635010f, 0.000000f,
            -0.635010f, 0.001416f, -0.207421f};
    private static final float[] LONG_SINE_PREVIOUS = {-0.000000f, 0.106103f, 0.250000f, 0.318310f, 0.250000f,
            0.106103f, -0.000000f};
    private static final float[] LONG_KBD_PREVIOUS = {0.059509f, 0.123714f, 0.186579f, 0.213077f, 0.186579f,
            0.123714f, 0.059509f};
    private static final float[] STOP_SINE_PREVIOUS = {0.038498f, 0.039212f, 0.039645f, 0.039790f, 0.039645f,
            0.039212f, 0.038498f};
    private static final float[] STOP_KBD_PREVIOUS = {0.026142f, 0.026413f, 0.026577f, 0.026631f, 0.026577f,
            0.026413f, 0.026142f};

    private static final float[][][] CURRENT = {
            {LONG_SINE_CURRENT, LONG_SINE_KBD_CURRENT, LONG_KBD_SINE_CURRENT, LONG_KBD_CURRENT},
            {START_SINE_CURRENT, START_SINE_KBD_CURRENT, START_KBD_SINE_CURRENT, START_KBD_CURRENT},
            {STOP_SINE_CURRENT, STOP_SINE_KBD_CURRENT, STOP_KBD_SINE_CURRENT, STOP_KBD_CURRENT},
            {STOP_START_SINE_CURRENT, STOP_START_SINE_KBD_CURRENT, STOP_START_KBD_SINE_CURRENT,
                    STOP_START_KBD_CURRENT}
    };

    /**
     * Estimates the MDST of a downmix window by filtering the current and previous MDCT ({@code mdct2mdst}).
     *
     * @param base the index of the window in {@link #downmixRe}
     * @param previousBase the index of the previous window in {@link #downmixRe}, -1 for {@code external},
     * -2 for none
     */
    private void mdst(int base, int previousBase, float[] external, int length, int sequence, int shape,
                      int previousShape) {
        var family = switch (sequence) {
            case FdChannel.LONG_START -> 1;
            case FdChannel.LONG_STOP -> 2;
            case FdChannel.STOP_START -> 3;
            default -> 0;
        };
        var current = CURRENT[family][previousShape * 2 + shape];
        var previous = family >= 2 ? (previousShape == 1 ? STOP_KBD_PREVIOUS : STOP_SINE_PREVIOUS)
                : (previousShape == 1 ? LONG_KBD_PREVIOUS : LONG_SINE_PREVIOUS);
        Arrays.fill(downmixIm, base, base + length, 0f);
        filterAndAdd(downmixRe, base, length, current, base, 1f, 1f);
        if (previousBase == -1) {
            filterAndAdd(external, 0, length, previous, base, -1f, 1f);
        } else if (previousBase >= 0) {
            filterAndAdd(downmixRe, previousBase, length, previous, base, -1f, 1f);
        }
    }

    private void filterAndAdd(float[] in, int o, int length, float[] f, int outBase, float even, float odd) {
        var out = downmixIm;
        var s = f[6] * in[o + 2] + f[5] * in[o + 1] + f[4] * in[o] + f[3] * in[o] + f[2] * in[o + 1]
                + f[1] * in[o + 2] + f[0] * in[o + 3];
        out[outBase] += s * even;
        s = f[6] * in[o + 1] + f[5] * in[o] + f[4] * in[o] + f[3] * in[o + 1] + f[2] * in[o + 2] + f[1] * in[o + 3]
                + f[0] * in[o + 4];
        out[outBase + 1] += s * odd;
        s = f[6] * in[o] + f[5] * in[o] + f[4] * in[o + 1] + f[3] * in[o + 2] + f[2] * in[o + 3] + f[1] * in[o + 4]
                + f[0] * in[o + 5];
        out[outBase + 2] += s * even;
        int i;
        for (i = 3; i < length - 4; i += 2) {
            s = f[6] * in[o + i - 3] + f[5] * in[o + i - 2] + f[4] * in[o + i - 1] + f[3] * in[o + i]
                    + f[2] * in[o + i + 1] + f[1] * in[o + i + 2] + f[0] * in[o + i + 3];
            out[outBase + i] += s * odd;
            s = f[6] * in[o + i - 2] + f[5] * in[o + i - 1] + f[4] * in[o + i] + f[3] * in[o + i + 1]
                    + f[2] * in[o + i + 2] + f[1] * in[o + i + 3] + f[0] * in[o + i + 4];
            out[outBase + i + 1] += s * even;
        }
        i = length - 3;
        s = f[6] * in[o + i - 3] + f[5] * in[o + i - 2] + f[4] * in[o + i - 1] + f[3] * in[o + i]
                + f[2] * in[o + i + 1] + f[1] * in[o + i + 2] + f[0] * in[o + i + 2];
        out[outBase + i] += s * odd;
        i = length - 2;
        s = f[6] * in[o + i - 3] + f[5] * in[o + i - 2] + f[4] * in[o + i - 1] + f[3] * in[o + i]
                + f[2] * in[o + i + 1] + f[1] * in[o + i + 1] + f[0] * in[o + i];
        out[outBase + i] += s * even;
        i = length - 1;
        s = f[6] * in[o + i - 3] + f[5] * in[o + i - 2] + f[4] * in[o + i - 1] + f[3] * in[o + i]
                + f[2] * in[o + i] + f[1] * in[o + i - 1] + f[0] * in[o + i - 2];
        out[outBase + i] += s * odd;
    }
}
