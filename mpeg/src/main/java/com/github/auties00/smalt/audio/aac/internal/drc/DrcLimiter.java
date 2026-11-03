/* Ports the ISO/IEC 23003-4 reference software peak limiter (peakLimiter.c). Copyright Fraunhofer IIS and ISO/IEC
   2014; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

/**
 * Limits the peaks of the output of the MPEG-D DRC decoder with a look-ahead limiter (ISO/IEC 23003-4 Annex E.4).
 *
 * <p>The limiter delays the signal by its attack time. Instances are mutable and not thread-safe.
 */
final class DrcLimiter {
    /**
     * The default attack time in milliseconds ({@code TDL_ATTACK_DEFAULT_MS}).
     */
    static final float ATTACK_DEFAULT_MS = 5.0f;

    /**
     * The default release time in milliseconds ({@code TDL_RELEASE_DEFAULT_MS}).
     */
    static final float RELEASE_DEFAULT_MS = 50.0f;

    /**
     * The default threshold, -1 dBFS ({@code LIM_DEFAULT_THRESHOLD}).
     */
    static final float DEFAULT_THRESHOLD = 0.89125094f;

    private final int attack;
    private final float attackConst;
    private final float releaseConst;
    private final float threshold;
    private final int channels;
    private final int secLen;
    private final int nMaxBufSec;
    private final float[] maxBuf;
    private final float[] maxBufSlow;
    private final float[][] delayBuf;
    private float cor = 1.0f;
    private int maxBufIdx;
    private int maxBufSlowIdx;
    private int delayBufIdx;
    private int maxBufSecIdx;
    private int maxBufSecCtr;
    private double smoothState0 = 1.0;

    /**
     * Constructs a limiter ({@code createLimiter}).
     *
     * @param attackMs the attack, which is the look-ahead, in milliseconds
     * @param releaseMs the release time in milliseconds (90% time constant)
     * @param threshold the limiting threshold
     * @param channels the number of channels
     * @param sampleRate the sampling rate
     * @throws MediaException.Malformed if the attack is shorter than one sample
     */
    DrcLimiter(float attackMs, float releaseMs, float threshold, int channels, int sampleRate) {
        attack = (int) (attackMs * sampleRate / 1000);
        if (attack < 1) {
            throw new MediaException.Malformed("MPEG-D DRC limiter attack is shorter than one sample");
        }
        secLen = (int) StrictMath.sqrt(attack + 1);
        var sections = (attack + 1) / secLen;
        if (sections * secLen < attack + 1) {
            sections++;
        }
        nMaxBufSec = sections;
        maxBuf = new float[nMaxBufSec * secLen];
        maxBufSlow = new float[nMaxBufSec];
        delayBuf = new float[channels][attack];
        attackConst = (float) StrictMath.pow(0.1, 1.0 / (attack + 1));
        releaseConst = (float) StrictMath.pow(0.1, 1.0 / (releaseMs * sampleRate / 1000 + 1));
        this.threshold = threshold;
        this.channels = channels;
    }

    /**
     * {@return the delay of the limiter in samples} ({@code getLimiterDelay})
     */
    int delay() {
        return attack;
    }

    /**
     * Limits a frame in place ({@code applyLimiter}).
     *
     * @param samples the channels
     * @param count the number of samples per channel
     */
    void apply(float[][] samples, int count) {
        for (var i = 0; i < count; i++) {
            var tmp = threshold;
            for (var j = 0; j < channels; j++) {
                tmp = Math.max(tmp, Math.abs(samples[j][i]));
            }
            maxBuf[maxBufIdx] = tmp;
            var sectionMaximum = maxBuf[maxBufSecIdx];
            for (var j = 1; j < secLen; j++) {
                if (maxBuf[maxBufSecIdx + j] > sectionMaximum) {
                    sectionMaximum = maxBuf[maxBufSecIdx + j];
                }
            }
            var maximum = sectionMaximum;
            for (var j = 0; j < nMaxBufSec; j++) {
                if (maxBufSlow[j] > maximum) {
                    maximum = maxBufSlow[j];
                }
            }
            maxBufIdx++;
            maxBufSecCtr++;
            if (maxBufSecCtr >= secLen || maxBufIdx >= attack + 1) {
                maxBufSecCtr = 0;
                maxBufSlow[maxBufSlowIdx] = sectionMaximum;
                maxBufSlowIdx++;
                if (maxBufSlowIdx >= nMaxBufSec) {
                    maxBufSlowIdx = 0;
                }
                maxBufSlow[maxBufSlowIdx] = 0.0f;
                maxBufSecIdx += secLen;
            }
            if (maxBufIdx >= attack + 1) {
                maxBufIdx = 0;
                maxBufSecIdx = 0;
            }
            var gain = maximum > threshold ? threshold / maximum : 1.0f;
            if (gain < smoothState0) {
                cor = Math.min(cor, (gain - 0.1f * (float) smoothState0) * 1.11111111f);
            } else {
                cor = gain;
            }
            if (cor < smoothState0) {
                smoothState0 = attackConst * (smoothState0 - cor) + cor;
                smoothState0 = Math.max(smoothState0, gain);
            } else {
                smoothState0 = releaseConst * (smoothState0 - cor) + cor;
            }
            gain = (float) smoothState0;
            for (var j = 0; j < channels; j++) {
                var delayed = delayBuf[j][delayBufIdx];
                delayBuf[j][delayBufIdx] = samples[j][i];
                delayed *= gain;
                if (delayed > threshold) {
                    delayed = threshold;
                }
                if (delayed < -threshold) {
                    delayed = -threshold;
                }
                samples[j][i] = delayed;
            }
            delayBufIdx++;
            if (delayBufIdx >= attack) {
                delayBufIdx = 0;
            }
        }
    }
}
