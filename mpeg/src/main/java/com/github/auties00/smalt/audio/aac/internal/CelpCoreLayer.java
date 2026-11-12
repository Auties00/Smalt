/* Ports the CELP core path of the scalable AAC decoder of the ISO/IEC 14496-3 reference software as distributed
   with the ISO/IEC 23003-3 reference software (the CELP branch of tfScaleableAUDecode in scal_dec_frame.c,
   mdct_core in transfo.c and the core delay buffer of dec_tf.c in src_tf), developed by Bodo Teichmann (Fraunhofer
   IIS) and edited by Bernhard Grill (Fraunhofer IIS), Takashi Koike (Sony Corporation), Markus Werner (SEED /
   Software Development Karlsruhe) and Olaf Kaehler (Fraunhofer IIS). ISO/IEC gives You a royalty-free, worldwide,
   non-exclusive, copyright license to copy, distribute, and make derivative works of this software module or
   modifications thereof for use in implementations of ISO/IEC 23003 in products that satisfy conformance criteria
   (if any). Copyright (c) ISO/IEC 2008. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.celp.internal.CelpObject;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes the CELP core layer of a scalable AAC object and turns its speech into the spectrum the AAC layers refine,
 * through the up-sampling filter tool of ISO/IEC 14496-3 subclause 4.6.15.
 *
 * <p>Each CELP frame is decoded without the post-processor (subclause 4.5.2.2.5.1), up-sampled to the AAC rate by
 * inserting zeros between its samples, each multiplied by the up-sampling factor, and queued behind the
 * {@code coreCoderDelay} samples of silence the first AAC layer announces. Each AAC frame then takes one frame length
 * of queued samples and transforms it, overlapped by half with the previous one, with the forward MDCT and the window
 * sequence and shapes of that AAC frame; the lines above the core's bandwidth are cleared. Core frames and AAC frames
 * need not align: the queue holds the samples of the core frames decoded ahead of the AAC frames that use them, as
 * the super-frames of Tables 4.88 and 4.89 imply. An AAC frame that finds too few queued samples transforms silence
 * in their place.
 *
 * <p>Instances are not thread-safe.
 */
public final class CelpCoreLayer {
    /**
     * The number of AAC frames of samples the queue may hold beyond the delay before the oldest are dropped.
     */
    private static final int QUEUED_FRAMES = 16;

    /**
     * The largest {@code coreCoderDelay}, the 14-bit field of the {@code GASpecificConfig}.
     */
    private static final int MAX_DELAY = (1 << 14) - 1;

    /**
     * The CELP decoder.
     */
    private final CelpObject object;

    /**
     * The ratio of the AAC sampling rate to the core sampling rate.
     */
    private final int factor;

    /**
     * The number of samples of an AAC frame.
     */
    private final int frameLength;

    /**
     * The forward transform of the up-sampled core signal.
     */
    private final ForwardMdct transform;

    /**
     * The decoded speech of one call of the CELP decoder.
     */
    private final float[] speech;

    /**
     * The queue of up-sampled samples, a ring of {@code queue.length} entries.
     */
    private final float[] queue;

    /**
     * The index of the oldest queued sample in {@link #queue}, and the number of queued samples.
     */
    private int head;

    private int queued;

    /**
     * The transform input: the samples of the previous AAC frame followed by those of the current one.
     */
    private final float[] input;

    /**
     * Creates the core layer of a scalable object.
     *
     * @param configs the {@code AudioSpecificConfig} of each elementary stream of the CELP object, in stream order
     * @param aacRate the sampling rate of the AAC layers, in hertz
     * @param frameLength the number of samples of an AAC frame, 960 or 1024
     * @param delay the {@code coreCoderDelay} of the first AAC layer, in samples at the AAC rate, at most 16383
     * @param limits the resource limits
     * @param kernel the DSP kernel
     * @throws MediaException.Malformed if a configuration is invalid, the AAC rate is not a multiple of the core rate
     * or the delay is out of range
     */
    public CelpCoreLayer(List<byte[]> configs, int aacRate, int frameLength, int delay, MediaLimits limits,
                         AacKernel kernel) {
        this.object = new CelpObject(configs, -1, false, limits);
        var coreRate = object.sampleRate();
        if (coreRate <= 0 || aacRate % coreRate != 0 || aacRate / coreRate > 12) {
            throw new MediaException.Malformed("scalable AAC at " + aacRate + " Hz over a CELP core at " + coreRate
                    + " Hz");
        }
        if (delay < 0 || delay > MAX_DELAY) {
            throw new MediaException.Malformed("scalable AAC core coder delay of " + delay + " samples");
        }
        this.factor = aacRate / coreRate;
        this.frameLength = frameLength;
        this.transform = new ForwardMdct(frameLength, kernel);
        this.speech = new float[object.frameSize() * object.framesPerUnit()];
        this.queue = new float[delay + (QUEUED_FRAMES + 1) * frameLength
                + object.frameSize() * object.framesPerUnit() * factor];
        this.queued = delay;
        this.input = new float[2 * frameLength];
    }

    /**
     * {@return the number of elementary streams of the CELP object, the access units of one core frame}
     */
    public int streams() {
        return object.streams();
    }

    /**
     * {@return the number of CELP layers the core decodes: the base layer and its enhancement layers}
     */
    public int layers() {
        return object.layers();
    }

    /**
     * {@return the sampling rate of the decoded core speech, in hertz}
     */
    public int sampleRate() {
        return object.sampleRate();
    }

    /**
     * {@return the ratio of the AAC sampling rate to the core sampling rate}
     */
    public int factor() {
        return factor;
    }

    /**
     * Decodes the access units of one core frame, or one EP frame with the error protection tool, and queues its
     * up-sampled speech.
     *
     * @param units the access units, in stream order, in their first {@code count} entries
     * @param sizes the number of bytes of each unit
     * @param count the number of access units
     * @throws MediaException.Malformed if the error protection tool rejects an access unit
     */
    public void decode(byte[][] units, int[] sizes, int count) {
        var frames = object.decode(units, sizes, count, speech);
        var samples = frames * object.frameSize();
        var length = queue.length;
        for (var i = 0; i < samples; i++) {
            if (queued + factor > length) {
                head = (head + factor) % length;
                queued -= factor;
            }
            var tail = (head + queued) % length;
            queue[tail] = speech[i] * factor;
            for (var k = 1; k < factor; k++) {
                queue[(tail + k) % length] = 0;
            }
            queued += factor;
        }
    }

    /**
     * Transforms the next frame length of queued samples into the core spectrum of an AAC frame.
     *
     * @param sequence the window sequence of the AAC frame
     * @param previousShape the window shape of the previous AAC frame
     * @param shape the window shape of the AAC frame
     * @param spectrum receives {@code frameLength} lines laid out as the AAC spectrum, in the decoder's domain
     */
    public void transform(int sequence, int previousShape, int shape, float[] spectrum) {
        System.arraycopy(input, frameLength, input, 0, frameLength);
        var available = Math.min(queued, frameLength);
        var length = queue.length;
        for (var i = 0; i < available; i++) {
            input[frameLength + i] = queue[(head + i) % length];
        }
        Arrays.fill(input, frameLength + available, 2 * frameLength, 0);
        head = (head + available) % length;
        queued -= available;
        transform.transform(input, 0, sequence, previousShape, shape, spectrum);
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            var windowLength = frameLength / 8;
            var lines = (int) ((float) windowLength / factor + 0.5f);
            for (var window = 0; window < 8; window++) {
                Arrays.fill(spectrum, window * windowLength + lines, (window + 1) * windowLength, 0);
            }
        } else {
            Arrays.fill(spectrum, (int) ((float) frameLength / factor + 0.5f), frameLength, 0);
        }
    }
}
