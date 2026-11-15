package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Enumerates the decoded picture hash SEI messages (ITU-T H.265 D.3.20) an {@link HevcEncoder} can attach to
 * every picture, so that a decoder can check its reconstruction against the encoder's.
 */
public enum HevcPictureHash {
    /**
     * No decoded picture hash.
     */
    NONE,

    /**
     * The MD5 digest of every color component, 16 bytes each ({@code hash_type} 0).
     */
    MD5,

    /**
     * The CRC of every color component, 2 bytes each ({@code hash_type} 1).
     */
    CRC,

    /**
     * The checksum of every color component, 4 bytes each ({@code hash_type} 2).
     */
    CHECKSUM
}
