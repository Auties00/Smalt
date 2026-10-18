package com.github.auties00.smalt.container.packaging;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens the files a {@link Packager} writes: playlists, manifests and segments.
 *
 * <p>Names are plain file names without directories, such as {@code master.m3u8} or
 * {@code r0_00001.ts}; manifests refer to the segments by those names, so the files must end up
 * side by side wherever they are served from. The packager writes each file in order, never
 * repositions it, and closes it once written.
 */
@FunctionalInterface
public interface PackageOutput {
    /**
     * Opens a file for writing.
     *
     * @param name the file name
     * @return the sink, which the packager closes
     * @throws MediaException.Io if the file cannot be created
     */
    MediaSink open(String name);

    /**
     * Returns an output that writes the files into a directory, creating it when needed and
     * replacing files of the same names.
     *
     * @param directory the directory to write into
     * @return the output
     */
    static PackageOutput directory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return name -> {
            try {
                Files.createDirectories(directory);
            } catch (IOException exception) {
                throw new MediaException.Io("cannot create " + directory, exception);
            }
            return MediaSink.of(directory.resolve(name));
        };
    }
}
