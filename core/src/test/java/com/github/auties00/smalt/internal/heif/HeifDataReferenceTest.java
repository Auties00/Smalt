package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers HEIF items stored in other files: the {@code dref} box of the {@code meta} box, items whose
 * {@code iloc} entry names one of its entries, and the resolver that supplies the files, which is asked once per
 * read and whose source is closed after it.
 */
@DisplayName("HEIF items in other files")
class HeifDataReferenceTest {
    private static final byte[] EXTERNAL = referencedFile();

    private static byte[] referencedFile() {
        var bytes = new byte[200];
        new Random(4).nextBytes(bytes);
        return bytes;
    }

    /** A resolver over in-memory files that records what it is asked and which sources are closed. */
    private static final class Recorder implements DataReferenceResolver {
        final List<DataReference> asked = new ArrayList<>();
        int opened;
        int closed;

        @Override
        public Optional<MediaSource> resolve(DataReference reference) {
            asked.add(reference);
            if (!reference.fileName().equals("external.bin")) {
                return Optional.empty();
            }
            opened++;
            var inner = MediaSource.of(EXTERNAL);
            return Optional.of(new MediaSource() {
                @Override
                public long size() {
                    return inner.size();
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    return inner.read(position, buffer, offset, length);
                }

                @Override
                public void close() {
                    closed++;
                    inner.close();
                }
            });
        }
    }

    /** A file whose item 1 has two extents in another file; the bytes it keeps in its own mdat are zeros. */
    private static byte[] file(byte[] entry, int index, HeifBuilder.Extent... extents) {
        var builder = new HeifBuilder().brands("mif1", "mif1");
        var id = builder.item("unci", new byte[16]);
        builder.primary(id).extents(id, 0, extents).dataReference(id, index).metaBox(HeifBuilder.dinf(entry));
        return builder.build();
    }

    private static byte[] expected(HeifBuilder.Extent... extents) {
        var out = new java.io.ByteArrayOutputStream();
        for (var extent : extents) {
            out.write(EXTERNAL, (int) extent.offset(), (int) extent.length());
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("reads an item from the file its url entry names, and closes it after the read")
    void external() {
        var extents = new HeifBuilder.Extent[]{new HeifBuilder.Extent(10, 20, 0), new HeifBuilder.Extent(150, 33, 0)};
        var data = file(HeifBuilder.urlEntry("media/external.bin"), 1, extents);
        var recorder = new Recorder();
        try (var source = MediaSource.of(data)) {
            var heif = HeifFile.parse(source, MediaLimits.DEFAULT, true, recorder);
            var item = heif.primaryItem();
            assertArrayEquals(expected(extents), heif.readItem(source, item, MediaLimits.DEFAULT));
            assertArrayEquals(expected(extents), heif.readItem(source, item, MediaLimits.DEFAULT));
        }
        assertEquals(List.of(new DataReference(DataReference.Kind.URL, "media/external.bin", "")), recorder.asked.subList(0, 1));
        assertEquals(2, recorder.opened);
        assertEquals(2, recorder.closed, "every resolved source is closed after its read");
    }

    @Test
    @DisplayName("an item in another file is unsupported without a resolver that supplies it")
    void unresolved() {
        var data = file(HeifBuilder.urlEntry("missing.bin"), 1, new HeifBuilder.Extent(0, 8, 0));
        try (var source = MediaSource.of(data)) {
            var plain = HeifFile.parse(source, MediaLimits.DEFAULT);
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> plain.readItem(source, plain.primaryItem(), MediaLimits.DEFAULT));
            assertEquals("heif", failure.format());
            assertTrue(failure.getMessage().contains("missing.bin"), failure.getMessage());
            var recorder = new Recorder();
            var resolving = HeifFile.parse(source, MediaLimits.DEFAULT, true, recorder);
            assertThrows(MediaException.Unsupported.class,
                    () -> resolving.readItem(source, resolving.primaryItem(), MediaLimits.DEFAULT));
            assertEquals(0, recorder.opened);
        }
    }

    @Test
    @DisplayName("a self-contained entry reads the item from the file itself")
    void selfContained() {
        var builder = new HeifBuilder().brands("mif1", "mif1");
        var payload = new byte[]{9, 8, 7, 6, 5, 4};
        var id = builder.item("unci", payload);
        builder.primary(id).dataReference(id, 1).metaBox(HeifBuilder.dinf(HeifBuilder.selfEntry()));
        var data = builder.build();
        try (var source = MediaSource.of(data)) {
            var heif = HeifFile.parse(source, MediaLimits.DEFAULT, true, new Recorder());
            assertArrayEquals(payload, heif.readItem(source, heif.primaryItem(), MediaLimits.DEFAULT));
        }
    }

    @Test
    @DisplayName("an index past the dref box and an extent past the referenced file are malformed")
    void malformed() {
        var data = file(HeifBuilder.urlEntry("external.bin"), 2, new HeifBuilder.Extent(0, 8, 0));
        try (var source = MediaSource.of(data)) {
            var heif = HeifFile.parse(source, MediaLimits.DEFAULT, true, new Recorder());
            assertThrows(MediaException.Malformed.class, () -> heif.readItem(source, heif.primaryItem(), MediaLimits.DEFAULT));
        }
        var past = file(HeifBuilder.urlEntry("external.bin"), 1, new HeifBuilder.Extent(190, 20, 0));
        var recorder = new Recorder();
        try (var source = MediaSource.of(past)) {
            var heif = HeifFile.parse(source, MediaLimits.DEFAULT, true, recorder);
            assertThrows(MediaException.Malformed.class, () -> heif.readItem(source, heif.primaryItem(), MediaLimits.DEFAULT));
        }
        assertEquals(recorder.opened, recorder.closed, "a failed read closes the source too");
    }

    @Test
    @DisplayName("a damaged dref box fails only the items stored in other files")
    void damagedDref() {
        // the dref box declares one entry whose box claims 64 bytes of the 8 that follow
        var damaged = HeifBuilder.box("dinf", HeifBuilder.fullBox("dref", 0, 0,
                new byte[]{0, 0, 0, 1, 0, 0, 0, 64, 'u', 'r', 'l', ' '}));
        var payload = new byte[]{1, 2, 3, 4, 5};
        var builder = new HeifBuilder().brands("mif1", "mif1");
        var id = builder.item("unci", payload);
        builder.primary(id).metaBox(damaged);
        var inFile = builder.build();
        try (var source = MediaSource.of(inFile)) {
            for (var strict : new boolean[]{false, true}) {
                var heif = HeifFile.parse(source, MediaLimits.DEFAULT, strict, new Recorder());
                assertArrayEquals(payload, heif.readItem(source, heif.primaryItem(), MediaLimits.DEFAULT));
            }
        }
        var external = new HeifBuilder().brands("mif1", "mif1");
        var externalId = external.item("unci", new byte[8]);
        external.primary(externalId).extents(externalId, 0, new HeifBuilder.Extent(0, 8, 0)).dataReference(externalId, 1)
                .metaBox(damaged);
        try (var source = MediaSource.of(external.build())) {
            var heif = HeifFile.parse(source, MediaLimits.DEFAULT, true, new Recorder());
            assertThrows(MediaException.Malformed.class, () -> heif.readItem(source, heif.primaryItem(), MediaLimits.DEFAULT));
        }
    }

    @Test
    @DisplayName("parses url, urn and alias entries of a dref box")
    void entries() {
        var urn = HeifBuilder.fullBox("urn ", 0, 0, "urn:smalt:x\0dir/x.bin\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var alias = new byte[150 + 4 + 12 + 4];
        alias[50] = 5;
        System.arraycopy("y.mov".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, alias, 51, 5);
        alias[150] = 0;
        alias[151] = 18;
        alias[152] = 0;
        alias[153] = 12;
        System.arraycopy("/Vol/z/y.mov".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, alias, 154, 12);
        alias[166] = (byte) 0xFF;
        alias[167] = (byte) 0xFF;
        var dinf = HeifBuilder.dinf(HeifBuilder.selfEntry(), HeifBuilder.urlEntry("a.bin"), urn,
                HeifBuilder.fullBox("alis", 0, 0, alias), HeifBuilder.fullBox("imdt", 0, 0, new byte[4]));
        var cursor = BoxCursor.of(dinf, 0).nextBox().nextBox();
        var entries = DataReferences.parse(cursor);
        assertEquals(5, entries.size());
        assertTrue(entries.get(0).selfContained());
        assertEquals(new DataReference(DataReference.Kind.URL, "a.bin", ""), entries.get(1).reference());
        assertEquals(new DataReference(DataReference.Kind.URN, "dir/x.bin", "urn:smalt:x"), entries.get(2).reference());
        assertEquals(new DataReference(DataReference.Kind.ALIAS, "/Vol/z/y.mov", "y.mov"), entries.get(3).reference());
        assertEquals(null, entries.get(4).reference());
        try (var files = new DataReferences(DataReferenceResolver.none())) {
            var failure = assertThrows(MediaException.Unsupported.class, () -> files.open(entries.get(4), "heif", "item 1"));
            assertTrue(failure.getMessage().contains("imdt"), failure.getMessage());
        }
    }
}
