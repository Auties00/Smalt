package com.github.auties00.smalt.audio.aac;

/**
 * Selects how the {@code AudioSpecificConfig} of an HE-AAC stream ({@link AacProfile#HE} and
 * {@link AacProfile#HE_V2}) signals spectral band replication and parametric stereo (ISO/IEC 14496-3 1.6.5).
 */
public enum AacSignaling {
    /**
     * Implicit signaling: the configuration describes only the AAC Low Complexity core at the core rate, and a
     * decoder finds the extensions in the bitstream. Every AAC decoder can play the core; the output rate and, for
     * HE-AAC v2, the channel count become known only from the first frames. ADTS can signal only this form.
     */
    IMPLICIT,

    /**
     * Explicit backward compatible signaling: the configuration of the core followed by the sync extensions that
     * announce spectral band replication, its output rate and parametric stereo. Decoders that do not know the
     * extensions read the core's part and ignore the rest.
     */
    BACKWARD_COMPATIBLE,

    /**
     * Explicit hierarchical signaling: object type 5, or 29 with parametric stereo, with the output rate, then the
     * core's configuration. Decoders know the output format from the configuration; ones without spectral band
     * replication cannot play the stream.
     */
    HIERARCHICAL
}
