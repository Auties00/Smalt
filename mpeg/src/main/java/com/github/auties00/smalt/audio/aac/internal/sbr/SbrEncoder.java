package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacKernel;
import com.github.auties00.smalt.audio.aac.internal.ElementId;
import com.github.auties00.smalt.audio.aac.internal.EncoderEngine;
import com.github.auties00.smalt.audio.aac.internal.EncoderLayout;

import java.util.Arrays;
import java.util.Optional;

/**
 * Encodes planar float samples into HE-AAC raw data blocks: an AAC Low Complexity core at half the input rate
 * with spectral band replication for every single channel and channel pair element (HE-AAC v1), or a mono core
 * with parametric stereo for a stereo input (HE-AAC v2).
 *
 * <p>The input is split into 64 complex QMF subbands at the input rate. The lowest 32 subbands, cleared from the
 * crossover of their element up, are synthesized into the core's input at half the rate; the spectral band
 * replication data of each frame describes the subbands from the crossover to the stop frequency over the time
 * the frame's core output covers, and travels in a fill element after its channel element. With parametric
 * stereo the two channels are downmixed in the QMF domain first. A frame codes {@code 2 * frameLength} input
 * samples; a decoder outputs input sample {@code i} at output sample {@code i + delay()}.
 *
 * <p>Instances are not thread-safe.
 */
public final class SbrEncoder {
    /**
     * The QMF columns between the column aligned with a decoder output slot and the column of the input whose
     * core coding the slot's low band holds, ahead of the core's frame delay: the six slots the decoder reads
     * its low band behind its output ({@code t_HFGen - t_HFAdj}) and the nine columns of the encoder's synthesis
     * and the decoder's analysis banks.
     */
    private static final int ALIGNMENT = 6 + 9;

    /**
     * The input samples the decoder's output lags behind the input beyond twice the core's frame delay: the
     * 577 samples of the encoder's QMF analysis and synthesis, the 578 of the decoder's (its 32-band analysis of
     * the core and 64-band synthesis) and the decoder's six-slot lag.
     */
    private static final int EXTRA_DELAY = 577 + 578 + 6 * 64;

    /**
     * The input rate.
     */
    private final int outputRate;

    /**
     * The core frame length, 1024 or 960.
     */
    private final int frameLength;

    /**
     * The QMF columns per frame.
     */
    private final int columnsPerFrame;

    /**
     * The number of input channels.
     */
    private final int inputChannels;

    /**
     * The core engine.
     */
    private final EncoderEngine engine;

    /**
     * The analysis bank of each analyzed channel: the core's coded channels, or the left and right input of
     * parametric stereo.
     */
    private final EncoderQmfAnalysis[] analysis;

    /**
     * The QMF columns of each analyzed channel.
     */
    private final SbrColumns[] columns;

    /**
     * The input plane each analyzed channel reads.
     */
    private final int[] analyzedInput;

    /**
     * The columns each core coded channel is synthesized from.
     */
    private final SbrColumns[] coreColumns;

    /**
     * The core input plane each core coded channel is written to.
     */
    private final int[] corePlane;

    /**
     * The subband from which each core coded channel's QMF columns are cleared before synthesis.
     */
    private final int[] coreCrossover;

    /**
     * The 32-band synthesis bank of each core coded channel.
     */
    private final QmfSynthesis[] synthesis;

    /**
     * The spectral band replication encoder of each channel element, or {@code null} for an LFE element.
     */
    private final SbrEncoderElement[] elements;

    /**
     * The parametric stereo encoder, or {@code null}.
     */
    private final PsEncoder parametricStereo;

    /**
     * The buffered input of each input channel, one frame of {@code 2 * frameLength} samples.
     */
    private final float[][] pending;

    /**
     * The number of valid samples in {@link #pending}.
     */
    private int filled;

    /**
     * The number of QMF columns analyzed.
     */
    private long analyzed;

    /**
     * The core input of one frame, one plane per core input channel.
     */
    private final float[][] corePlanes;

    /**
     * The real parts of the columns of one frame of one core channel, 64 per column.
     */
    private final float[] frameRe;

    /**
     * The imaginary parts of the columns of one frame of one core channel.
     */
    private final float[] frameIm;

    /**
     * The estimated bitrate of the extension payloads, which the core leaves to them.
     */
    private final int extensionBitrate;

    /**
     * Creates an encoder.
     *
     * @param outputRate the input and output rate, twice a core rate the tuning table covers
     * @param frameLength the core frame length, 1024 or 960
     * @param layout the coding of the input layout at the core rate (for parametric stereo, of the stereo input)
     * @param bitrate the bitrate in bits per second
     * @param vbr whether to code the core at constant quality
     * @param parametricStereo whether to code a stereo input as a mono core with parametric stereo
     * @param kernel the core's DSP kernel
     * @throws IllegalArgumentException if the tuning table covers neither the rate nor the bitrate of an element
     */
    public SbrEncoder(int outputRate, int frameLength, EncoderLayout layout, int bitrate, boolean vbr,
                      boolean parametricStereo, AacKernel kernel) {
        this.outputRate = outputRate;
        this.frameLength = frameLength;
        this.columnsPerFrame = frameLength / 32;
        var coreRate = outputRate / 2;
        var timeSlots = frameLength / 64;
        this.inputChannels = layout.inputChannels().length;
        var coreLayout = parametricStereo ? mono(coreRate) : layout;
        var coreChannels = coreLayout.inputChannels().length;
        var elementTypes = coreLayout.elementTypes();
        var weights = 0.0;
        for (var type : elementTypes) {
            weights += weight(type);
        }
        var sbrBits = 0;
        this.elements = new SbrEncoderElement[elementTypes.length];
        if (parametricStereo) {
            this.analysis = new EncoderQmfAnalysis[]{new EncoderQmfAnalysis(), new EncoderQmfAnalysis()};
            this.columns = new SbrColumns[]{new SbrColumns(), new SbrColumns()};
            this.analyzedInput = layout.inputChannels().clone();
            var mono = new SbrColumns();
            var tuning = tuning(coreRate, 1, bitrate);
            this.parametricStereo = new PsEncoder(columns[0], columns[1], mono, timeSlots, bitrate < 21000,
                    bitrate >= 40000 ? 2 : 1);
            sbrBits = estimate(1) + 1500;
            elements[0] = new SbrEncoderElement(tuning, outputRate, timeSlots, new SbrColumns[]{mono},
                    this.parametricStereo, bitrate, bitrate - sbrBits);
            this.coreColumns = new SbrColumns[]{mono};
            this.corePlane = new int[]{0};
            this.coreCrossover = new int[]{elements[0].crossover()};
        } else {
            this.parametricStereo = null;
            this.analysis = new EncoderQmfAnalysis[coreChannels];
            this.columns = new SbrColumns[coreChannels];
            this.analyzedInput = layout.inputChannels().clone();
            this.coreColumns = columns;
            this.corePlane = layout.inputChannels().clone();
            this.coreCrossover = new int[coreChannels];
            for (var c = 0; c < coreChannels; c++) {
                analysis[c] = new EncoderQmfAnalysis();
                columns[c] = new SbrColumns();
                coreCrossover[c] = 32;
            }
            var channel = 0;
            for (var e = 0; e < elementTypes.length; e++) {
                var type = elementTypes[e];
                var width = type == ElementId.CPE ? 2 : 1;
                if (type != ElementId.LFE) {
                    var share = (int) Math.round(bitrate * weight(type) / weights);
                    var tuning = tuning(coreRate, width, share);
                    var element = new SbrColumns[width];
                    for (var i = 0; i < width; i++) {
                        element[i] = columns[channel + i];
                    }
                    var estimated = estimate(width);
                    sbrBits += estimated;
                    elements[e] = new SbrEncoderElement(tuning, outputRate, timeSlots, element, null, share,
                            share - estimated);
                    for (var i = 0; i < width; i++) {
                        coreCrossover[channel + i] = elements[e].crossover();
                    }
                }
                channel += width;
            }
        }
        var crossover = 0;
        for (var element : elements) {
            if (element != null) {
                crossover = Math.max(crossover, element.crossover());
            }
        }
        var bandwidth = Math.min(crossover * outputRate / 128, coreRate / 2);
        this.extensionBitrate = sbrBits;
        this.engine = new EncoderEngine(coreRate, frameLength, coreLayout, bitrate,
                Math.max(bitrate - sbrBits, 1000 * coreChannels), vbr, bandwidth, kernel);
        this.synthesis = new QmfSynthesis[coreChannels];
        for (var c = 0; c < coreChannels; c++) {
            synthesis[c] = new QmfSynthesis(32, SbrKernels.INSTANCE);
        }
        this.pending = new float[inputChannels][2 * frameLength];
        this.corePlanes = new float[parametricStereo ? 1 : inputChannels][frameLength];
        this.frameRe = new float[columnsPerFrame * 64];
        this.frameIm = new float[columnsPerFrame * 64];
    }

    /**
     * Returns why the tuning table does not cover a configuration, or empty when it does.
     *
     * @param outputRate the input and output rate
     * @param layout the coding of the input layout at half that rate
     * @param bitrate the bitrate in bits per second
     * @param parametricStereo whether the stereo input is coded with parametric stereo
     * @return the reason, or empty
     */
    public static Optional<String> unsupported(int outputRate, EncoderLayout layout, int bitrate,
                                               boolean parametricStereo) {
        if (outputRate % 2 != 0) {
            return Optional.of("HE-AAC needs an even sampling rate");
        }
        var coreRate = outputRate / 2;
        if (parametricStereo) {
            if (layout.inputChannels().length != 2 || layout.elementTypes().length != 1) {
                return Optional.of("HE-AAC v2 needs a stereo input");
            }
            return check(coreRate, 1, bitrate);
        }
        var weights = 0.0;
        for (var type : layout.elementTypes()) {
            weights += weight(type);
        }
        for (var type : layout.elementTypes()) {
            if (type != ElementId.LFE) {
                var width = type == ElementId.CPE ? 2 : 1;
                var reason = check(coreRate, width, (int) Math.round(bitrate * weight(type) / weights));
                if (reason.isPresent()) {
                    return reason;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the highest frequency the spectral band replication of a configuration reconstructs.
     *
     * @param outputRate the input and output rate
     * @param layout the coding of the input layout at half that rate
     * @param bitrate the bitrate in bits per second
     * @param parametricStereo whether the stereo input is coded with parametric stereo
     * @return the stop frequency in hertz
     * @throws IllegalArgumentException if {@link #unsupported} reports a reason
     */
    public static int bandwidth(int outputRate, EncoderLayout layout, int bitrate, boolean parametricStereo) {
        var reason = unsupported(outputRate, layout, bitrate, parametricStereo);
        if (reason.isPresent()) {
            throw new IllegalArgumentException(reason.get());
        }
        var coreRate = outputRate / 2;
        var highest = 0;
        if (parametricStereo) {
            highest = stopBand(tuning(coreRate, 1, bitrate), outputRate);
        } else {
            var weights = 0.0;
            for (var type : layout.elementTypes()) {
                weights += weight(type);
            }
            for (var type : layout.elementTypes()) {
                if (type != ElementId.LFE) {
                    var width = type == ElementId.CPE ? 2 : 1;
                    var share = (int) Math.round(bitrate * weight(type) / weights);
                    highest = Math.max(highest, stopBand(tuning(coreRate, width, share), outputRate));
                }
            }
        }
        return (int) ((long) highest * outputRate / 128);
    }

    /**
     * Returns the lowest frequency spectral band replication reconstructs in a configuration, where the band
     * the core codes ends.
     *
     * @param outputRate the input and output rate
     * @param layout the coding of the input layout at half that rate
     * @param bitrate the bitrate in bits per second
     * @param parametricStereo whether the stereo input is coded with parametric stereo
     * @return the lowest crossover frequency of the configuration's elements, in hertz
     * @throws IllegalArgumentException if {@link #unsupported} reports a reason
     */
    public static int crossoverFrequency(int outputRate, EncoderLayout layout, int bitrate,
                                         boolean parametricStereo) {
        var reason = unsupported(outputRate, layout, bitrate, parametricStereo);
        if (reason.isPresent()) {
            throw new IllegalArgumentException(reason.get());
        }
        var coreRate = outputRate / 2;
        var lowest = 64;
        if (parametricStereo) {
            lowest = tables(tuning(coreRate, 1, bitrate), outputRate).kx;
        } else {
            var weights = 0.0;
            for (var type : layout.elementTypes()) {
                weights += weight(type);
            }
            for (var type : layout.elementTypes()) {
                if (type != ElementId.LFE) {
                    var width = type == ElementId.CPE ? 2 : 1;
                    var share = (int) Math.round(bitrate * weight(type) / weights);
                    lowest = Math.min(lowest, tables(tuning(coreRate, width, share), outputRate).kx);
                }
            }
        }
        return (int) ((long) lowest * outputRate / 128);
    }

    /**
     * Returns the frequency band tables of a tuning.
     *
     * @param tuning the frequency range and noise resolution
     * @param outputRate the output rate
     * @return the tables
     * @throws IllegalArgumentException if the tuning describes invalid tables
     */
    private static SbrFrequencies tables(SbrTuning tuning, int outputRate) {
        var header = new SbrHeader();
        header.startFrequency = tuning.startFrequency();
        header.stopFrequency = tuning.stopFrequency();
        header.frequencyScale = tuning.frequencyScale();
        header.noiseBands = tuning.noiseBands();
        var tables = new SbrFrequencies();
        if (!tables.update(header, outputRate)) {
            throw new IllegalArgumentException("invalid SBR tuning " + tuning + " at " + outputRate + " Hz");
        }
        return tables;
    }

    /**
     * Returns the subband past the highest the decoder reconstructs for a tuning.
     *
     * @param tuning the frequency range and noise resolution
     * @param outputRate the output rate
     * @return the stop subband, {@code kx + M}
     */
    private static int stopBand(SbrTuning tuning, int outputRate) {
        var tables = tables(tuning, outputRate);
        return tables.kx + tables.m;
    }

    /**
     * Returns the range of bitrates the tuning table covers for an element, as a reason when the bitrate is
     * outside it.
     *
     * @param coreRate the core rate
     * @param channels the element's channels
     * @param bitrate the element's bitrate
     * @return the reason, or empty
     */
    private static Optional<String> check(int coreRate, int channels, int bitrate) {
        var min = SbrTuning.minimumBitrate(coreRate, channels);
        if (min == 0) {
            return Optional.of("no HE-AAC tuning for a " + coreRate + " Hz core with " + channels + " channel"
                    + (channels == 1 ? "" : "s") + " per element");
        }
        var max = SbrTuning.maximumBitrate(coreRate, channels);
        if (bitrate < min || bitrate > max) {
            return Optional.of("HE-AAC " + (channels == 1 ? "single channel" : "channel pair") + " element bitrate "
                    + bitrate + " outside " + min + " to " + max + " for a " + coreRate + " Hz core");
        }
        return Optional.empty();
    }

    /**
     * Returns the tuning of an element.
     *
     * @param coreRate the core rate
     * @param channels the element's channels
     * @param bitrate the element's bitrate
     * @return the tuning
     * @throws IllegalArgumentException if the table has none
     */
    private static SbrTuning tuning(int coreRate, int channels, int bitrate) {
        return SbrTuning.of(coreRate, channels, bitrate).orElseThrow(() -> new IllegalArgumentException(
                check(coreRate, channels, bitrate).orElse("no HE-AAC tuning")));
    }

    /**
     * Returns the share of the bitrate an element's type takes, an LFE element taking a fifth of a channel.
     *
     * @param type the element type
     * @return the weight
     */
    private static double weight(int type) {
        return type == ElementId.CPE ? 2 : type == ElementId.LFE ? 0.2 : 1;
    }

    /**
     * Returns an estimate of the bitrate of an element's spectral band replication data.
     *
     * @param channels the element's channels
     * @return the bits per second
     */
    private static int estimate(int channels) {
        return 2000 * channels;
    }

    /**
     * Returns the coding of a mono core.
     *
     * @param coreRate the core rate
     * @return the layout
     */
    private static EncoderLayout mono(int coreRate) {
        return EncoderLayout.of(com.github.auties00.smalt.audio.ChannelLayout.MONO, coreRate).orElseThrow();
    }

    /**
     * Returns the number of input samples per channel the encoder accepts before it must encode.
     *
     * @return the free capacity in samples per channel
     */
    public int capacity() {
        return 2 * frameLength - filled;
    }

    /**
     * Appends input samples, clipped to {@code [-1, 1]}, and runs the analysis once a frame is complete.
     *
     * @param planes the input planes
     * @param offset the index of the first sample
     * @param count the number of samples, at most {@link #capacity()}
     */
    public void push(float[][] planes, int offset, int count) {
        for (var channel = 0; channel < inputChannels; channel++) {
            var source = planes[channel];
            var target = pending[channel];
            for (var i = 0; i < count; i++) {
                var value = source[offset + i];
                target[filled + i] = value > 1 ? 1 : value < -1 ? -1 : value == value ? value : 0;
            }
        }
        filled += count;
        if (filled == 2 * frameLength) {
            process();
        }
    }

    /**
     * Appends silence, running the analysis once a frame is complete.
     *
     * @param count the number of samples, at most {@link #capacity()}
     */
    public void pad(int count) {
        for (var channel = 0; channel < inputChannels; channel++) {
            Arrays.fill(pending[channel], filled, filled + count, 0);
        }
        filled += count;
        if (filled == 2 * frameLength) {
            process();
        }
    }

    /**
     * Analyzes a complete frame of input and hands its core samples to the core engine.
     */
    private void process() {
        for (var col = 0; col < columnsPerFrame; col++) {
            var column = analyzed + col;
            var offset = SbrColumns.offset(column);
            for (var a = 0; a < analysis.length; a++) {
                analysis[a].analyze(pending[analyzedInput[a]], 64 * col, columns[a].re(), columns[a].im(), offset);
            }
            if (parametricStereo != null) {
                parametricStereo.downmix(column);
            }
        }
        for (var c = 0; c < coreColumns.length; c++) {
            var source = coreColumns[c];
            var limit = coreCrossover[c];
            for (var col = 0; col < columnsPerFrame; col++) {
                var offset = SbrColumns.offset(analyzed + col);
                System.arraycopy(source.re(), offset, frameRe, 64 * col, limit);
                System.arraycopy(source.im(), offset, frameIm, 64 * col, limit);
                Arrays.fill(frameRe, 64 * col + limit, 64 * col + 64, 0);
                Arrays.fill(frameIm, 64 * col + limit, 64 * col + 64, 0);
            }
            synthesis[c].synthesize(frameRe, frameIm, columnsPerFrame, corePlanes[corePlane[c]], 0);
        }
        analyzed += columnsPerFrame;
        filled = 0;
        engine.push(corePlanes, 0, frameLength);
    }

    /**
     * Returns whether a frame is ready to encode.
     *
     * @return {@code true} when {@link #encodeFrame()} may be called
     */
    public boolean frameReady() {
        return engine.frameReady();
    }

    /**
     * Returns the number of frames encoded.
     *
     * @return the frame count
     */
    public long frames() {
        return engine.frames();
    }

    /**
     * Encodes the next frame.
     *
     * @return the raw data block, with the spectral band replication data of every element
     */
    public byte[] encodeFrame() {
        var base = engine.frames() * columnsPerFrame - columnsPerFrame - ALIGNMENT;
        if (parametricStereo != null) {
            parametricStereo.analyze(base);
        }
        for (var e = 0; e < elements.length; e++) {
            if (elements[e] != null) {
                var payload = elements[e].encode(base);
                var bits = (int) payload.bitCount();
                payload.alignWithZeros();
                engine.setExtension(e, payload.array(), bits);
            }
        }
        return engine.encodeFrame();
    }

    /**
     * Changes the bitrate of the core from the next frame on; the spectral band replication and parametric stereo
     * keep their tuning, and the core leaves them the same estimated share as before.
     *
     * @param bitrate the bitrate in bits per second
     */
    public void retarget(int bitrate) {
        // TODO: retune the spectral band replication (a new sbr_header, which resets the decoder) when the new
        //  bitrate selects another tuning entry, so the frequency range follows the bitrate as it does for AAC-LC
        var crossover = 0;
        for (var element : elements) {
            if (element != null) {
                crossover = Math.max(crossover, element.crossover());
            }
        }
        var bandwidth = Math.min(crossover * outputRate / 128, outputRate / 4);
        engine.retarget(bitrate, Math.max(bitrate - extensionBitrate, 1000 * coreColumns.length), bandwidth);
    }

    /**
     * Returns the number of input samples a decoder outputs before the first input sample.
     *
     * @return the delay in input samples
     */
    public int delay() {
        return 2 * frameLength + EXTRA_DELAY;
    }
}
