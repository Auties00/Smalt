package com.github.auties00.smalt.image.heic;

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

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decodes a HEIC file whose primary item is stored in another file, which the {@code dref} box of its {@code meta}
 * box names, through the data reference resolver of {@link ImageDecoderOptions}: the picture equals the one of the
 * same item stored in the file, and without a resolver that supplies the file the item is unsupported.
 */
@DisplayName("HEIC items in other files")
class HeicDataReferenceTest {
    @TempDir
    Path work;

    @Test
    @DisplayName("an uncompressed item in another file decodes like the same item in the file")
    void external() throws IOException {
        var random = new Random(3);
        var data = new byte[24 * 10 * 3];
        random.nextBytes(data);
        var inline = HeicUncompressedTest.unif();
        inline.primary(inline.item("unci", data, plain(HeifBuilder.ispe(24, 10)),
                essential(HeicUncompressedTest.profile("rgb3"))));
        var expected = HeicDecoder.decode(MediaSource.of(inline.build()));

        // the item's samples sit after 100 bytes of other data in the referenced file
        var referenced = new byte[100 + data.length + 50];
        random.nextBytes(referenced);
        System.arraycopy(data, 0, referenced, 100, data.length);
        Files.write(work.resolve("pixels.bin"), referenced);
        var builder = HeicUncompressedTest.unif();
        var id = builder.item("unci", new byte[data.length], plain(HeifBuilder.ispe(24, 10)),
                essential(HeicUncompressedTest.profile("rgb3")));
        builder.primary(id).extents(id, 0, new HeifBuilder.Extent(100, data.length, 0)).dataReference(id, 1)
                .metaBox(HeifBuilder.dinf(HeifBuilder.urlEntry("pixels.bin")));
        var file = builder.build();

        var options = ImageDecoderOptions.builder().dataReferences(DataReferenceResolver.directory(work)).build();
        var decoded = HeicDecoder.decode(MediaSource.of(file), options);
        var want = assertInstanceOf(YuvPicture.class, expected.picture());
        var got = assertInstanceOf(YuvPicture.class, decoded.picture());
        assertEquals(want.width(), got.width());
        assertArrayEquals(want.y(), got.y(), "y");
        assertArrayEquals(want.u(), got.u(), "u");
        assertArrayEquals(want.v(), got.v(), "v");
        var throughRegistry = Images.decode(MediaSource.of(file), options);
        assertArrayEquals(want.y(), assertInstanceOf(YuvPicture.class, throughRegistry.picture()).y());

        var failure = assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.decode(MediaSource.of(file)));
        assertEquals("heif", failure.format());
        Files.write(work.resolve("pixels.bin"), Arrays.copyOf(referenced, 120));
        assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(file), options));
    }
}
