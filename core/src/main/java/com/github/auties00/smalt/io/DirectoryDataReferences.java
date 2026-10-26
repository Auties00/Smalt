package com.github.auties00.smalt.io;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
            case ALIAS -> {
                var name = reference.fileName();
                yield name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                        || name.indexOf('\\') >= 0 ? null : resolve(name);
            }
            case URL, URN -> locate(reference);
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
