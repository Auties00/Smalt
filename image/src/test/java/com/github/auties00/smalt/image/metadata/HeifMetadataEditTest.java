package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.validate.Findings;
import com.github.auties00.smalt.testkit.validate.IsoBmffValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.box;
import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.fullBox;
import static com.github.auties00.smalt.testkit.HeifBuilder.ispe;
import static com.github.auties00.smalt.testkit.HeifBuilder.nclx;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the ICC color property of the primary item of HEIF files and the metadata of HEIF image
 * sequences. Synthetic files built with {@link HeifBuilder} check the property bookkeeping (in-place
 * replacement, sharing, renumbering, 15-bit indices) and the offsets of hand-built track boxes;
 * ffmpeg's AVIF files check, with ffmpeg decoding every frame and exiftool reading the profile, that
 * real files stay intact.
 */
@DisplayName("HEIF ICC profiles and image sequences")
class HeifMetadataEditTest {
    static final byte[] OLD_ICC = "an old ICC profile, not parsed by the editor".getBytes(StandardCharsets.US_ASCII);

    static byte[] payload(int length, long seed) {
        var data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    static HeifFile parse(byte[] file) {
        return HeifFile.parse(MediaSource.of(file), MediaLimits.DEFAULT);
    }

    static List<Class<?>> kinds(HeifItem item) {
        return item.properties().stream().<Class<?>>map(Object::getClass).toList();
    }

    static ImageMetadataEdit icc(ColorProfile profile) {
        return ImageMetadataEdit.keepAll().toBuilder().colorProfile(profile).build();
    }

    static ImageMetadataEdit noIcc() {
        return ImageMetadataEdit.keepAll().toBuilder().removeIccProfile().build();
    }

    /** Returns the children of a box found by path, for example meta, iprp, ipco. */
    static List<byte[]> children(byte[] file, String... path) {
        var start = 0;
        var end = file.length;
        for (var type : path) {
            var found = false;
            var at = start;
            while (at + 8 <= end) {
                var size = ByteBuffer.wrap(file, at, 4).getInt();
                var boxType = new String(file, at + 4, 4, StandardCharsets.ISO_8859_1);
                if (boxType.equals(type)) {
                    start = at + 8 + (type.equals("meta") ? 4 : 0);
                    end = at + size;
                    found = true;
                    break;
                }
                at += size;
            }
            assertTrue(found, "no " + type + " box");
        }
        var result = new ArrayList<byte[]>();
        for (var at = start; at + 8 <= end; ) {
            var size = ByteBuffer.wrap(file, at, 4).getInt();
            result.add(Arrays.copyOfRange(file, at, at + size));
            at += size;
        }
        return result;
    }

    static int properties(byte[] file) {
        return children(file, "meta", "iprp", "ipco").size();
    }

    static byte[] primaryCodedData(byte[] file) {
        var heif = parse(file);
        return heif.readItem(MediaSource.of(file), heif.primaryItem(), MediaLimits.DEFAULT);
    }

    @Nested
    @DisplayName("ICC profiles")
    class Icc {
        static byte[] still(boolean withIcc) {
            var builder = new HeifBuilder();
            var properties = new ArrayList<HeifBuilder.Property>(List.of(plain(ispe(64, 48)), plain(nclx(1, 13, 6, true))));
            if (withIcc) {
                properties.add(plain(HeifBuilder.icc(OLD_ICC)));
            }
            properties.add(essential(HeifBuilder.irot(1)));
            var id = builder.item("av01", payload(300, 1), properties.toArray(HeifBuilder.Property[]::new));
            return builder.primary(id).build();
        }

        @Test
        @DisplayName("replaces the primary item's profile in place when no other item uses it")
        void replace() {
            var original = still(true);
            var edited = ImageMetadataEditor.apply(original, icc(ColorProfile.displayP3()));
            var primary = parse(edited).primaryItem();
            assertEquals(List.of(HeifProperty.SpatialExtent.class, HeifProperty.ColorNclx.class, HeifProperty.ColorIcc.class,
                    HeifProperty.Rotation.class), kinds(primary));
            assertArrayEquals(ColorProfile.displayP3().toByteArray(), primary.property(HeifProperty.ColorIcc.class).profile());
            assertEquals(new HeifProperty.ColorNclx(1, 13, 6, true), primary.property(HeifProperty.ColorNclx.class));
            assertEquals(properties(original), properties(edited));
            assertArrayEquals(ColorProfile.displayP3().toByteArray(),
                    ImageMetadata.read(MediaSource.of(edited)).iccProfile().orElseThrow());
            assertArrayEquals(primaryCodedData(original), primaryCodedData(edited));
        }

        @Test
        @DisplayName("removes the profile and its property, keeping the other properties and their order")
        void remove() {
            var original = still(true);
            var edited = ImageMetadataEditor.apply(original, noIcc());
            var primary = parse(edited).primaryItem();
            assertEquals(List.of(HeifProperty.SpatialExtent.class, HeifProperty.ColorNclx.class, HeifProperty.Rotation.class),
                    kinds(primary));
            assertEquals(properties(original) - 1, properties(edited));
            assertTrue(ImageMetadata.read(MediaSource.of(edited)).iccProfile().isEmpty());
            assertEquals(-1, MetadataSupportBytes.indexOf(edited, OLD_ICC));
            assertArrayEquals(primaryCodedData(original), primaryCodedData(edited));
            var bare = still(false);
            assertArrayEquals(bare, ImageMetadataEditor.apply(bare, noIcc()));
        }

        @Test
        @DisplayName("adds a profile before the transformative properties of an item that had none")
        void add() {
            var edited = ImageMetadataEditor.apply(still(false), icc(ColorProfile.srgb()));
            assertEquals(List.of(HeifProperty.SpatialExtent.class, HeifProperty.ColorNclx.class, HeifProperty.ColorIcc.class,
                    HeifProperty.Rotation.class), kinds(parse(edited).primaryItem()));
            assertArrayEquals(ColorProfile.srgb().toByteArray(),
                    ImageMetadata.read(MediaSource.of(edited)).iccProfile().orElseThrow());
        }

        /** Two items whose ICC associations point at one shared property of the first. */
        static byte[] shared() {
            var builder = new HeifBuilder();
            var first = builder.item("av01", payload(200, 2), plain(ispe(64, 48)), plain(HeifBuilder.icc(OLD_ICC)));
            builder.item("av01", payload(100, 3), plain(ispe(32, 24)), plain(HeifBuilder.icc(OLD_ICC)));
            var file = builder.primary(first).build();
            // property 4 is the second item's own copy; point its association at property 2
            var ipma = indexOf(file, "ipma".getBytes(StandardCharsets.US_ASCII));
            var association = ipma + 4 + 4 + 4 + (2 + 1 + 2) + 2 + 1 + 1;
            assertEquals(4, file[association]);
            file[association] = 2;
            return file;
        }

        @Test
        @DisplayName("adds a new property when another item shares the profile, which keeps the old one")
        void sharedReplace() {
            var original = shared();
            var edited = ImageMetadataEditor.apply(original, icc(ColorProfile.displayP3()));
            var heif = parse(edited);
            assertArrayEquals(ColorProfile.displayP3().toByteArray(), heif.item(1).property(HeifProperty.ColorIcc.class).profile());
            assertArrayEquals(OLD_ICC, heif.item(2).property(HeifProperty.ColorIcc.class).profile());
            assertEquals(properties(original) + 1, properties(edited));
            var removed = parse(ImageMetadataEditor.apply(original, noIcc()));
            assertEquals(null, removed.item(1).property(HeifProperty.ColorIcc.class));
            assertArrayEquals(OLD_ICC, removed.item(2).property(HeifProperty.ColorIcc.class).profile());
        }

        @Test
        @DisplayName("renumbers the associations of other items after dropping a property")
        void renumber() {
            var builder = new HeifBuilder();
            var primary = builder.item("av01", payload(200, 4), plain(ispe(64, 48)), plain(HeifBuilder.icc(OLD_ICC)),
                    essential(HeifBuilder.irot(2)));
            builder.item("av01", payload(100, 5), plain(ispe(32, 24)), plain(nclx(9, 16, 9, false)),
                    essential(HeifBuilder.imir(1)));
            var original = builder.primary(primary).build();
            var edited = parse(ImageMetadataEditor.apply(original, noIcc()));
            assertEquals(List.of(HeifProperty.SpatialExtent.class, HeifProperty.Rotation.class), kinds(edited.item(1)));
            assertEquals(new HeifProperty.Rotation(2), edited.item(1).property(HeifProperty.Rotation.class));
            var other = edited.item(2);
            assertEquals(new HeifProperty.SpatialExtent(32, 24), other.property(HeifProperty.SpatialExtent.class));
            assertEquals(new HeifProperty.ColorNclx(9, 16, 9, false), other.property(HeifProperty.ColorNclx.class));
            assertEquals(new HeifProperty.Mirror(1), other.property(HeifProperty.Mirror.class));
        }

        @Test
        @DisplayName("switches the associations to 15-bit indices when the new property needs one")
        void wideIndices() {
            var builder = new HeifBuilder();
            var primary = builder.item("av01", payload(200, 6), plain(ispe(64, 48)));
            // 1 + 126 properties use every 7-bit index; the new profile needs index 128
            var many = new HeifBuilder.Property[126];
            for (var i = 0; i < many.length; i++) {
                many[i] = plain(box("zzzz", new byte[]{(byte) i}));
            }
            builder.item("av01", payload(50, 7), many);
            var original = builder.primary(primary).build();
            var edited = ImageMetadataEditor.apply(original, icc(ColorProfile.displayP3()));
            var ipma = children(edited, "meta", "iprp").get(1);
            assertEquals(1, ipma[11] & 1, "ipma flags");
            var heif = parse(edited);
            assertArrayEquals(ColorProfile.displayP3().toByteArray(), heif.item(1).property(HeifProperty.ColorIcc.class).profile());
            assertEquals(126, heif.item(2).properties().size());
            assertEquals(128, properties(edited));
        }

        @Test
        @DisplayName("edits the profile together with the EXIF and XMP items")
        void together() {
            var edited = ImageMetadataEditor.apply(still(true), ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.adobeRgb())
                    .exif(ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Ada")))
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Both")).build());
            var read = ImageMetadata.read(MediaSource.of(edited));
            assertArrayEquals(ColorProfile.adobeRgb().toByteArray(), read.iccProfile().orElseThrow());
            assertEquals(new ExifValue.Ascii("Ada"), read.exif().orElseThrow().ifd0().get(0x013B));
            assertEquals("Both", read.xmp().orElseThrow().text(XmpPacket.DC, "title").orElseThrow());
        }
    }

    static int indexOf(byte[] data, byte[] needle) {
        return MetadataSupportBytes.indexOf(data, needle);
    }

    /** Byte search shared by the nested classes. */
    static final class MetadataSupportBytes {
        static int indexOf(byte[] data, byte[] needle) {
            outer:
            for (var i = 0; i + needle.length <= data.length; i++) {
                for (var j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    @Nested
    @DisplayName("image sequences")
    class Sequences {
        static final long RELATIVE_SAIO = 0x7FFF_FF00L;

        static byte[] u32s(long... values) {
            var buffer = ByteBuffer.allocate(4 * values.length);
            for (var value : values) {
                buffer.putInt((int) value);
            }
            return buffer.array();
        }

        static byte[] u64s(long... values) {
            var buffer = ByteBuffer.allocate(8 * values.length);
            for (var value : values) {
                buffer.putLong(value);
            }
            return buffer.array();
        }

        static byte[] concat(byte[]... parts) {
            var out = new ByteArrayOutputStream();
            for (var part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }

        /** A still image followed by track boxes whose offsets point into its mdat, and a moof, mdat and mfra. */
        static byte[] sequence(long[] offsets) {
            var still = Icc.still(true);
            var fragmentData = payload(64, 11);
            var stbl = box("stbl", concat(
                    fullBox("stco", 0, 0, concat(u32s(2), u32s(offsets[0], offsets[1]))),
                    fullBox("co64", 0, 0, concat(u32s(1), u64s(offsets[2]))),
                    fullBox("saio", 0, 0, concat(u32s(1), u32s(offsets[3])))));
            var moov = box("moov", box("trak", box("mdia", box("minf", stbl))));
            var moofStart = (long) still.length + moov.length;
            // the saio of a traf holds an offset relative to the fragment's base, which an edit leaves alone
            var moof = box("moof", box("traf", concat(fullBox("tfhd", 0, 1, concat(u32s(1), u64s(moofStart + 200))),
                    fullBox("saio", 0, 0, concat(u32s(1), u32s(RELATIVE_SAIO))))));
            var mdat = box("mdat", fragmentData);
            var pad = new byte[200 - moof.length - 8];
            var free = box("free", pad);
            var tail = concat(moof, free, mdat);
            var tfra = fullBox("tfra", 1, 0, concat(u32s(1), u32s(0), u32s(1), u64s(5), u64s(moofStart),
                    new byte[]{1, 1, 1}));
            // mfro, the last box of mfra and of the file, repeats the size of mfra
            var mfra = box("mfra", concat(tfra, fullBox("mfro", 0, 0, u32s(8 + tfra.length + 16))));
            return concat(still, moov, tail, mfra);
        }

        static long mdatStart(byte[] still) {
            return indexOf(still, "mdat".getBytes(StandardCharsets.US_ASCII)) + 4;
        }

        @Test
        @DisplayName("moves every chunk, auxiliary, fragment and random-access offset with the data")
        void offsets() {
            var data = mdatStart(Icc.still(true));
            var offsets = new long[]{data, data + 100, data + 50, data + 10};
            var original = sequence(offsets);
            var edited = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder()
                    .exif(ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("A sequence artist")))
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Sequence")).colorProfile(ColorProfile.displayP3())
                    .build());
            var delta = indexOf(edited, "moov".getBytes(StandardCharsets.US_ASCII))
                    - indexOf(original, "moov".getBytes(StandardCharsets.US_ASCII));
            assertNotEquals(0, delta);
            var before = ByteBuffer.wrap(original);
            var after = ByteBuffer.wrap(edited);
            var stco = indexOf(edited, "stco".getBytes(StandardCharsets.US_ASCII));
            var co64 = indexOf(edited, "co64".getBytes(StandardCharsets.US_ASCII));
            var saio = indexOf(edited, "saio".getBytes(StandardCharsets.US_ASCII));
            var moved = new long[]{after.getInt(stco + 12) & 0xFFFFFFFFL, after.getInt(stco + 16) & 0xFFFFFFFFL,
                    after.getLong(co64 + 12), after.getInt(saio + 12) & 0xFFFFFFFFL};
            for (var i = 0; i < offsets.length; i++) {
                assertEquals(offsets[i] + delta, moved[i], "offset " + i);
                assertArrayEquals(Arrays.copyOfRange(original, (int) offsets[i], (int) offsets[i] + 16),
                        Arrays.copyOfRange(edited, (int) moved[i], (int) moved[i] + 16), "data at offset " + i);
            }
            var moofBefore = indexOf(original, "moof".getBytes(StandardCharsets.US_ASCII)) - 4;
            var moofAfter = indexOf(edited, "moof".getBytes(StandardCharsets.US_ASCII)) - 4;
            var tfhd = indexOf(edited, "tfhd".getBytes(StandardCharsets.US_ASCII));
            assertEquals(moofAfter + 200, after.getLong(tfhd + 12));
            var fragmentSaio = moofAfter + indexOf(Arrays.copyOfRange(edited, moofAfter, edited.length),
                    "saio".getBytes(StandardCharsets.US_ASCII));
            assertEquals(RELATIVE_SAIO, after.getInt(fragmentSaio + 12) & 0xFFFFFFFFL);
            var tfra = indexOf(edited, "tfra".getBytes(StandardCharsets.US_ASCII));
            assertEquals(moofAfter, after.getLong(tfra + 4 + 16 + 8));
            assertEquals(moofBefore, before.getLong(indexOf(original, "tfra".getBytes(StandardCharsets.US_ASCII)) + 4 + 16 + 8));
            // a reader finds mfra through the mfro size at the end of the file, so the new item data goes before it
            var mfraSize = after.getInt(edited.length - 4);
            assertEquals("mfra", new String(edited, edited.length - mfraSize + 4, 4, StandardCharsets.ISO_8859_1));
            assertEquals(before.getInt(original.length - 4), mfraSize);
            var read = ImageMetadata.read(MediaSource.of(edited));
            assertEquals(new ExifValue.Ascii("A sequence artist"), read.exif().orElseThrow().ifd0().get(0x013B));
            assertArrayEquals(primaryCodedData(original), primaryCodedData(edited));
        }

        @Test
        @DisplayName("keeps offsets before the meta box and fails when a moved one no longer fits 32 bits")
        void limits() {
            var data = mdatStart(Icc.still(true));
            var kept = sequence(new long[]{8, data, data, data});
            var edited = ImageMetadataEditor.apply(kept, icc(ColorProfile.displayP3()));
            var stco = indexOf(edited, "stco".getBytes(StandardCharsets.US_ASCII));
            assertEquals(8, ByteBuffer.wrap(edited).getInt(stco + 12));
            var overflow = sequence(new long[]{0xFFFF_FFF0L, data, data, data});
            assertThrows(MediaException.LimitExceeded.class, () -> ImageMetadataEditor.apply(overflow,
                    icc(ColorProfile.displayP3())));
        }

        @Test
        @DisplayName("refuses a sequence without a primary item and tracks that locate their own items")
        void unsupported() {
            var stbl = box("stbl", fullBox("stco", 0, 0, concat(u32s(1), u32s(100))));
            var movie = concat(box("ftyp", "avisavis".getBytes(StandardCharsets.US_ASCII)),
                    box("moov", box("trak", box("mdia", box("minf", stbl)))), box("mdat", new byte[64]));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(movie,
                    ImageMetadataEdit.keepAll().toBuilder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "x")).build()));
            var trackMeta = concat(Icc.still(true), box("moov", box("trak", fullBox("meta", 0, 0,
                    fullBox("iloc", 1, 0, new byte[8])))));
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(trackMeta,
                    icc(ColorProfile.displayP3())));
        }

        @Test
        @DisplayName("survives mutated track boxes, raising nothing but MediaException")
        void hostile() {
            var data = mdatStart(Icc.still(true));
            var seed = sequence(new long[]{data, data, data, data});
            var moov = indexOf(seed, "moov".getBytes(StandardCharsets.US_ASCII)) - 4;
            var random = new Random(21);
            for (var round = 0; round < 500; round++) {
                var mutated = seed.clone();
                for (var i = 0; i < 3; i++) {
                    mutated[moov + random.nextInt(seed.length - moov)] = (byte) random.nextInt(256);
                }
                try {
                    ImageMetadataEditor.apply(mutated, icc(ColorProfile.displayP3()));
                } catch (MediaException expected) {
                    // corrupt input may be rejected, never crash
                }
            }
        }
    }

    @Nested
    @DisplayName("ffmpeg and exiftool")
    class Oracles {
        static byte[] avif(boolean animated) {
            Oracle.assumeEncoder("libaom-av1");
            if (animated) {
                return Fixtures.read(Fixtures.testVideo("avif", 64, 64, 0.2,
                        List.of("-an", "-c:v", "libaom-av1", "-cpu-used", "8", "-g", "3")));
            }
            return Fixtures.read(Fixtures.testImage("avif", 64, 64, List.of("-c:v", "libaom-av1", "-still-picture", "1",
                    "-cpu-used", "8")));
        }

        static List<String> validation(byte[] file) {
            var findings = new Findings("sequence");
            IsoBmffValidator.validate(file, findings, IsoBmffValidator.Kind.FILE, java.util.Map.of());
            return findings.problems();
        }

        /** Decodes the frames of the track, the second video stream ffmpeg exposes after the primary item. */
        static byte[] track(java.nio.file.Path file) {
            return Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error", "-i",
                    file.toString(), "-map", "0:v:1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"));
        }

        @Test
        @DisplayName("an animated AVIF keeps every frame and gains the metadata exiftool reads")
        void animated() {
            var original = avif(true);
            assertTrue(indexOf(original, "moov".getBytes(StandardCharsets.US_ASCII)) > 0, "ffmpeg wrote no track");
            var edited = ImageMetadataEditor.apply(original, ImageMetadataEdit.keepAll().toBuilder()
                    .colorProfile(ColorProfile.displayP3())
                    .exif(ExifData.EMPTY.with(ExifData.Directory.IFD0, 0x013B, new ExifValue.Ascii("Animator")))
                    .xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Frames")).build());
            var before = MetadataSupport.save("sequence-before.avif", original);
            var after = MetadataSupport.save("sequence-after.avif", edited);
            var frames = track(before);
            assertTrue(frames.length >= 64 * 64 * 3 * 2, "fewer than two frames");
            assertArrayEquals(frames, track(after));
            assertArrayEquals(Oracle.decodeVideo(before, "rgb24", 0), Oracle.decodeVideo(after, "rgb24", 0));
            // the edit adds no problem to those of ffmpeg's own file (its track durations)
            assertEquals(validation(original), validation(edited));
            Oracle.assertFfmpegDecodes(after);
            var tags = MetadataSupport.exiftoolRead(edited, "sequence-tags.avif");
            assertEquals("Animator", tags.get("IFD0:Artist"), tags.toString());
            assertEquals("Frames", tags.get("XMP-dc:Title"), tags.toString());
            assertEquals("Display P3", tags.get("ICC-header:ProfileDescription") != null
                    ? tags.get("ICC-header:ProfileDescription") : tags.get("ICC_Profile:ProfileDescription"), tags.toString());
            assertArrayEquals(primaryCodedData(original), primaryCodedData(edited));
            var stripped = ImageMetadataEditor.apply(edited, ImageMetadataEdit.stripAll().toBuilder().removeIccProfile()
                    .build());
            assertArrayEquals(frames, track(MetadataSupport.save("sequence-stripped.avif", stripped)));
            assertTrue(ImageMetadata.read(MediaSource.of(stripped)).iccProfile().isEmpty());
        }

        @Test
        @DisplayName("a still AVIF gets, replaces and loses its profile, decoding the same")
        void still() {
            var original = avif(false);
            var withP3 = ImageMetadataEditor.apply(original, icc(ColorProfile.displayP3()));
            var withSrgb = ImageMetadataEditor.apply(withP3, icc(ColorProfile.srgb()));
            var without = ImageMetadataEditor.apply(withSrgb, noIcc());
            var pixels = Oracle.decodeVideo(MetadataSupport.save("still-before.avif", original), "rgb24", 1);
            for (var edited : List.of(withP3, withSrgb, without)) {
                var file = MetadataSupport.save("still-edited.avif", edited);
                assertArrayEquals(pixels, Oracle.decodeVideo(file, "rgb24", 1));
                Oracle.assertDecodesCleanly(file);
            }
            var description = MetadataSupport.exiftool(List.of("-s3", "-ICC_Profile:ProfileDescription",
                    MetadataSupport.save("still-srgb.avif", withSrgb).toString())).trim();
            assertTrue(description.toLowerCase(java.util.Locale.ROOT).contains("srgb"), description);
            assertEquals("", MetadataSupport.exiftool(List.of("-s3", "-ICC_Profile:ProfileDescription",
                    MetadataSupport.save("still-none.avif", without).toString())).trim());
            assertEquals(primaryCodedData(original).length, primaryCodedData(without).length);
            assertInstanceOf(HeifProperty.ColorNclx.class, parse(without).primaryItem().properties().stream()
                    .filter(HeifProperty.ColorNclx.class::isInstance).findFirst().orElse(null));
        }
    }
}
