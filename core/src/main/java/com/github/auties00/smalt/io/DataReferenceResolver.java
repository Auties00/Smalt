package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Supplies the files that the data references of a media file name, so that a demuxer or image decoder can read
 * samples and items stored outside the file it was given.
 *
 * <p>A demuxer asks for each {@link DataReference} once, the first time a track uses it, and closes the returned
 * source when it is closed (or when its construction fails); an image decoder asks each time it reads an item
 * stored elsewhere and closes the source once it has read the item. The HLS and DASH demuxers, whose references are
 * the URLs of their playlists, segments and keys ({@link DataReference.Kind#URL}), ask for each file once to learn
 * its size and again whenever they read it after a few other files, closing every source they no longer hold open.
 * The Matroska demuxer asks for each linked segment once, when it is created, and closes the sources when it is
 * closed; it ignores a source whose first segment is not the one referenced.
 * A reference the resolver cannot supply makes the samples or items that use it fail with
 * {@link MediaException.Unsupported}, as they do without a resolver.
 * A resolver may be called from several threads at once when several readers share it.
 *
 * <p>Resolving a reference reads a file the media file chose, so a resolver for untrusted input should supply
 * only files the application means to expose: {@link #directory(Path)} supplies files of one directory and its
 * subdirectories only, {@link #http(URI, HttpSourceOptions)} resources of one origin only, and {@link #none()}, the
 * default of the options that take a resolver, supplies nothing.
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
     * resolves to its {@link DataReference#fileName() file name} in the directory. A
     * {@link DataReference.Kind#MATROSKA_SEGMENT Matroska segment} reference resolves to the file of that name when
     * its first segment is the one referenced, and otherwise to the first file, in name order, of the directory
     * itself (not of its subdirectories) whose name ends in {@code .mkv}, {@code .mka}, {@code .mks}, {@code .mk3d}
     * or {@code .webm} and whose first segment is the one referenced (by its {@code SegmentUUID}, or for a
     * {@link DataReference.Kind#MATROSKA_NEXT_SEGMENT} reference by its {@code PrevUUID}); finding it reads the
     * start of every such file. The reference resolves only
     * when that path is a regular file inside the directory, symbolic links followed, so a media file cannot reach
     * a file outside it; URLs of other schemes resolve to nothing. Resolvers over the same directory are equal.
     *
     * @param directory the directory holding the referenced files
     * @return the resolver
     */
    static DataReferenceResolver directory(Path directory) {
        return new DirectoryDataReferences(directory);
    }

    /**
     * Returns a resolver that fetches the referenced resources over HTTP from the origin of {@code base}, usually
     * the URI of the media file or playlist.
     *
     * <p>A {@link DataReference.Kind#URL URL} or {@link DataReference.Kind#URN URN} location resolves against
     * {@code base} (RFC 3986 section 5), without its fragment, and the resource is opened as
     * {@link MediaSource#of(URI, HttpSourceOptions)} opens it. It resolves only when the result is an
     * {@code http} or {@code https} URI of the same origin as {@code base}, that is the same scheme, host and
     * port (RFC 6454), so a media file cannot make the application fetch from another server; other references
     * resolve to nothing. The redirects the {@linkplain HttpSourceOptions#httpClient() client} follows are the
     * server's choice and are not checked, so a server that redirects to other origins should be read with a
     * client that does not follow redirects. A resource the server fails to deliver fails with
     * {@link MediaException.Io}. Resolvers with the same base and options are equal.
     *
     * @param base the absolute {@code http} or {@code https} URI relative locations resolve against
     * @param options the options of the sources
     * @return the resolver
     * @throws IllegalArgumentException if {@code base} is not an absolute {@code http} or {@code https} URI with a
     * host
     */
    static DataReferenceResolver http(URI base, HttpSourceOptions options) {
        return new HttpDataReferences(base, options, null);
    }

    /**
     * Returns a resolver that fetches the referenced resources over HTTP when a filter accepts them, such as the
     * resources of a content delivery network serving the segments of a playlist.
     *
     * <p>The resolver behaves as {@link #http(URI, HttpSourceOptions)} describes, except that a resolved
     * {@code http} or {@code https} URI is fetched when {@code filter} accepts it, whatever its origin. The filter
     * may be called from several threads at once. Resolvers with the same base, options and filter are equal.
     *
     * @param base the absolute {@code http} or {@code https} URI relative locations resolve against
     * @param options the options of the sources
     * @param filter accepts the resolved URIs that may be fetched
     * @return the resolver
     * @throws IllegalArgumentException if {@code base} is not an absolute {@code http} or {@code https} URI with a
     * host
     */
    static DataReferenceResolver http(URI base, HttpSourceOptions options, Predicate<? super URI> filter) {
        return new HttpDataReferences(base, options, Objects.requireNonNull(filter, "filter"));
    }
}
