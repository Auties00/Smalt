package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.track.Codec;

/**
 * Recognizes, reads, writes and edits the tags of a container format on behalf of
 * {@link com.github.auties00.smalt.container.Containers Containers} and
 * {@link com.github.auties00.smalt.metadata.TagEditor TagEditor}, and through them of every reader, the prober and
 * the transcoder.
 *
 * <p>A provider handles one format: a {@link ContainerFormat} constant, or a container that enum does not name, for
 * which {@link #format()} is {@link ContainerFormat#OTHER} and {@link #name()} is the only identity. Core's own
 * containers take precedence. A provider reads, writes or edits the tags of its format only where core has no
 * implementation of that operation, so a provider of a constant core implements adds only the operations core
 * lacks (for example tag editing of {@link ContainerFormat#Y4M} files, which core reads and writes but does not
 * tag), and recognition asks the providers only about an input that none of core's magic numbers identifies.
 *
 * <p>{@link com.github.auties00.smalt.container.Containers#detect(MediaSource) Containers.detect} first checks the
 * containers core recognizes by a magic number, then asks every provider's {@link #matches(byte[], long)} with the
 * leading bytes of the input, then, when the input starts with an ID3v2 tag, asks again with the bytes after the
 * tag, and only then tries the formats core recognizes by heuristics (frame sync words, elementary video
 * structure, text signatures). The first provider that matches names the format, and a provider whose
 * {@code matches} throws is taken not to match; the order in which providers are consulted is unspecified, so
 * providers should not recognize the same content.
 *
 * <p>Providers are found once, the first time a registry needs them, with {@link java.util.ServiceLoader} through
 * the class loader that defined this interface: a named module declares one with {@code provides} in its module
 * declaration, a jar on the class path with a {@code META-INF/services} entry named after this interface. A
 * provider whose {@link #name()} or {@link #format()} breaks the rules of those methods makes every lookup throw
 * {@link java.util.ServiceConfigurationError}.
 *
 * <p>Implementations must be thread-safe, since one instance serves every caller of the JVM, and must have a
 * public no-argument constructor that does no work: every provider is instantiated when the providers are first
 * looked up, whatever container the caller uses. The demuxers, muxers and tag edits a provider returns follow the
 * contracts of {@link Demuxer}, {@link Muxer} and {@code TagEditor}: they raise only {@link MediaException} for
 * corrupt input and bound every size they read before allocating.
 */
public interface ContainerProvider {
    /**
     * The number of leading bytes {@link #probeLength()} asks for by default.
     */
    int DEFAULT_PROBE_LENGTH = 4096;

    /**
     * The largest number of leading bytes recognition reads for a provider; a larger {@link #probeLength()} reads
     * this many.
     */
    int MAX_PROBE_LENGTH = 64 * 1024;

    /**
     * Returns the short name of the container, which identifies it in failures, in
     * {@link Demuxer#formatName()}, in {@link com.github.auties00.smalt.probe.MediaInfo#containerName()} and in
     * the name-based lookups of {@code Containers}.
     *
     * <p>The name is one or more groups of lowercase ASCII letters and digits joined by single hyphens, for
     * example {@code "mxf"} or {@code "sony-wave64"}. For a provider of a named format it is
     * {@link ContainerFormat#shortName()} of {@link #format()}; for a provider of {@link ContainerFormat#OTHER} it
     * is not the short name of any constant.
     *
     * @return the name
     */
    String name();

    /**
     * Returns the format this provider implements.
     *
     * @implSpec The default implementation returns {@link ContainerFormat#OTHER}.
     *
     * @return the {@link ContainerFormat} constant of the container, or {@link ContainerFormat#OTHER} for a
     * container that enum does not name
     */
    default ContainerFormat format() {
        return ContainerFormat.OTHER;
    }

    /**
     * Returns how many leading bytes {@link #matches(byte[], long)} needs to recognize the format.
     *
     * @implSpec The default implementation returns {@value #DEFAULT_PROBE_LENGTH}.
     *
     * @return the number of bytes, at most {@value #MAX_PROBE_LENGTH} are read
     */
    default int probeLength() {
        return DEFAULT_PROBE_LENGTH;
    }

    /**
     * Returns whether the leading bytes of an input belong to this provider's format.
     *
     * <p>The method is called with the bytes at offset zero and, when the input starts with an ID3v2 tag, again
     * with the bytes that follow the tag; a provider whose demuxer cannot skip a leading tag returns {@code false}
     * for a nonzero {@code offset}. Implementations must decide from a signature or a structure that other
     * formats do not share, must not modify {@code header} and must not throw for any content.
     *
     * @implSpec The default implementation returns {@code false}, for a provider of a format that core recognizes
     * itself.
     *
     * @param header the bytes from {@code offset} on, at most {@link #probeLength()} of them and fewer when the
     * input ends first
     * @param offset the offset of {@code header} in the input: zero, or the size of the leading ID3v2 tag
     * @return {@code true} when the input is a file of {@link #format()}
     */
    default boolean matches(byte[] header, long offset) {
        return false;
    }

    /**
     * Returns whether this provider reads its format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @return {@code true} when {@link #openDemuxer(MediaSource, DemuxerOptions)} is implemented
     */
    default boolean demuxes() {
        return false;
    }

    /**
     * Creates a demuxer for an input of this provider's format.
     *
     * <p>Implementations must return a demuxer whose {@link Demuxer#format()} returns {@link #format()} and whose
     * {@link Demuxer#formatName()} returns {@link #name()}.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a provider whose
     * {@link #demuxes()} returns {@code true} overrides it.
     *
     * @param source the input, read from offset zero by the returned demuxer and not closed by it
     * @param options the limits the demuxer enforces
     * @return the demuxer, which the caller closes
     * @throws MediaException.Unsupported if the file uses a feature the demuxer lacks
     * @throws MediaException.Malformed if the container is corrupt
     * @throws MediaException.LimitExceeded if a size the container declares exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    default Demuxer openDemuxer(MediaSource source, DemuxerOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " reads no container");
    }

    /**
     * Returns whether this provider writes its format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @return {@code true} when {@link #openMuxer(MediaSink)} is implemented
     */
    default boolean muxes() {
        return false;
    }

    /**
     * Returns whether the muxer of this provider accepts tracks of a codec.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param codec the codec of a track
     * @return {@code true} when the muxer {@link #openMuxer(MediaSink)} returns accepts tracks of {@code codec}
     */
    default boolean carries(Codec codec) {
        return false;
    }

    /**
     * Creates a muxer writing this provider's format with its default options.
     *
     * <p>Implementations must return a muxer whose {@link Muxer#addTrack} throws {@link IllegalArgumentException}
     * for a track whose codec {@link #carries(Codec)} rejects.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a provider whose
     * {@link #muxes()} returns {@code true} overrides it.
     *
     * @param sink the output, written from its position and not closed by the muxer
     * @return the muxer, which the caller finishes and closes
     * @throws MediaException.Unsupported if the sink cannot hold the file
     * @throws MediaException.Io if the sink position cannot be read
     */
    default Muxer openMuxer(MediaSink sink) {
        throw new UnsupportedOperationException(getClass().getName() + " writes no container");
    }

    /**
     * Returns whether this provider reads and writes the tags of its format.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @return {@code true} when {@link #readTags(MediaSource, MediaLimits)} and
     * {@link #planTags(MediaSource, MediaTags, TagWriteOptions)} are implemented
     */
    default boolean editsTags() {
        return false;
    }

    /**
     * Reads the tags, pictures, chapters and XMP packet of a file of this provider's format.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a provider whose
     * {@link #editsTags()} returns {@code true} overrides it.
     *
     * @param source the file, read from offset zero; not closed
     * @param limits the bounds on every metadata item
     * @return the tags
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    default MediaTags readTags(MediaSource source, MediaLimits limits) {
        throw new UnsupportedOperationException(getClass().getName() + " edits no tags");
    }

    /**
     * Plans the file that replaces every tag, picture and chapter of a file of this provider's format.
     *
     * <p>The plan describes the whole output. Implementations must never change a byte of the audio or video
     * data, must keep the items {@link MediaTags} cannot represent unless {@link TagWriteOptions#preserveUnknown()}
     * is off, and must plan an {@linkplain TagRewrite#isInPlace() in-place} rewrite whenever the new metadata fits
     * the space the old one used; {@code TagEditor} decides whether to apply it in place.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a provider whose
     * {@link #editsTags()} returns {@code true} overrides it.
     *
     * @param source the original file, read from offset zero; not closed
     * @param tags the tags, pictures and chapters to store
     * @param options how to write them
     * @return the plan of the output
     * @throws MediaException.Unsupported if the format cannot store the tags this way
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     * @throws MediaException.Io if reading fails
     */
    default TagRewrite planTags(MediaSource source, MediaTags tags, TagWriteOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " edits no tags");
    }
}
