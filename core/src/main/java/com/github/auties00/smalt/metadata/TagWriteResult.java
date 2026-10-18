package com.github.auties00.smalt.metadata;

/**
 * Names how {@link TagEditor#write(java.nio.file.Path, MediaTags, TagWriteOptions)} changed a file.
 */
public enum TagWriteResult {
    /**
     * The file already held exactly these tags and was not written.
     */
    UNCHANGED,

    /**
     * The new metadata fit the existing space: only the metadata bytes were rewritten, the audio
     * and video data did not move.
     */
    IN_PLACE,

    /**
     * The file was rewritten to a temporary file that replaced it.
     */
    REWRITTEN
}
