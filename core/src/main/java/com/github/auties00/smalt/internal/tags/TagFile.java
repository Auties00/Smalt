package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

/**
 * Reads the tags of one file and plans the rewrite that replaces them.
 *
 * <p>An instance is created over an opened source, whose structure it parses once. Implementations
 * must raise only {@link MediaException} for corrupt input, must plan an in-place edit whenever
 * the new tags fit the space the old ones occupied, and must never change a byte of the audio or
 * video data. Instances are not thread-safe.
 */
public sealed interface TagFile permits AviTagFile, FlacTagFile, Id3StreamTagFile, MatroskaTagFile, Mp4TagFile,
        OggTagFile, RiffTagFile {
    /**
     * Returns the tags, pictures and chapters of the file.
     *
     * @return the tags
     * @throws MediaException.Malformed if the metadata is corrupt
     */
    MediaTags read();

    /**
     * Plans the output that replaces the tags of the file.
     *
     * @param tags the tags to write, replacing every tag, picture and chapter
     * @param options how to write them
     * @return the plan of the whole output
     * @throws MediaException.Unsupported if the format cannot store the tags this way
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     */
    EditPlan plan(MediaTags tags, TagWriteOptions options);
}
