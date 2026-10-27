package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.tags.MatroskaSegmentProbe;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The resolver of {@link DataReferenceResolver#directory(Path)}, which opens the referenced files inside one
 * directory; two resolvers over the same directory are equal.
 *
 * @param root the absolute, normalized directory
 */
record DirectoryDataReferences(Path root) implements DataReferenceResolver {
    /**
     * The file name extensions of the files searched for a Matroska segment.
     */
    private static final List<String> MATROSKA_EXTENSIONS = List.of(".mkv", ".mka", ".mks", ".mk3d", ".webm");

    /**
     * The largest number of files searched for a Matroska segment.
     */
    private static final int MAX_SEGMENT_CANDIDATES = 4096;

    /**
     * Constructs a resolver over a directory.
     *
     * @param root the directory, made absolute and normalized
     */
    DirectoryDataReferences {
        root = Objects.requireNonNull(root, "directory").toAbsolutePath().normalize();
    }

    @Override
    public Optional<MediaSource> resolve(DataReference reference) {
        var candidate = switch (reference.kind()) {
            case ALIAS -> plainName(reference.fileName());
            case URL, URN -> locate(reference);
            case MATROSKA_SEGMENT, MATROSKA_NEXT_SEGMENT -> {
                var hint = plainName(reference.fileName());
                yield hint != null && matches(hint, reference) ? hint : findSegment(reference);
            }
        };
        if (candidate == null) {
            return Optional.empty();
        }
        try {
            var real = candidate.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real)) {
                return Optional.empty();
            }
            return Optional.of(MediaSource.of(real));
        } catch (IOException | SecurityException exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the path of a file name in the directory.
     *
     * @param name the file name
     * @return the path, or {@code null} when the name is empty, a directory name or a path
     */
    private Path plainName(String name) {
        return name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 ? null : resolve(name);
    }

    /**
     * Searches the directory, without its subdirectories, for the Matroska file holding a referenced segment.
     *
     * @param reference a {@link DataReference.Kind#MATROSKA_SEGMENT} or
     * {@link DataReference.Kind#MATROSKA_NEXT_SEGMENT} reference
     * @return the first matching file in name order, or {@code null} when none matches
     */
    private Path findSegment(DataReference reference) {
        var candidates = new ArrayList<Path>();
        try (var listing = Files.newDirectoryStream(root)) {
            for (var path : listing) {
                if (candidates.size() >= MAX_SEGMENT_CANDIDATES) {
                    break;
                }
                var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                for (var extension : MATROSKA_EXTENSIONS) {
                    if (name.endsWith(extension)) {
                        candidates.add(path);
                        break;
                    }
                }
            }
        } catch (IOException | SecurityException | DirectoryIteratorException exception) {
            return null;
        }
        candidates.sort(Comparator.comparing(Path::toString));
        for (var candidate : candidates) {
            if (matches(candidate, reference)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns whether a file holds the segment a Matroska reference names.
     *
     * @param path the file
     * @param reference the Matroska reference to match
     * @return {@code true} when the file is a regular file inside the directory whose first segment matches
     */
    private boolean matches(Path path, DataReference reference) {
        try {
            var real = path.toRealPath();
            if (!real.startsWith(root.toRealPath()) || !Files.isRegularFile(real)) {
                return false;
            }
            try (var source = MediaSource.of(real)) {
                var identifiers = MatroskaSegmentProbe.probe(source);
                if (identifiers == null) {
                    return false;
                }
                var wanted = reference.location().toLowerCase(Locale.ROOT);
                return reference.kind() == DataReference.Kind.MATROSKA_SEGMENT
                        ? wanted.equals(identifiers.segmentUuid())
                        : wanted.equals(identifiers.prevUuid()) && !wanted.equals(identifiers.segmentUuid());
            }
        } catch (IOException | SecurityException | MediaException exception) {
            return false;
        }
    }

    /**
     * Returns the path a URL or URN location names in the directory.
     *
     * @param reference the URL or URN reference
     * @return the path, or {@code null} when the location is empty, absolute, of another scheme or outside the
     * directory
     */
    private Path locate(DataReference reference) {
        var location = reference.location();
        if (location.isEmpty()) {
            return null;
        }
        var colon = location.indexOf(':');
        var slash = location.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash)) {
            if (!location.substring(0, colon).toLowerCase(Locale.ROOT).equals("file")) {
                return null;
            }
            try {
                var path = Path.of(new URI(location)).normalize();
                return path.startsWith(root) ? path : null;
            } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException exception) {
                return null;
            }
        }
        var path = reference.decodedPath();
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")) {
            return null;
        }
        return resolve(path);
    }

    /**
     * Resolves a relative path against the directory.
     *
     * @param relative the relative path
     * @return the path, or {@code null} when it is not a valid path or lies outside the directory
     */
    private Path resolve(String relative) {
        try {
            var relativePath = Path.of(relative);
            if (relativePath.isAbsolute() || relativePath.getRoot() != null) {
                return null;
            }
            var path = root.resolve(relativePath).normalize();
            return path.startsWith(root) ? path : null;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "DataReferenceResolver.directory(" + root + ")";
    }
}
