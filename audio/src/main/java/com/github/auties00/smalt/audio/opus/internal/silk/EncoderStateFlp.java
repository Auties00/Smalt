/* Ports libopus 1.5.2 (silk/float/structs_FLP.h). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt.
   */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.*;

/**
 * Holds the complete state of one channel of the SILK float encoder
 * ({@code silk_encoder_state_FLP}), together with the scratch buffers its per-frame analysis and
 * quantization reuse.
 *
 * @implNote This implementation replaces the stack arrays of the reference with scratch objects, which carry no state
 * between frames.
 */
public final class EncoderStateFlp {
    /**
     * The state shared with the fixed-point code.
     */
    public final EncoderState sCmn = new EncoderState();

    /**
     * The noise-shaping analysis state.
     */
    public final ShapeStateFlp sShape = new ShapeStateFlp();

    /**
     * The buffer for pitch and noise-shaping analysis.
     */
    final float[] x_buf = new float[2 * MAX_FRAME_LENGTH + LA_SHAPE_MAX];

    /**
     * The normalized correlation from the pitch lag estimator.
     */
    float LTPCorr;

    /**
     * The analysis results and coding parameters of the current frame.
     */
    final EncoderControlFlp sEncCtrl = new EncoderControlFlp();

    /**
     * The noise-shaping quantizer scratch.
     */
    final Nsq nsq = new Nsq();

    /**
     * The delayed-decision noise-shaping quantizer scratch.
     */
    final NsqDelDec nsqDelDec = new NsqDelDec();

    /**
     * The NLSF quantizer scratch.
     */
    final NlsfEncode nlsfEncode = new NlsfEncode();

    /**
     * The side-information entropy coder scratch.
     */
    final EncodeIndices encodeIndices = new EncodeIndices();

    /**
     * The excitation entropy coder scratch.
     */
    final EncodePulses encodePulses = new EncodePulses();

    /**
     * The voice activity detector scratch.
     */
    final Vad vad = new Vad();

    /**
     * The pitch estimator scratch.
     */
    final PitchAnalysisCoreFlp pitchAnalysis = new PitchAnalysisCoreFlp();

    /**
     * The scratch of the remaining float analysis stages.
     */
    final AnalysisScratch scratch = new AnalysisScratch();

    /**
     * Creates a zeroed channel state.
     */
    public EncoderStateFlp() {
    }

    /**
     * Resets every state field to zero, as {@code memset} does in the reference.
     */
    void clear() {
        sCmn.clear();
        sShape.clear();
        Arrays.fill(x_buf, 0f);
        LTPCorr = 0;
    }
}
