package com.github.auties00.smalt.io;

import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DataReference} and the resolvers of {@link DataReferenceResolver}: which locations the directory
 * resolver opens, and that it never opens a file outside its directory.
 */
@DisplayName("data reference resolvers")
class DataReferenceResolverTest {
    @TempDir
    Path work;

    private Path media() throws IOException {
        var media = Files.createDirectories(work.resolve("media"));
        Files.write(media.resolve("a.dat"), new byte[]{1, 2, 3});
        Files.write(media.resolve("b c.dat"), new byte[]{4});
        Files.createDirectories(media.resolve("sub"));
        Files.write(media.resolve("sub").resolve("d.dat"), new byte[]{5, 6});
        Files.write(work.resolve("outside.dat"), new byte[]{9});
        return media;
    }

    private static byte[] read(Optional<MediaSource> resolved) {
        try (var source = resolved.orElseThrow()) {
            var bytes = new byte[(int) source.size()];
            source.readFully(0, bytes, 0, bytes.length);
            return bytes;
        }
    }

    private static DataReference url(String location) {
        return new DataReference(DataReference.Kind.URL, location, "");
    }

    @Test
    @DisplayName("opens relative URLs, escapes decoded, and file URLs inside the directory")
    void urls() throws IOException {
        var media = media();
        var resolver = DataReferenceResolver.directory(media);
        assertArrayEquals(new byte[]{1, 2, 3}, read(resolver.resolve(url("a.dat"))));
        assertArrayEquals(new byte[]{1, 2, 3}, read(resolver.resolve(url("./a.dat"))));
        assertArrayEquals(new byte[]{1, 2, 3}, read(resolver.resolve(url("sub/../a.dat"))));
        assertArrayEquals(new byte[]{4}, read(resolver.resolve(url("b%20c.dat"))));
        assertArrayEquals(new byte[]{5, 6}, read(resolver.resolve(url("sub/d.dat?version=2#start"))));
        assertArrayEquals(new byte[]{1, 2, 3}, read(resolver.resolve(url(media.resolve("a.dat").toUri().toString()))));
        assertArrayEquals(new byte[]{5, 6},
                read(resolver.resolve(new DataReference(DataReference.Kind.URN, "sub/d.dat", "urn:smalt:d"))));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"../outside.dat", "sub/../../outside.dat", "missing.dat", "sub", "", "/etc/passwd",
            "http://example.com/a.dat", "data:text/plain,abc", "%2e%2e/outside.dat"})
    @DisplayName("resolves nothing outside the directory, missing, or of another scheme")
    void refused(String location) throws IOException {
        var resolver = DataReferenceResolver.directory(media());
        assertEquals(Optional.empty(), resolver.resolve(url(location)));
    }

    @Test
    @DisplayName("refuses a file URL outside the directory and an empty URN")
    void refusedOther() throws IOException {
        var resolver = DataReferenceResolver.directory(media());
        assertEquals(Optional.empty(), resolver.resolve(url(work.resolve("outside.dat").toUri().toString())));
        assertEquals(Optional.empty(), resolver.resolve(new DataReference(DataReference.Kind.URN, "", "urn:x")));
    }

    @Test
    @DisplayName("opens an alias by its file name in the directory, wherever it pointed")
    void aliases() throws IOException {
        var resolver = DataReferenceResolver.directory(media());
        var alias = new DataReference(DataReference.Kind.ALIAS, "/Volumes/Disk/Movies/a.dat", "a.dat");
        assertArrayEquals(new byte[]{1, 2, 3}, read(resolver.resolve(alias)));
        var hfs = new DataReference(DataReference.Kind.ALIAS, "Disk:Movies:a.dat", "");
        assertEquals("a.dat", hfs.fileName());
        assertArrayEquals(new byte[]{1, 2, 3}, read(resolver.resolve(hfs)));
        assertEquals(Optional.empty(), resolver.resolve(new DataReference(DataReference.Kind.ALIAS, "", "..")));
        assertEquals(Optional.empty(), resolver.resolve(new DataReference(DataReference.Kind.ALIAS, "", "")));
    }

    @Test
    @DisplayName("does not follow a symbolic link out of the directory")
    void symbolicLink() throws IOException {
        var media = media();
        try {
            Files.createSymbolicLink(media.resolve("link.dat"), work.resolve("outside.dat"));
        } catch (IOException | UnsupportedOperationException exception) {
            throw Skip.now("platform:unix", "symbolic links cannot be created here: " + exception);
        }
        assertEquals(Optional.empty(), DataReferenceResolver.directory(media).resolve(url("link.dat")));
    }

    @Test
    @DisplayName("names the file of each kind of reference, and none() resolves nothing")
    void fileNames() {
        assertEquals("a b.mov", url("../media/a%20b.mov?x#y").fileName());
        assertEquals("", url("media/").fileName());
        assertEquals("x.mov", new DataReference(DataReference.Kind.URN, "dir/x.mov", "urn:x").fileName());
        assertEquals("y.mov", new DataReference(DataReference.Kind.ALIAS, "/a/b/z.mov", "y.mov").fileName());
        assertTrue(DataReferenceResolver.none().resolve(url("a.dat")).isEmpty());
    }

    @Test
    @DisplayName("resolvers over one directory are equal, so options holding them are too")
    void equality() {
        var first = DataReferenceResolver.directory(work.resolve("media"));
        var second = DataReferenceResolver.directory(work.resolve("media").resolve("sub").resolve(".."));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.toString().contains("media"), first.toString());
        assertTrue(!first.equals(DataReferenceResolver.directory(work)));
        assertEquals(com.github.auties00.smalt.container.DemuxerOptions.builder().dataReferences(first).build(),
                com.github.auties00.smalt.container.DemuxerOptions.builder().dataReferences(second).build());
        assertEquals(com.github.auties00.smalt.image.ImageDecoderOptions.builder().dataReferences(first).build(),
                com.github.auties00.smalt.image.ImageDecoderOptions.builder().dataReferences(second).build());
    }
}
