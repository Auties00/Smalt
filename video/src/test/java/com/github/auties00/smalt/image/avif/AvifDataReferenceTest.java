package com.github.auties00.smalt.image.avif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static com.github.auties00.smalt.image.avif.AvifStructureTest.still;
import static com.github.auties00.smalt.image.avif.AvifStructureTest.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decodes an AVIF file whose primary AV1 item is stored in another file, which the {@code dref} box of its
 * {@code meta} box names, through the data reference resolver of {@link ImageDecoderOptions}: the picture equals
 * the one of the same item stored in the file, and without a resolver that supplies the file the item is
 * unsupported.
 */
@DisplayName("AVIF items in other files")
class AvifDataReferenceTest {
    @TempDir
    Path work;

    @Test
    @DisplayName("an AV1 item in another file decodes like the same item in the file")
    void external() throws IOException {
        var coded = tile(0, 0, "yuv420p");
        var inline = new HeifBuilder();
        inline.primary(still(inline, coded));
        var expected = assertInstanceOf(YuvPicture.class, AvifDecoder.decode(MediaSource.of(inline.build())).picture());

        // the payload sits after 77 bytes of other data in the referenced file
        var random = new Random(8);
        var payload = coded.payload();
        var referenced = new byte[77 + payload.length + 31];
        random.nextBytes(referenced);
        System.arraycopy(payload, 0, referenced, 77, payload.length);
        Files.write(work.resolve("frame.obu"), referenced);
        var builder = new HeifBuilder();
        var id = still(builder, new AvifStructureTest.Coded(new byte[payload.length], coded.config(), coded.width(),
                coded.height()));
        builder.primary(id).extents(id, 0, new HeifBuilder.Extent(77, payload.length, 0)).dataReference(id, 1)
                .metaBox(HeifBuilder.dinf(HeifBuilder.urlEntry("frame.obu")));
        var file = builder.build();

        var options = ImageDecoderOptions.builder().dataReferences(DataReferenceResolver.directory(work)).build();
        var decoded = assertInstanceOf(YuvPicture.class, AvifDecoder.decode(MediaSource.of(file), options).picture());
        assertEquals(expected.width(), decoded.width());
        assertArrayEquals(expected.y(), decoded.y(), "y");
        assertArrayEquals(expected.u(), decoded.u(), "u");
        assertArrayEquals(expected.v(), decoded.v(), "v");
        var throughRegistry = Images.decode(MediaSource.of(file), options);
        assertArrayEquals(expected.y(), assertInstanceOf(YuvPicture.class, throughRegistry.picture()).y());

        var failure = assertThrows(MediaException.Unsupported.class, () -> AvifDecoder.decode(MediaSource.of(file)));
        assertEquals("heif", failure.format());
        Files.write(work.resolve("frame.obu"), Arrays.copyOf(referenced, 90));
        assertThrows(MediaException.Malformed.class, () -> AvifDecoder.decode(MediaSource.of(file), options));
    }
}
