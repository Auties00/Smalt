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
 * repositions it, and closes it once written. The playlists and the manifest of an event or live presentation
 * are opened again for every update, and each update replaces the previous content.
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
     * Deletes a file the packager wrote and no longer lists: a segment that left a live window (see
     * {@link PackagingOptions#removeExpiredSegments()}).
     *
     * @implSpec The default implementation keeps the file.
     * @param name the file name
     * @throws MediaException.Io if the file cannot be deleted
     */
    default void delete(String name) {
        Objects.requireNonNull(name, "name");
    }

    /**
     * Returns an output that writes the files into a directory, creating it when needed, replacing files of the
     * same names and deleting the files {@link #delete(String)} names.
     *
     * @param directory the directory to write into
     * @return the output
     */
    static PackageOutput directory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        return new PackageOutput() {
            @Override
            public MediaSink open(String name) {
                try {
                    Files.createDirectories(directory);
                } catch (IOException exception) {
                    throw new MediaException.Io("cannot create " + directory, exception);
                }
                return MediaSink.of(directory.resolve(name));
            }

            @Override
            public void delete(String name) {
                try {
                    Files.deleteIfExists(directory.resolve(name));
                } catch (IOException exception) {
                    throw new MediaException.Io("cannot delete " + name + " in " + directory, exception);
                }
            }
        };
    }
}
