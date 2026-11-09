package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Recognizes the LBR low bit rate coding of DTS Express (ETSI TS 102 114 clause 9) and reads its decoder
 * initialization header (Table 9-1).
 *
 * <p>Instances are not thread-safe.
 */
final class LbrDecoder {
    /**
     * The LBR sync word.
     */
    static final int SYNC = 0x0A801921;

    /**
     * The sample rates by {@code nLBRSampleRateCode} (Table 9-3), {@code 0} where reserved.
     */
    private static final int[] SAMPLE_RATES = {8000, 16000, 32000, 0, 0, 22050, 44100, 0, 0, 0, 12000, 24000, 48000,
            0, 0, 0};

    /**
     * The sample rate of the LBR header.
     */
    private int sampleRate;

    /**
     * The speaker mask of the LBR header.
     */
    private int speakerMask;

    /**
     * Reads an LBR component.
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param substream the extension substream holding the asset descriptor
     * @throws MediaException.Malformed if the component breaks the syntax
     * @throws MediaException.Unsupported always once the header is valid: LBR audio is not decoded
     */
    void decode(byte[] data, int offset, int length, ExtensionSubstream substream) {
        if (length < 5 || CoreDecoder.readInt(data, offset) != SYNC) {
            throw new MediaException.Malformed("missing DTS LBR sync word");
        }
        var type = data[offset + 4] & 0xFF;
        if (type == 2) {
            if (length < 18) {
                throw new MediaException.Malformed("truncated DTS LBR decoder initialization header");
            }
            var code = data[offset + 5] & 0xFF;
            sampleRate = code < SAMPLE_RATES.length ? SAMPLE_RATES[code] : 0;
            if (sampleRate == 0) {
                throw new MediaException.Malformed("invalid DTS LBR sample rate code " + code);
            }
            speakerMask = (data[offset + 6] & 0xFF) | (data[offset + 7] & 0xFF) << 8;
        } else if (type != 1) {
            throw new MediaException.Malformed("unknown DTS LBR header type " + type);
        }
        // TODO: decode the LBR audio. ETSI TS 102 114 V1.6.1 clause 9.6.3 gives the residual time samples only as
        // bit counts to skip (Table 9-28) without their quantized values or Huffman mappings, and no DTS Express
        // stream with a public license exists to derive or check them against.
        throw new MediaException.Unsupported("DTS", "DTS Express (LBR) audio is not supported");
    }

    /**
     * {@return the number of samples per channel of the last frame}
     */
    int outputSamples() {
        return 0;
    }

    /**
     * {@return the sample rate of the last frame}
     */
    int outputRate() {
        return sampleRate;
    }

    /**
     * {@return the speaker bit of each output channel}
     */
    int[] masks() {
        return new int[0];
    }

    /**
     * {@return the samples of an output channel}
     *
     * @param ch the channel
     */
    float[] output(int ch) {
        return new float[0];
    }

    /**
     * {@return the speaker mask of the last decoder initialization header (Table 7-10), or {@code 0}}
     */
    int speakerMask() {
        return speakerMask;
    }

    /**
     * Clears the state, as at the start of a stream.
     */
    void reset() {
        sampleRate = 0;
        speakerMask = 0;
    }
}
