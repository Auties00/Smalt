package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.edit.EditPlan;

/**
 * Reads the metadata of one image file and plans the rewrite that changes it.
 *
 * <p>An instance is created over an opened source, whose structure it parses once; it is not
 * thread-safe.
 *
 * @implSpec Implementations copy the compressed pixel data unchanged and raise only
 * {@link MediaException} for corrupt input.
 */
interface ImageFile {
    /**
     * Returns the metadata of the file.
     *
     * @return the metadata
     */
    ImageMetadata metadata();

    /**
     * Plans the output that carries the resolved metadata.
     *
     * @param target the metadata to write
     * @return the plan of the whole output
     * @throws MediaException.Unsupported   if the format cannot store the metadata or cannot be
     *                                      edited
     * @throws MediaException.LimitExceeded if an item exceeds a limit of the format
     */
    EditPlan plan(Resolved target);
}
