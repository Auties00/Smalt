package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Packages the packets an application writes into an HLS or DASH presentation, as they come: the writer that
 * {@link Packager#open(List, PackageOutput, PackagingOptions)} returns.
 *
 * <p>Each rendition's packets are written in decoding order, each naming its track by the identifier the
 * rendition declares; across renditions, packets are written roughly in time order, as a live source produces
 * them, and a subtitle cue before the audio and video it overlaps. Segments are written as they complete, and an
 * event or live presentation rewrites its playlists and manifest after each; {@link #finish()} ends the
 * presentation. Instances are not thread-safe.
 */
public final class PackageWriter implements AutoCloseable {
    /**
     * The presentation being written.
     */
    private final Presentation presentation;

    /**
     * The index of each rendition, by name.
     */
    private final Map<String, Integer> renditions = new HashMap<>();

    /**
     * The result, once finished.
     */
    private PackagingResult result;

    /**
     * Whether the writer was closed.
     */
    private boolean closed;

    /**
     * Constructs a writer.
     *
     * @param presentation the presentation the packets are written to
     * @param names the rendition names, in order
     */
    PackageWriter(Presentation presentation, List<String> names) {
        this.presentation = presentation;
        for (var index = 0; index < names.size(); index++) {
            renditions.put(names.get(index), index);
        }
    }

    /**
     * Writes one packet of a rendition.
     *
     * <p>A packet of a track the rendition does not package (a variant's second audio track, the audio of a
     * variant next to audio renditions) is ignored.
     *
     * @param rendition the name of the rendition, as {@link Packager#open(List, PackageOutput, PackagingOptions)}
     * named it ({@code r0}, {@code r1} and so on for unnamed ones)
     * @param packet the packet, carrying the identifier of one of the rendition's tracks
     * @throws IllegalArgumentException if no rendition has that name
     * @throws IllegalStateException if the writer is finished or closed
     * @throws MediaException.Malformed if the packet cannot be packaged, for example a timestamp going backwards
     * @throws MediaException.Io if writing a segment or a manifest fails
     */
    public void writePacket(String rendition, Packet packet) {
        Objects.requireNonNull(rendition, "rendition");
        Objects.requireNonNull(packet, "packet");
        checkOpen();
        var index = renditions.get(rendition);
        if (index == null) {
            throw new IllegalArgumentException("no rendition is named " + rendition);
        }
        presentation.write(index, packet);
    }

    /**
     * Ends the presentation: writes the last segments and the complete playlists and manifest.
     *
     * @return the description of what was written
     * @throws IllegalStateException if the writer is finished or closed
     * @throws MediaException.Malformed if what was written cannot be packaged
     * @throws MediaException.Io if writing fails
     */
    public PackagingResult finish() {
        checkOpen();
        result = presentation.finish();
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closing a writer that was not finished releases its muxers and files without writing the last segments
     * or completing the manifests. Closing twice has no effect.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        presentation.close();
    }

    /**
     * Rejects a call to a finished or closed writer.
     *
     * @throws IllegalStateException if the writer is finished or closed
     */
    private void checkOpen() {
        if (closed || result != null) {
            throw new IllegalStateException("the presentation writer is " + (closed ? "closed" : "finished"));
        }
    }
}
