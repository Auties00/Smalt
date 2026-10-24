package com.github.auties00.smalt.image.jp2;

/**
 * Enumerates the codestream profiles of ISO/IEC 15444-1 annex A ({@code Rsiz}, Table A.10) that
 * {@link Jp2Encoder} writes: the digital cinema profiles of amendment 1 and the Interoperable Master
 * Format (IMF) profiles of amendment 8.
 *
 * <p>A profile restricts the image and the coding parameters; the options' presets
 * ({@link Jp2EncoderOptions#forCinema2k(int)}, {@link Jp2EncoderOptions#forCinema4k()} and
 * {@link Jp2EncoderOptions#forImf(Jp2Profile, int, int, int)}) hold settings that meet it, and the
 * encoder rejects an image or options that do not.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Jp2Profile {
    /**
     * Digital cinema 2K (profile 3, {@code Rsiz} 3): three 12-bit unsigned components of at most
     * 2048 by 1080 samples, one tile, the 9-7 wavelet, at most 5 decomposition levels, one quality
     * layer, CPRL progression, 32x32 code-blocks, 256x256 precincts (128x128 for the lowest
     * resolution), one guard bit, a tile-part per component, {@code TLM} markers, and at most
     * 1302083 bytes per frame at 24 frames per second (651041 at 48), 1041666 (520833) per
     * component.
     */
    CINEMA_2K(0x0003),

    /**
     * Digital cinema 4K (profile 4, {@code Rsiz} 4): as {@link #CINEMA_2K} for at most 4096 by 2160
     * samples at 24 frames per second, with from 1 to 6 decomposition levels, two guard bits, and
     * two progressions in the main header: CPRL over every resolution but the highest, then CPRL over
     * the highest (the 2K image first).
     */
    CINEMA_4K(0x0004),

    /**
     * IMF 2K single-tile lossy ({@code Rsiz} 0x04XX): at most three unsigned components of 8 to 16
     * bits and 2048 by 1556 samples, the 9-7 wavelet, from 1 to 5 decomposition levels.
     */
    IMF_2K(0x0400),

    /**
     * IMF 4K single-tile lossy: as {@link #IMF_2K} for at most 4096 by 3112 samples and from 1 to 6
     * decomposition levels.
     */
    IMF_4K(0x0500),

    /**
     * IMF 8K single-tile lossy: as {@link #IMF_2K} for at most 8192 by 6224 samples and from 1 to 7
     * decomposition levels.
     */
    IMF_8K(0x0600),

    /**
     * IMF 2K single- or multi-tile reversible: as {@link #IMF_2K} with the 5-3 wavelet, tiles of
     * 1024x1024 or covering the image, and at most 4 decomposition levels for tiles narrower than
     * 2048 samples (but at least 1024), 5 otherwise.
     */
    IMF_2K_REVERSIBLE(0x0700),

    /**
     * IMF 4K single- or multi-tile reversible: as {@link #IMF_2K_REVERSIBLE} for at most 4096 by 3112
     * samples, with tiles of 2048x2048 allowed too and 6 decomposition levels for tiles of at least
     * 4096 samples.
     */
    IMF_4K_REVERSIBLE(0x0800),

    /**
     * IMF 8K single- or multi-tile reversible: as {@link #IMF_4K_REVERSIBLE} for at most 8192 by 6224
     * samples, with tiles of 4096x4096 allowed too and 7 decomposition levels for tiles of at least
     * 8192 samples.
     */
    IMF_8K_REVERSIBLE(0x0900);

    /**
     * The profile's {@code Rsiz} value, without the IMF levels.
     */
    private final int code;

    /**
     * Constructs a constant.
     *
     * @param code the {@code Rsiz} value
     */
    Jp2Profile(int code) {
        this.code = code;
    }

    /**
     * {@return the {@code Rsiz} value of the profile, without the IMF main and sub levels}
     */
    int code() {
        return code;
    }

    /**
     * {@return whether the profile is a digital cinema one}
     */
    boolean isCinema() {
        return this == CINEMA_2K || this == CINEMA_4K;
    }

    /**
     * {@return whether the profile is a reversible IMF one}
     */
    boolean isReversibleImf() {
        return this == IMF_2K_REVERSIBLE || this == IMF_4K_REVERSIBLE || this == IMF_8K_REVERSIBLE;
    }
}
