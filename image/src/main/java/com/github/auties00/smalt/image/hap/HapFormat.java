package com.github.auties00.smalt.image.hap;

import java.nio.charset.StandardCharsets;

/**
 * A HAP texture format, with the sample entry type and FourCC that names it in containers.
 */
public enum HapFormat {
    /**
     * HAP: RGB in DXT1 (BC1), sample entry type {@code Hap1}.
     */
    HAP("Hap1"),

    /**
     * HAP Alpha: RGBA in DXT5 (BC3), sample entry type {@code Hap5}.
     */
    HAP_ALPHA("Hap5"),

    /**
     * HAP Q: scaled YCoCg in DXT5 (BC3), sample entry type {@code HapY}.
     */
    HAP_Q("HapY"),

    /**
     * HAP Q Alpha: scaled YCoCg in DXT5 (BC3) and alpha in RGTC1 (BC4), sample entry type {@code HapM}.
     */
    HAP_Q_ALPHA("HapM"),

    /**
     * HAP Alpha Only: one channel in RGTC1 (BC4), sample entry type {@code HapA}.
     */
    HAP_ALPHA_ONLY("HapA"),

    /**
     * HAP R: RGBA in BPTC (BC7), sample entry type {@code Hap7}.
     */
    HAP_R("Hap7"),

    /**
     * HAP HDR: RGB half-precision floating point in unsigned BPTC float (BC6H), sample entry type {@code HapH}.
     */
    HAP_HDR("HapH");

    private final String type;

    HapFormat(String type) {
        this.type = type;
    }

    /**
     * {@return the sample entry type and FourCC, four ASCII characters}
     */
    public String type() {
        return type;
    }

    /**
     * {@return the sample entry type as the bytes of its four ASCII characters}
     */
    byte[] typeBytes() {
        return type.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * {@return whether the format stores alpha}
     */
    public boolean hasAlpha() {
        return this == HAP_ALPHA || this == HAP_Q_ALPHA || this == HAP_ALPHA_ONLY || this == HAP_R;
    }
}
