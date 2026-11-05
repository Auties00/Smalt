package com.github.auties00.smalt.internal.cenc;

/**
 * Enumerates the protection schemes of ISO/IEC 23001-7 Common Encryption, by the four-character code of their
 * {@code schm} box.
 */
public enum CencScheme {
    /**
     * AES-CTR over the protected bytes of a sample, one key stream continuing across its subsamples ({@code cenc}).
     */
    CENC(0x63656E63),

    /**
     * AES-CBC over the whole blocks of each protected range, the chain continuing across the subsamples of a sample
     * ({@code cbc1}).
     */
    CBC1(0x63626331),

    /**
     * AES-CTR over the blocks a crypt and skip pattern selects in each protected range ({@code cens}).
     */
    CENS(0x63656E73),

    /**
     * AES-CBC over the blocks a crypt and skip pattern selects, the chain restarting from the initialization vector
     * at each subsample ({@code cbcs}).
     */
    CBCS(0x63626373);

    private final int fourCc;

    CencScheme(int fourCc) {
        this.fourCc = fourCc;
    }

    /**
     * {@return the scheme type of the {@code schm} box}
     */
    public int fourCc() {
        return fourCc;
    }

    /**
     * {@return whether the scheme chains cipher blocks (AES-CBC) rather than counting them (AES-CTR)}
     */
    public boolean isCbc() {
        return this == CBC1 || this == CBCS;
    }

    /**
     * {@return whether the scheme encrypts a pattern of the blocks of each protected range}
     */
    public boolean usesPattern() {
        return this == CENS || this == CBCS;
    }

    /**
     * Returns the scheme of a {@code schm} scheme type.
     *
     * @param fourCc the scheme type
     * @return the scheme, or {@code null} for a scheme type this enum does not list
     */
    public static CencScheme of(int fourCc) {
        for (var scheme : values()) {
            if (scheme.fourCc == fourCc) {
                return scheme;
            }
        }
        return null;
    }
}
