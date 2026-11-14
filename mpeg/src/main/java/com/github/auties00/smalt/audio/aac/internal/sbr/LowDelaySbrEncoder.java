package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacKernel;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ElementId;
import com.github.auties00.smalt.audio.aac.internal.EncoderEngine;
import com.github.auties00.smalt.audio.aac.internal.EncoderLayout;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.Arrays;
import java.util.Optional;

/**
 * Encodes planar float samples into AAC-ELD raw data blocks with low delay spectral band replication
 * (ISO/IEC 14496-3 subclause 4.6.19): an AAC-ELD core of 512 or 480 samples per frame followed by the
 * {@code low_delay_sbr_data} of every channel element that is not an LFE element.
 *
 * <p>In the dual-rate mode the input runs at twice the core rate: it is split into 64 complex low delay QMF subbands,
 * and the lowest 32 subbands, cleared from the crossover of their element up, are synthesized into the core's input
 * by the 32-band low delay synthesis bank, so a frame codes {@code 2 * frameLength} input samples. In the downsampled
 * mode the input runs at the core rate, the core codes it directly and a 32-band low delay analysis gives the
 * subbands; the spectral band replication then reconstructs the band from the crossover to at most half the core
 * rate, and a frame codes {@code frameLength} input samples. The frequency band tables refer to twice the core rate
 * in both modes. Each frame's spectral band replication data describes, one QMF column per time slot, the columns the
 * decoder's analysis of the frame's core output holds, and the {@code ld_sbr_header} of the
 * {@code AudioSpecificConfig} carries the header of each element. A decoder outputs input sample {@code i} at output
 * sample {@code i + delay()}.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation derives the core's input in the dual-rate mode with the low delay banks instead of a
 * separate resampler, so the subbands of the spectral band replication and of the core come from one analysis. With
 * 480-sample frames the core's delay of 360 core samples is not a whole number of QMF columns: in the downsampled
 * mode the analysis input is delayed by 8 samples to align the columns exactly, and in the dual-rate mode, where the
 * core's input comes from the same analysis, the columns are aligned to the nearest one, a quarter of a time slot
 * early.
 */
public final class LowDelaySbrEncoder {
    /**
     * The QMF columns between a column of the encoder's analysis and the column of the decoder's analysis of the
     * core output that holds the same input, in the dual-rate mode with 512-sample frames: the core's delay of 384
     * core samples (12 columns) and the 32 samples of the 32-band synthesis that makes the core's input.
     */
    private static final int ALIGNMENT_DUAL_512 = 13;

    /**
     * The alignment of the dual-rate mode with 480-sample frames: the core's 360 samples and the synthesis bank's
     * 32 make 12.25 columns, rounded to the nearest column.
     */
    private static final int ALIGNMENT_DUAL_480 = 12;

    /**
     * Whether the tool runs in the dual-rate mode.
     */
    private final boolean dualRate;

    /**
     * The core rate.
     */
    private final int coreRate;

    /**
     * The core frame length, 512 or 480.
     */
    private final int frameLength;

    /**
     * The input samples per channel of one frame: {@code 2 * frameLength} at dual rate, else {@code frameLength}.
     */
    private final int inputFrame;

    /**
     * The QMF columns, and time slots, per frame: 16 or 15.
     */
    private final int columnsPerFrame;

    /**
     * The columns between an encoder column and the decoder column holding the same input.
     */
    private final int alignment;

    /**
     * The input samples the analysis input lags behind the core's in the downsampled mode, 0 at dual rate.
     */
    private final int analysisDelay;

    /**
     * The number of input channels.
     */
    private final int inputChannels;

    /**
     * The core engine.
     */
    private final EncoderEngine engine;

    /**
     * The 64-band low delay analysis bank of each coded channel at dual rate, or {@code null}.
     */
    private final EncoderQmfAnalysis[] wideAnalysis;

    /**
     * The 32-band low delay analysis bank of each coded channel in the downsampled mode, or {@code null}.
     */
    private final QmfAnalysis[] narrowAnalysis;

    /**
     * The 32-band low delay synthesis bank of each coded channel at dual rate, or {@code null}.
     */
    private final QmfSynthesis[] synthesis;

    /**
     * The QMF columns of each coded channel.
     */
    private final SbrColumns[] columns;

    /**
     * The input plane each coded channel reads.
     */
    private final int[] inputPlane;

    /**
     * The subband from which each coded channel's columns are cleared before the synthesis of the core's input.
     */
    private final int[] crossover;

    /**
     * The spectral band replication encoder of each channel element, or {@code null} for an LFE element.
     */
    private final SbrEncoderElement[] elements;

    /**
     * The buffered input of each input channel, one frame.
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
     * The core input of one frame, one plane per input channel.
     */
    private final float[][] corePlanes;

    /**
     * The analysis input of each coded channel in the downsampled mode: the last {@link #analysisDelay} samples of
     * the previous frame followed by the frame, or {@code null}.
     */
    private final float[][] delayed;

    /**
     * The real parts of the columns of one frame of one channel, 64 per column.
     */
    private final float[] frameRe;

    /**
     * The imaginary parts of the columns of one frame of one channel.
     */
    private final float[] frameIm;

    /**
     * The subbands of one frame of the 32-band analysis, 32 per column.
     */
    private final float[] narrowRe;

    /**
     * The imaginary parts of {@link #narrowRe}.
     */
    private final float[] narrowIm;

    /**
     * The estimated bitrate of the extension payloads, which the core leaves to them.
     */
    private final int extensionBitrate;

    /**
     * The highest crossover of the elements, as a frequency in hertz.
     */
    private final int crossoverFrequency;

    /**
     * Creates an encoder.
     *
     * @param coreRate the core rate; the input rate is twice it at dual rate and equal to it in the downsampled mode
     * @param frameLength the core frame length, 512 or 480
     * @param layout the coding of the input layout at the core rate, channel configuration 1 to 7
     * @param bitrate the bitrate in bits per second
     * @param vbr whether to code the core at constant quality
     * @param dualRate whether to run the dual-rate mode instead of the downsampled mode
     * @param kernel the core's DSP kernel
     * @throws IllegalArgumentException if {@link #unsupported} reports a reason
     */
    public LowDelaySbrEncoder(int coreRate, int frameLength, EncoderLayout layout, int bitrate, boolean vbr,
                              boolean dualRate, AacKernel kernel) {
        var reason = unsupported(coreRate, layout, bitrate, dualRate);
        if (reason.isPresent()) {
            throw new IllegalArgumentException(reason.get());
        }
        this.dualRate = dualRate;
        this.coreRate = coreRate;
        this.frameLength = frameLength;
        this.inputFrame = dualRate ? 2 * frameLength : frameLength;
        this.columnsPerFrame = frameLength / 32;
        var coreDelay = 3 * frameLength / 4;
        if (dualRate) {
            this.alignment = frameLength == 512 ? ALIGNMENT_DUAL_512 : ALIGNMENT_DUAL_480;
            this.analysisDelay = 0;
        } else {
            // the core's delay less the analysis input's, in columns of 32 samples
            this.analysisDelay = coreDelay % 32;
            this.alignment = (coreDelay - analysisDelay) / 32;
        }
        this.inputChannels = layout.inputChannels().length;
        var coded = inputChannels;
        var elementTypes = layout.elementTypes();
        var weights = 0.0;
        for (var type : elementTypes) {
            weights += weight(type);
        }
        this.columns = new SbrColumns[coded];
        this.inputPlane = layout.inputChannels().clone();
        this.crossover = new int[coded];
        for (var c = 0; c < coded; c++) {
            columns[c] = new SbrColumns();
            crossover[c] = 32;
        }
        this.elements = new SbrEncoderElement[elementTypes.length];
        var sbrBits = 0;
        var channel = 0;
        var timeSlots = columnsPerFrame;
        for (var e = 0; e < elementTypes.length; e++) {
            var type = elementTypes[e];
            var width = type == ElementId.CPE ? 2 : 1;
            if (type != ElementId.LFE) {
                var share = (int) Math.round(bitrate * weight(type) / weights);
                var tuning = tuning(coreRate, width, share, dualRate);
                var element = Arrays.copyOfRange(columns, channel, channel + width);
                var estimated = estimate(width);
                sbrBits += estimated;
                elements[e] = new SbrEncoderElement(tuning, 2 * coreRate, timeSlots, element, null, share,
                        share - estimated, true);
                for (var i = 0; i < width; i++) {
                    crossover[channel + i] = elements[e].crossover();
                }
            }
            channel += width;
        }
        var highest = 0;
        for (var element : elements) {
            if (element != null) {
                highest = Math.max(highest, element.crossover());
            }
        }
        this.crossoverFrequency = Math.min(highest * coreRate / 64, coreRate / 2);
        this.extensionBitrate = sbrBits;
        this.engine = new EncoderEngine(coreRate, frameLength, layout, bitrate,
                Math.max(bitrate - sbrBits, 1000 * coded), vbr, crossoverFrequency, AudioSpecificConfig.AOT_ER_ELD,
                kernel);
        if (dualRate) {
            this.wideAnalysis = new EncoderQmfAnalysis[coded];
            this.synthesis = new QmfSynthesis[coded];
            for (var c = 0; c < coded; c++) {
                wideAnalysis[c] = new EncoderQmfAnalysis(true);
                synthesis[c] = new QmfSynthesis(32, SbrKernels.INSTANCE, true);
            }
            this.narrowAnalysis = null;
            this.delayed = null;
            this.narrowRe = null;
            this.narrowIm = null;
        } else {
            this.wideAnalysis = null;
            this.synthesis = null;
            this.narrowAnalysis = new QmfAnalysis[coded];
            this.delayed = new float[coded][analysisDelay + frameLength];
            for (var c = 0; c < coded; c++) {
                narrowAnalysis[c] = new QmfAnalysis(SbrKernels.INSTANCE, true);
            }
            this.narrowRe = new float[columnsPerFrame * 32];
            this.narrowIm = new float[columnsPerFrame * 32];
        }
        this.pending = new float[inputChannels][inputFrame];
        this.corePlanes = new float[inputChannels][frameLength];
        this.frameRe = new float[columnsPerFrame * 64];
        this.frameIm = new float[columnsPerFrame * 64];
    }

    /**
     * Returns why the tuning table does not cover a configuration, or empty when it does.
     *
     * @param coreRate the core rate
     * @param layout the coding of the input layout at the core rate
     * @param bitrate the bitrate in bits per second
     * @param dualRate whether the tool runs in the dual-rate mode
     * @return the reason, or empty
     */
    public static Optional<String> unsupported(int coreRate, EncoderLayout layout, int bitrate, boolean dualRate) {
        var weights = 0.0;
        for (var type : layout.elementTypes()) {
            weights += weight(type);
        }
        for (var type : layout.elementTypes()) {
            if (type != ElementId.LFE) {
                var width = type == ElementId.CPE ? 2 : 1;
                var share = (int) Math.round(bitrate * weight(type) / weights);
                var min = SbrTuning.minimumLowDelayBitrate(coreRate, width);
                if (min == 0) {
                    return Optional.of("no AAC-ELD low delay SBR tuning for a " + coreRate + " Hz core with " + width
                            + " channel" + (width == 1 ? "" : "s") + " per element");
                }
                var max = SbrTuning.maximumLowDelayBitrate(coreRate, width);
                if (share < min || share > max) {
                    return Optional.of("AAC-ELD low delay SBR " + (width == 1 ? "single channel" : "channel pair")
                            + " element bitrate " + share + " outside " + min + " to " + max + " for a " + coreRate
                            + " Hz core");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the highest frequency the spectral band replication of a configuration reconstructs.
     *
     * @param coreRate the core rate
     * @param layout the coding of the input layout at the core rate
     * @param bitrate the bitrate in bits per second
     * @param dualRate whether the tool runs in the dual-rate mode
     * @return the stop frequency in hertz
     * @throws IllegalArgumentException if {@link #unsupported} reports a reason
     */
    public static int bandwidth(int coreRate, EncoderLayout layout, int bitrate, boolean dualRate) {
        var reason = unsupported(coreRate, layout, bitrate, dualRate);
        if (reason.isPresent()) {
            throw new IllegalArgumentException(reason.get());
        }
        var weights = 0.0;
        for (var type : layout.elementTypes()) {
            weights += weight(type);
        }
        var highest = 0;
        for (var type : layout.elementTypes()) {
            if (type != ElementId.LFE) {
                var width = type == ElementId.CPE ? 2 : 1;
                var share = (int) Math.round(bitrate * weight(type) / weights);
                var tables = tables(tuning(coreRate, width, share, dualRate), 2 * coreRate);
                highest = Math.max(highest, tables.kx + tables.m);
            }
        }
        return (int) ((long) highest * coreRate / 64);
    }

    /**
     * Returns the lowest frequency the spectral band replication of a configuration reconstructs, where the band the
     * core codes ends.
     *
     * @param coreRate the core rate
     * @param layout the coding of the input layout at the core rate
     * @param bitrate the bitrate in bits per second
     * @param dualRate whether the tool runs in the dual-rate mode
     * @return the lowest crossover frequency of the configuration's elements, in hertz
     * @throws IllegalArgumentException if {@link #unsupported} reports a reason
     */
    public static int crossoverFrequency(int coreRate, EncoderLayout layout, int bitrate, boolean dualRate) {
        var reason = unsupported(coreRate, layout, bitrate, dualRate);
        if (reason.isPresent()) {
            throw new IllegalArgumentException(reason.get());
        }
        var weights = 0.0;
        for (var type : layout.elementTypes()) {
            weights += weight(type);
        }
        var lowest = 64;
        for (var type : layout.elementTypes()) {
            if (type != ElementId.LFE) {
                var width = type == ElementId.CPE ? 2 : 1;
                var share = (int) Math.round(bitrate * weight(type) / weights);
                lowest = Math.min(lowest, tables(tuning(coreRate, width, share, dualRate), 2 * coreRate).kx);
            }
        }
        return (int) ((long) lowest * coreRate / 64);
    }

    /**
     * Returns the frequency band tables of a tuning.
     *
     * @param tuning the frequency range and noise resolution
     * @param sbrRate the rate the tables refer to
     * @return the tables
     */
    private static SbrFrequencies tables(SbrTuning tuning, int sbrRate) {
        var header = new SbrHeader();
        header.startFrequency = tuning.startFrequency();
        header.stopFrequency = tuning.stopFrequency();
        header.frequencyScale = tuning.frequencyScale();
        header.noiseBands = tuning.noiseBands();
        var tables = new SbrFrequencies();
        if (!tables.update(header, sbrRate)) {
            throw new IllegalArgumentException("invalid SBR tuning " + tuning + " at " + sbrRate + " Hz");
        }
        return tables;
    }

    /**
     * Returns the tuning of an element.
     *
     * @param coreRate the core rate
     * @param channels the element's channels
     * @param bitrate the element's bitrate
     * @param dualRate whether the tool runs in the dual-rate mode
     * @return the tuning
     * @throws IllegalArgumentException if the table has none
     */
    private static SbrTuning tuning(int coreRate, int channels, int bitrate, boolean dualRate) {
        return SbrTuning.lowDelay(coreRate, channels, bitrate, !dualRate).orElseThrow(
                () -> new IllegalArgumentException("no AAC-ELD low delay SBR tuning for " + bitrate + " bps"));
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
     * Returns an estimate of the bitrate of an element's low delay spectral band replication data, which comes
     * with every 512 or 480 core samples and carries no header.
     *
     * @param channels the element's channels
     * @return the bits per second
     */
    private static int estimate(int channels) {
        return 3000 * channels;
    }

    /**
     * Writes the {@code ld_sbr_header} of the {@code ELDSpecificConfig}: the {@code sbr_header} of every channel
     * element that is not an LFE element, in element order.
     *
     * @param writer the destination
     */
    public void writeHeaders(MsbBitWriter writer) {
        for (var element : elements) {
            if (element != null) {
                element.writeHeader(writer);
            }
        }
    }

    /**
     * Returns the number of input samples per channel the encoder accepts before it must encode.
     *
     * @return the free capacity in samples per channel
     */
    public int capacity() {
        return inputFrame - filled;
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
        if (filled == inputFrame) {
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
        if (filled == inputFrame) {
            process();
        }
    }

    /**
     * Analyzes a complete frame of input and hands its core samples to the core engine.
     */
    private void process() {
        for (var c = 0; c < columns.length; c++) {
            var source = pending[inputPlane[c]];
            var ring = columns[c];
            if (dualRate) {
                var bank = wideAnalysis[c];
                for (var col = 0; col < columnsPerFrame; col++) {
                    bank.analyze(source, 64 * col, ring.re(), ring.im(), SbrColumns.offset(analyzed + col));
                }
                var limit = crossover[c];
                for (var col = 0; col < columnsPerFrame; col++) {
                    var offset = SbrColumns.offset(analyzed + col);
                    System.arraycopy(ring.re(), offset, frameRe, 64 * col, limit);
                    System.arraycopy(ring.im(), offset, frameIm, 64 * col, limit);
                    Arrays.fill(frameRe, 64 * col + limit, 64 * col + 64, 0);
                    Arrays.fill(frameIm, 64 * col + limit, 64 * col + 64, 0);
                }
                synthesis[c].synthesize(frameRe, frameIm, columnsPerFrame, corePlanes[inputPlane[c]], 0);
            } else {
                var input = delayed[c];
                System.arraycopy(input, frameLength, input, 0, analysisDelay);
                System.arraycopy(source, 0, input, analysisDelay, frameLength);
                narrowAnalysis[c].analyze(input, columnsPerFrame, narrowRe, narrowIm, 0);
                for (var col = 0; col < columnsPerFrame; col++) {
                    var offset = SbrColumns.offset(analyzed + col);
                    System.arraycopy(narrowRe, 32 * col, ring.re(), offset, 32);
                    System.arraycopy(narrowIm, 32 * col, ring.im(), offset, 32);
                }
                System.arraycopy(source, 0, corePlanes[inputPlane[c]], 0, frameLength);
            }
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
     * @return the raw data block, with the low delay spectral band replication data of every element after the
     * channel elements
     */
    public byte[] encodeFrame() {
        var base = engine.frames() * columnsPerFrame - alignment;
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
     * Changes the bitrate of the core from the next frame on; the spectral band replication keeps its tuning, and the
     * core leaves it the same estimated share as before.
     *
     * @param bitrate the bitrate in bits per second
     */
    public void retarget(int bitrate) {
        // TODO: retune the spectral band replication when the new bitrate selects another tuning entry; low delay
        //  SBR takes its header from the AudioSpecificConfig, so a new range needs an in-band sbr_header
        engine.retarget(bitrate, Math.max(bitrate - extensionBitrate, 1000 * columns.length), crossoverFrequency);
    }

    /**
     * Returns the number of input samples a decoder outputs before the first input sample.
     *
     * <p>At dual rate it is three halves of a core frame (the core's delay at the input rate) plus the 128 samples
     * of the low delay banks: the encoder's 64-band analysis and 32-band synthesis, and the decoder's 32-band analysis
     * and 64-band synthesis, each pair reconstructing its input 32 core samples late. In the downsampled mode it is the
     * core's three quarters of a frame plus the 32 samples of the decoder's 32-band banks.
     *
     * @return the delay in input samples
     */
    public int delay() {
        return dualRate ? 3 * frameLength / 2 + 128 : 3 * frameLength / 4 + 32;
    }
}
