package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Supplies the files that the data references of a media file name, so that a demuxer or image decoder can read
 * samples and items stored outside the file it was given.
 *
 * <p>A demuxer asks for each {@link DataReference} once, the first time a track uses it, and closes the returned
 * source when it is closed (or when its construction fails); an image decoder asks each time it reads an item
 * stored elsewhere and closes the source once it has read the item. A reference the resolver cannot supply makes
 * the samples or items that use it fail with {@link MediaException.Unsupported}, as they do without a resolver.
 * A resolver may be called from several threads at once when several readers share it.
 *
 * <p>Resolving a reference reads a file the media file chose, so a resolver for untrusted input should supply
 * only files the application means to expose: {@link #directory(Path)} supplies files of one directory and its
 * subdirectories only, and {@link #none()}, the default of the options that take a resolver, supplies nothing.
 */
@FunctionalInterface
public interface DataReferenceResolver {
    /**
     * Returns the source holding the data a reference names.
     *
     * @param reference the data reference
     * @return the source, which the reader closes, or an empty {@code Optional} when the reference cannot be
     * resolved
     * @throws MediaException.Io if opening the referenced file fails in a way the reader should report
     */
    Optional<MediaSource> resolve(DataReference reference);

    /**
     * Returns the resolver that resolves no reference.
     *
     * @return the resolver
     */
    static DataReferenceResolver none() {
        return NoDataReferences.INSTANCE;
    }

    /**
     * Returns a resolver that opens the referenced files in a directory, usually the one of the media file.
     *
     * <p>A {@link DataReference.Kind#URL URL} or {@link DataReference.Kind#URN URN} location that is a relative
     * URL (such as {@code ../media/a.mov} or {@code ./data.dat}, its {@code %XX} escapes decoded) resolves against
     * the directory, and a {@code file:} URL resolves to its path; an {@link DataReference.Kind#ALIAS alias}
     * resolves to its {@link DataReference#fileName() file name} in the directory. The reference resolves only
     * when that path is a regular file inside the directory, symbolic links followed, so a media file cannot reach
     * a file outside it; URLs of other schemes resolve to nothing. Resolvers over the same directory are equal.
     *
     * @param directory the directory holding the referenced files
     * @return the resolver
     */
    static DataReferenceResolver directory(Path directory) {
        return new DirectoryDataReferences(directory);
    }
}
