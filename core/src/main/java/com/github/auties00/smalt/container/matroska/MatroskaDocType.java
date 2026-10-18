package com.github.auties00.smalt.container.matroska;

/**
 * A document type of the EBML header, the {@code DocType} element that tells a Matroska file from a WebM one.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default}
 * branch.
 */
public enum MatroskaDocType {
    /**
     * Matroska ({@code matroska}): every codec {@link MatroskaMuxer} supports.
     */
    MATROSKA,

    /**
     * WebM ({@code webm}): VP8, VP9, AV1, Opus and Vorbis only.
     */
    WEBM
}
