/* Ports the ISO/IEC 23003-3:2019 reference software (ct_sbrdecoder.c: usac_readSBR, usac_applySBR).
   Copyright Coding Technologies, Sony Corporation and ISO/IEC 2002-2008; ISO/IEC reference software licence
   (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Decodes the enhanced spectral band replication of one USAC element: it reads {@code UsacSbrData()} and turns
 * the element's core output into the output rate.
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrElement {
    private static final int UNKNOWN = 0;
    private static final int ORIGINAL = 1;
    private static final int PREDICTIVE = 2;

    private final UsacConfig.Element config;
    private final EsbrChannel[] channels;
    private final int dataChannels;
    private final boolean lfe;

    /**
     * The predictive vector coding state, or {@code null} when the element does not enable it.
     */
    final EsbrPvc pvc;

    /**
     * Whether the most recently processed channel used predictive vector coding.
     */
    boolean previousPvcFrame;

    private boolean pvcFrame;
    private int parsedMode = UNKNOWN;

    /**
     * Constructs the decoder of an element.
     *
     * @param context the shared state
     * @param config the element configuration
     * @param channels the number of core channels the element resamples, 1 or 2
     */
    EsbrElement(EsbrContext context, UsacConfig.Element config, int channels) {
        this.config = config;
        var stereoConfigIndex = config.type() == UsacConfig.CPE ? config.stereoConfigIndex() : 0;
        this.dataChannels = stereoConfigIndex == 1 || stereoConfigIndex == 2 ? 1 : channels;
        this.lfe = config.type() == UsacConfig.LFE;
        this.pvc = !lfe && config.pvc() ? new EsbrPvc() : null;
        this.channels = new EsbrChannel[channels];
        for (var i = 0; i < channels; i++) {
            this.channels[i] = new EsbrChannel(context, !lfe && config.harmonicSbr(), pvc != null);
        }
    }

    /**
     * {@return the channel decoders}
     */
    EsbrChannel[] channels() {
        return channels;
    }

    /**
     * Reads {@code UsacSbrData()}.
     *
     * @param reader the bit source
     * @param independent the frame's {@code usacIndependencyFlag}
     * @throws MediaException.Malformed if the data is invalid
     */
    void read(AacBitReader reader, boolean independent) {
        var first = channels[0];
        var header = first.frame.header;
        var reset = header.read(reader, config.sbrHeader(), independent, pvc != null, first.active);
        if (pvc != null) {
            pvc.updateMode(header.pvcMode);
        }
        pvcFrame = header.pvcMode != 0;
        for (var i = 1; i < dataChannels; i++) {
            channels[i].frame.header.copyFrom(header);
        }
        if (reset) {
            for (var i = 0; i < dataChannels; i++) {
                channels[i].resetBands();
                channels[i].active = true;
            }
        }
        for (var i = 0; i < dataChannels; i++) {
            channels[i].frame.independent = independent;
        }
        if (dataChannels == 1) {
            if (pvcFrame) {
                first.frame.readSinglePvc(reader, config.harmonicSbr(), pvc, parsedMode == ORIGINAL);
                parsedMode = PREDICTIVE;
            } else {
                first.frame.readSingle(reader, config.harmonicSbr(), config.interTes());
                parsedMode = ORIGINAL;
            }
        } else {
            if (pvcFrame) {
                throw new MediaException.Unsupported("usac pvc stereo",
                        "USAC predictive vector coding in a channel pair element is not supported");
            }
            EsbrFrame.readPair(first.frame, channels[1].frame, reader, config.harmonicSbr(), config.interTes());
        }
    }

    /**
     * Turns the element's core output into the output rate.
     *
     * @param inputs the core output of each channel
     * @param outputs receives the output of each channel
     * @param stereoConfigIndex the element's {@code stereoConfigIndex}
     * @param synthesize whether to synthesize the outputs; otherwise only the channels' QMF slots receive the frame
     */
    void process(float[][] inputs, float[][] outputs, int stereoConfigIndex, boolean synthesize) {
        var active = !lfe && channels[0].active;
        if (active) {
            if (pvcFrame) {
                for (var i = 0; i < dataChannels; i++) {
                    channels[i].adjuster.pvcNoiseBands = channels[i].frame.decodePvc();
                }
            } else {
                for (var i = 0; i < dataChannels; i++) {
                    channels[i].frame.decode();
                }
                if (dataChannels == 2 && channels[0].frame.coupling != EsbrFrame.COUPLING_OFF) {
                    EsbrFrame.unmap(channels[0].frame, channels[1].frame);
                }
            }
        }
        for (var i = 0; i < channels.length; i++) {
            var apply = active && i < dataChannels;
            var framePvc = apply && pvcFrame;
            channels[i].process(inputs[i], outputs[i], apply, stereoConfigIndex, this, framePvc, synthesize);
            if (i < dataChannels) {
                previousPvcFrame = framePvc;
            }
        }
    }
}
