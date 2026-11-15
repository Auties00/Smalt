package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Objects;

/**
 * One input of a transcode: a media file, or several played one after the other.
 *
 * <p>A concatenation presents its files as one input whose tracks are those of the first file: every file must have
 * as many tracks, of the same kinds in the same order. Each file's timeline is moved so that it starts where the
 * previous one ends, so timestamps rise continuously across the joins. The end of a file is the end of its video when
 * the other tracks end less than a frame later (as rounded container timestamps make them), and otherwise the end of
 * its last packet in any track. An encoded stream decodes each file with a decoder of its own track, which may use
 * another codec, configuration, sampling rate or channel layout, and audio is padded with silence or cut at each join
 * so that it stays in step with the video; a copied stream requires the same codec and codec configuration in every
 * file. The tags, cover art and track tags are those of the first file, and the chapters of every file are joined on
 * the concatenated timeline.
 *
 * <p>The sources are not closed by the transcode. Instances are immutable and thread-safe.
 */
public final class TranscodeInput {
    private final List<MediaSource> sources;

    private TranscodeInput(List<MediaSource> sources) {
        this.sources = sources;
    }

    /**
     * Returns an input of one file.
     *
     * @param source the file
     * @return the input
     */
    public static TranscodeInput of(MediaSource source) {
        return new TranscodeInput(List.of(Objects.requireNonNull(source, "source")));
    }

    /**
     * Returns an input that plays several files one after the other.
     *
     * @param sources the files, in playing order
     * @return the input
     * @throws NullPointerException if a source is {@code null}
     * @throws IllegalArgumentException if the list is empty
     */
    public static TranscodeInput concat(List<MediaSource> sources) {
        var copy = List.copyOf(sources);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("a concatenation needs at least one file");
        }
        return new TranscodeInput(copy);
    }

    /**
     * {@return the files, in playing order; one for an input that is not a concatenation}
     */
    public List<MediaSource> sources() {
        return sources;
    }

    /**
     * {@return whether the input plays more than one file}
     */
    public boolean isConcatenation() {
        return sources.size() > 1;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TranscodeInput that && sources.equals(that.sources);
    }

    @Override
    public int hashCode() {
        return sources.hashCode();
    }

    @Override
    public String toString() {
        return "TranscodeInput" + sources;
    }
}
