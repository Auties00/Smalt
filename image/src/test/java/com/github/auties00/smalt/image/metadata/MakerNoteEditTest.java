package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edits the real camera files of ExifTool's sample collection ({@code exiftool-samples-<make>.tar.gz}: each model's
 * APP segments with the main image replaced) and checks that the maker note survives at its place. Maker notes
 * locate their data by offsets from the TIFF header (or from their own start, which moves with them), so an edit
 * that rewrites the EXIF block must keep the {@code MakerNote} value (tag {@code 0x927C} of the Exif IFD) at the same
 * offset from the TIFF header, byte for byte. The edit is {@link ImageMetadataEditor#stripLocation(byte[])}, which
 * removes the GPS IFD and so rewrites the block of every file that has one.
 */
@DisplayName("maker notes through EXIF edits")
class MakerNoteEditTest {
    /** The offset of a maker note from the TIFF header and its bytes. */
    record MakerNote(long offset, byte[] data) {
    }

    /** {@return the EXIF block (a TIFF structure) of a JPEG's APP1 Exif segment, if any} */
    static Optional<byte[]> exif(byte[] jpeg) {
        var at = 2;
        while (at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
            var marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) {
                break;
            }
            var length = (jpeg[at + 2] & 0xFF) << 8 | jpeg[at + 3] & 0xFF;
            if (marker == 0xE1 && length >= 8 && jpeg[at + 4] == 'E' && jpeg[at + 5] == 'x' && jpeg[at + 6] == 'i'
                    && jpeg[at + 7] == 'f' && jpeg[at + 8] == 0 && jpeg[at + 9] == 0) {
                return Optional.of(Arrays.copyOfRange(jpeg, at + 10, Math.min(jpeg.length, at + 2 + length)));
            }
            at += 2 + length;
        }
        return Optional.empty();
    }

    /** {@return the maker note of an EXIF block: tag 0x927C of the IFD that tag 0x8769 of IFD0 points to} */
    static Optional<MakerNote> makerNote(byte[] tiff) {
        if (tiff.length < 8) {
            return Optional.empty();
        }
        var buffer = ByteBuffer.wrap(tiff).order(tiff[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        var exifIfd = entry(buffer, buffer.getInt(4) & 0xFFFFFFFFL, 0x8769);
        if (exifIfd == null) {
            return Optional.empty();
        }
        var exifOffset = buffer.getInt((int) (exifIfd + 8)) & 0xFFFFFFFFL;
        var note = entry(buffer, exifOffset, 0x927C);
        if (note == null) {
            return Optional.empty();
        }
        var count = buffer.getInt((int) (note + 4)) & 0xFFFFFFFFL;
        var offset = count <= 4 ? note + 8 : buffer.getInt((int) (note + 8)) & 0xFFFFFFFFL;
        if (offset + count > tiff.length) {
            return Optional.empty();
        }
        return Optional.of(new MakerNote(offset, Arrays.copyOfRange(tiff, (int) offset, (int) (offset + count))));
    }

    /** {@return the position of an IFD's entry of a tag, or null} */
    private static Long entry(ByteBuffer buffer, long ifd, int tag) {
        if (ifd < 8 || ifd + 2 > buffer.limit()) {
            return null;
        }
        var count = buffer.getShort((int) ifd) & 0xFFFF;
        for (var index = 0; index < count; index++) {
            var at = ifd + 2 + 12L * index;
            if (at + 12 > buffer.limit()) {
                return null;
            }
            if ((buffer.getShort((int) at) & 0xFFFF) == tag) {
                return at;
            }
        }
        return null;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Apple", "Canon", "Casio", "DJI", "FLIR", "FujiFilm", "GE", "GoPro", "Google", "HP",
            "Hasselblad", "Huawei", "JVC", "Kodak", "Kyocera", "Leica", "Microsoft", "Minolta", "Motorola", "Nikon",
            "Nintendo", "Olympus", "Panasonic", "Pentax", "Reconyx", "Ricoh", "Samsung", "Sanyo", "Sigma", "Sony",
            "Xiaomi"})
    @DisplayName("stripLocation keeps every model's maker note at its offset, byte for byte")
    void stripLocation(String make) {
        var files = RasterTesting.untarGz(Conformance.vector("exiftool-samples-" + make + ".tar.gz"));
        var notes = 0;
        for (var file : files.entrySet()) {
            if (!file.getKey().toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) {
                continue;
            }
            var original = file.getValue();
            var note = exif(original).flatMap(MakerNoteEditTest::makerNote);
            if (note.isEmpty()) {
                continue;
            }
            notes++;
            var edited = ImageMetadataEditor.stripLocation(original);
            var after = exif(edited).flatMap(MakerNoteEditTest::makerNote).orElse(null);
            assertNotNull(after, file.getKey() + ": the edited file keeps its maker note");
            assertEquals(note.get().offset(), after.offset(), file.getKey() + ": maker note offset");
            assertArrayEquals(note.get().data(), after.data(), file.getKey() + ": maker note bytes");
            assertNotNull(ImageMetadata.read(MediaSource.of(edited)), file.getKey());
        }
        assertTrue(notes > 0, make + " has files with a maker note");
    }
}
