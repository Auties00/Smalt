package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the phone metadata of MP4 and QuickTime files that {@link TagEditor} reads, edits and strips: QuickTime keyed
 * metadata, user data text atoms, the 3GPP location box and XMP. The inputs are the behavior samples made by
 * {@code tools/testdata/behavior/make-keyed-metadata-samples.py} (an iPhone-like movie and an Android-like file) and
 * files ffmpeg writes; ffprobe and ExifTool are the independent readers, and the media data must stay byte for byte
 * the same (the {@code mdat} boxes and ffmpeg's packet hashes).
 */
@DisplayName("MP4 phone metadata")
class PhoneMetadataTest {
    static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "mp4");

    static final Path PHONE = SAMPLES.resolve("phone-keyed.mov");

    static final Path ANDROID = SAMPLES.resolve("location-xmp.mp4");

    static byte[] phone() {
        return Fixtures.read(PHONE);
    }

    static byte[] android() {
        return Fixtures.read(ANDROID);
    }

    static Path save(Path directory, String name, byte[] data) throws IOException {
        var file = directory.resolve(name);
        Files.write(file, data);
        return file;
    }

    /** Returns the SHA-256 of the payloads of the top-level mdat boxes, in order. */
    static List<String> mdat(byte[] file) {
        var result = new ArrayList<String>();
        for (var box : Mp4Atoms.top(file)) {
            if (box.type().equals("mdat")) {
                try {
                    var digest = MessageDigest.getInstance("SHA-256");
                    digest.update(file, box.payload(), box.end() - box.payload());
                    result.add(HexFormat.of().formatHex(digest.digest()));
                } catch (NoSuchAlgorithmException exception) {
                    throw new AssertionError(exception);
                }
            }
        }
        return result;
    }

    /** Returns the keyed metadata items of the meta boxes of moov and moov/udta: key to data type of the first atom. */
    static Map<String, Integer> keyedTypes(byte[] file) {
        var result = new LinkedHashMap<String, Integer>();
        for (var moov : Mp4Atoms.all(file, "moov")) {
            var levels = new ArrayList<>(List.of(moov));
            levels.addAll(Mp4Atoms.all(file, moov, "udta"));
            for (var level : levels) {
                for (var meta : Mp4Atoms.children(file, level.payload(), level.end())) {
                    if (meta.type().equals("meta")) {
                        keyed(file, meta, result);
                    }
                }
            }
        }
        return result;
    }

    private static void keyed(byte[] file, Mp4Atoms.Box meta, Map<String, Integer> result) {
        var start = Mp4Atoms.type(file, meta.payload() + 4).equals("hdlr") ? meta.payload() : meta.payload() + 4;
        var children = Mp4Atoms.children(file, start, meta.end());
        var hdlr = children.stream().filter(box -> box.type().equals("hdlr")).findFirst().orElse(null);
        if (hdlr == null || !Mp4Atoms.type(file, hdlr.payload() + 8).equals("mdta")) {
            return;
        }
        var names = new ArrayList<String>();
        for (var box : children) {
            if (box.type().equals("keys")) {
                var position = box.payload() + 8;
                for (var index = 0L; index < Mp4Atoms.u32(file, box.payload() + 4); index++) {
                    var size = (int) Mp4Atoms.u32(file, position);
                    names.add(new String(file, position + 8, size - 8, StandardCharsets.UTF_8));
                    position += size;
                }
            }
        }
        for (var box : children) {
            if (box.type().equals("ilst")) {
                for (var item : Mp4Atoms.children(file, box.payload(), box.end())) {
                    var key = names.get((int) Mp4Atoms.u32(file, item.offset() + 4) - 1);
                    var data = Mp4Atoms.children(file, item.payload(), item.end()).getFirst();
                    result.put(key, (int) Mp4Atoms.u32(file, data.payload()));
                }
            }
        }
    }

    /** Returns a key as ffprobe's flat output prints it: lower case, other characters than letters and digits as {@code _}. */
    static String flat(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    /** Returns the ffprobe format tags whose key names a place. */
    static Map<String, String> probedLocations(Path file) {
        var result = new LinkedHashMap<String, String>();
        TagFixtures.ffprobeTags(file).forEach((key, value) -> {
            if (key.contains("location") || key.contains("xyz")) {
                result.put(key, value);
            }
        });
        return result;
    }

    /** Returns the tags ExifTool reads, keyed by group and name, as {@code -j -G1 -n} prints them. */
    static Map<String, String> exiftool(Path file) {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        var json = new String(Oracle.run(Oracle.Tool.EXIFTOOL, java.time.Duration.ofMinutes(1), List.of("-j", "-G1",
                "-n", file.toString())), StandardCharsets.UTF_8);
        var result = new LinkedHashMap<String, String>();
        var matcher = java.util.regex.Pattern.compile("\"([^\"]+)\": \"?(.*?)\"?,?\\r?$", java.util.regex.Pattern.MULTILINE)
                .matcher(json);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    /** Returns the ExifTool tags that record a place: GPS tags and location information. */
    static Map<String, String> exiftoolLocations(Path file) {
        var result = new LinkedHashMap<String, String>();
        exiftool(file).forEach((key, value) -> {
            var name = key.substring(key.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith("gps") || name.contains("location") || name.equals("city")) {
                result.put(key, value);
            }
        });
        return result;
    }

    static boolean contains(byte[] data, String text) {
        return new String(data, StandardCharsets.ISO_8859_1).contains(text);
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("reads the keyed metadata, text atoms and XMP of an iPhone movie with its data types as text")
        void phone() {
            var tags = TagEditor.read(MediaSource.of(PhoneMetadataTest.phone()));
            assertEquals(Optional.of("+45.4642+009.1900+120.000/"), tags.get("com.apple.quicktime.location.ISO6709"));
            assertEquals(Optional.of("14.172235"), tags.get("com.apple.quicktime.location.accuracy.horizontal"));
            assertEquals(Optional.of("Apple"), tags.get("com.apple.quicktime.make"));
            assertEquals(Optional.of("iPhone 15 Pro"), tags.get("com.apple.quicktime.model"));
            assertEquals(Optional.of("17.4.1"), tags.get("com.apple.quicktime.software"));
            assertEquals(Optional.of("2024-05-01T12:00:00+0200"), tags.get("com.apple.quicktime.creationdate"));
            assertEquals(Optional.of("Behavior \u00E8"), tags.get("com.apple.quicktime.author"));
            assertEquals(Optional.of("0.9375"), tags.get("com.apple.quicktime.live-photo.vitality-score"));
            assertEquals(Optional.of("-1"), tags.get("com.apple.quicktime.full-frame-rate-playback-intent"));
            assertEquals(Optional.of("24"), tags.get("com.apple.quicktime.camera.focal_length.35mm_equivalent"));
            assertTrue(tags.get("com.apple.quicktime.cinematic-video").isEmpty(), "a binary item is opaque");
            assertEquals(Optional.of("+45.4642+009.1900/"), tags.get("\u00A9xyz"));
            assertEquals(Optional.of("Apple"), tags.get("\u00A9mak"));
            assertEquals(Optional.of("Phone"), tags.xmp().orElseThrow().text(XmpPacket.DC, "title"));
            assertTrue(tags.hasLocation());
        }

        @Test
        @DisplayName("reads the 3GPP location, the keyed metadata in udta and the XMP uuid box of an Android file")
        void android() {
            var tags = TagEditor.read(MediaSource.of(PhoneMetadataTest.android()));
            assertEquals(Optional.of("+45.4642+009.19+120/"), tags.get("loci"));
            assertEquals(Optional.of("14"), tags.get("com.android.version"));
            assertEquals(Optional.of("30.0"), tags.get("com.android.capture.fps"));
            assertEquals(Optional.of("Behavior"), tags.xmp().orElseThrow().text(XmpPacket.XMP, "CreatorTool"));
            assertEquals("+45.4642+009.19+120/", Media.probe(MediaSource.of(PhoneMetadataTest.android())).tags()
                    .get("location"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"phone-keyed.mov", "location-xmp.mp4"})
        @DisplayName("agrees with ffprobe on every keyed metadata value ffprobe reports")
        void ffprobe(String name) {
            var tags = TagEditor.read(SAMPLES.resolve(name));
            var probe = TagFixtures.ffprobeTags(SAMPLES.resolve(name));
            var compared = 0;
            for (var entry : probe.entrySet()) {
                if (!entry.getKey().startsWith("com_") || entry.getKey().endsWith("cinematic_video")) {
                    continue;
                }
                var read = tags.entries().stream().filter(tag -> flat(tag.key()).equals(entry.getKey())).findFirst()
                        .orElseThrow(() -> new AssertionError(entry + " not read: " + tags)).value();
                if (read.matches("-?\\d+(\\.\\d+)?") && entry.getValue().matches("-?\\d+(\\.\\d+)?")) {
                    assertEquals(Double.parseDouble(entry.getValue()), Double.parseDouble(read), 1e-9, entry.getKey());
                } else {
                    assertEquals(entry.getValue(), read, entry.getKey());
                }
                compared++;
            }
            assertTrue(compared >= 2, probe.toString());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"phone-keyed.mov", "location-xmp.mp4"})
        @DisplayName("rewrites the file it read byte for byte")
        void identity(String name, @TempDir Path directory) throws IOException {
            var original = Fixtures.read(SAMPLES.resolve(name));
            // the QuickTime text atoms of the iPhone movie move into the ilst on the first write
            var data = TagEditor.write(original, TagEditor.read(MediaSource.of(original)), TagWriteOptions.defaults());
            if (name.endsWith(".mp4")) {
                assertArrayEquals(original, data);
            }
            assertEquals(TagEditor.read(MediaSource.of(original)), TagEditor.read(MediaSource.of(data)));
            assertEquals(mdat(original), mdat(data));
            assertArrayEquals(data, TagEditor.write(data, TagEditor.read(MediaSource.of(data)), TagWriteOptions.defaults()));
            var file = save(directory, name, data);
            assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file)));
            assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file),
                    TagWriteOptions.builder().inPlace(false).build()));
        }
    }

    @Nested
    @DisplayName("stripping the location")
    class Stripping {
        static Stream<Arguments> files() {
            var mov = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO), List.of("-c:v", "mpeg4",
                    "-metadata", "location=+37.3349-122.0090/", "-metadata", "title=Kept"));
            var mp4 = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i",
                    TagFixtures.AUDIO), List.of("-c:v", "mpeg4", "-c:a", "aac", "-shortest", "-metadata",
                    "location=+37.3349-122.0090/", "-metadata", "title=Kept"));
            var keyed = Fixtures.ffmpeg("mov", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO), List.of("-c:v", "mpeg4",
                    "-movflags", "use_metadata_tags", "-metadata", "com.apple.quicktime.location.ISO6709=+37.3349-122.0090/",
                    "-metadata", "title=Kept"));
            var faststart = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO), List.of("-c:v",
                    "mpeg4", "-movflags", "+faststart", "-metadata", "location=+37.3349-122.0090/", "-metadata",
                    "title=Kept"));
            return Stream.of(Arguments.of("iPhone keyed metadata, text atom and XMP atom", PHONE),
                    Arguments.of("3GPP location, Android keyed metadata and XMP uuid", ANDROID),
                    Arguments.of("ffmpeg mov text atom", mov), Arguments.of("ffmpeg mp4 loci", mp4),
                    Arguments.of("ffmpeg keyed metadata", keyed), Arguments.of("ffmpeg faststart loci", faststart));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("files")
        @DisplayName("leaves nothing ffprobe, ExifTool or Smalt read as a place, and keeps the media and the other tags")
        void strips(String name, Path input, @TempDir Path directory) throws IOException {
            var original = Fixtures.read(input);
            var file = save(directory, "strip-" + input.getFileName(), original);
            var before = TagEditor.read(file);
            assertTrue(before.hasLocation(), before.toString());
            assertFalse(probedLocations(file).isEmpty(), "the input records a place for ffprobe");
            var packets = TagFixtures.packetHash(file);
            assertNotEquals(TagWriteResult.UNCHANGED, TagEditor.stripLocation(file));
            var stripped = Files.readAllBytes(file);
            var after = TagEditor.read(file);
            assertFalse(after.hasLocation(), after.toString());
            assertEquals(before.toBuilder().removeLocation().build(), after);
            assertEquals(mdat(original), mdat(stripped), "the media data moved or changed");
            assertEquals(packets, TagFixtures.packetHash(file));
            assertEquals(Map.of(), probedLocations(file));
            assertTrue(Media.probe(MediaSource.of(stripped)).tags().keySet().stream()
                    .noneMatch(key -> key.contains("location")), "Media.probe");
            for (var place : List.of("+45.4642", "+37.3349", "45,27.852N", "Milano")) {
                assertFalse(contains(stripped, place), place + " is left in the file");
            }
            assertEquals(TagWriteResult.UNCHANGED, TagEditor.stripLocation(file));
            assertEquals(Map.of(), exiftoolLocations(file));
            Oracle.assertDecodesCleanly(file);
        }

        @Test
        @DisplayName("keeps the other keyed metadata, its data types and the opaque items of an iPhone movie")
        void keepsKeyed(@TempDir Path directory) throws IOException {
            var file = save(directory, "phone.mov", phone());
            TagEditor.stripLocation(file);
            var data = Files.readAllBytes(file);
            var types = keyedTypes(data);
            assertEquals(List.of("com.apple.quicktime.make", "com.apple.quicktime.model", "com.apple.quicktime.software",
                    "com.apple.quicktime.creationdate", "com.apple.quicktime.author",
                    "com.apple.quicktime.live-photo.vitality-score", "com.apple.quicktime.full-frame-rate-playback-intent",
                    "com.apple.quicktime.camera.focal_length.35mm_equivalent", "com.apple.quicktime.cinematic-video"),
                    List.copyOf(types.keySet()));
            assertEquals(List.of(1, 1, 1, 1, 2, 23, 21, 22, 0), List.copyOf(types.values()));
            var probe = TagFixtures.ffprobeTags(save(directory, "probe.mov", data));
            assertEquals("Apple", probe.get(flat("com.apple.quicktime.make")));
            assertEquals("0.937500", probe.get(flat("com.apple.quicktime.live-photo.vitality-score")));
            var xmp = TagEditor.read(file).xmp().orElseThrow();
            assertEquals(Optional.of("Phone"), xmp.text(XmpPacket.DC, "title"));
            assertTrue(xmp.text(XmpPacket.PHOTOSHOP, "City").isEmpty());
            var exif = exiftool(file);
            assertTrue(exif.containsValue("Apple"), exif.toString());
            assertTrue(exif.containsValue("Phone"), exif.toString());
        }

        @Test
        @DisplayName("replaces a moved XMP uuid box by zeroes and writes the new packet once")
        void blanksXmp(@TempDir Path directory) throws IOException {
            var tags = MediaTags.builder().set(StandardTag.TITLE, "muxed").xmp(XmpPacket.empty()
                    .with(XmpPacket.DC, "title", "Muxed").with(XmpPacket.EXIF, "GPSLatitude", "45,27.852N")).build();
            var muxed = MuxedTagsTest.remux(MuxedTagsTest.untagged("mp4", List.of("-c:a", "aac")), Mp4Muxer::new, tags);
            var boxes = Mp4Atoms.top(muxed).stream().map(Mp4Atoms.Box::type).toList();
            assertEquals("uuid", boxes.get(1), "the muxer writes the packet after ftyp: " + boxes);
            var stripped = TagEditor.stripLocation(muxed, TagWriteOptions.defaults());
            assertFalse(contains(stripped, "45,27.852N"));
            var uuids = Mp4Atoms.top(stripped).stream().filter(box -> box.type().equals("uuid")).toList();
            assertEquals(1, uuids.size());
            assertTrue(uuids.getFirst().offset() > Mp4Atoms.all(stripped, "moov").getFirst().offset());
            var blank = Mp4Atoms.top(stripped).get(1);
            assertEquals("free", blank.type());
            for (var index = blank.payload(); index < blank.end(); index++) {
                assertEquals(0, stripped[index]);
            }
            assertEquals(Optional.of("Muxed"), TagEditor.read(MediaSource.of(stripped)).xmp().orElseThrow()
                    .text(XmpPacket.DC, "title"));
            assertEquals(mdat(muxed), mdat(stripped));
            Oracle.assertDecodesCleanly(save(directory, "blanked.mp4", stripped));
        }
    }

    @Nested
    @DisplayName("editing")
    class Editing {
        @Test
        @DisplayName("changes, retypes, removes and adds keyed metadata where the file keeps it")
        void edits(@TempDir Path directory) throws IOException {
            var file = save(directory, "edit.mov", phone());
            var edited = TagEditor.read(file).toBuilder().set("com.apple.quicktime.make", "Smalt")
                    .set("com.apple.quicktime.live-photo.vitality-score", "0.5")
                    .set("com.apple.quicktime.camera.focal_length.35mm_equivalent", "wide")
                    .remove("com.apple.quicktime.software").set("com.smalt.added.key", "new").build();
            TagEditor.write(file, edited);
            var data = Files.readAllBytes(file);
            var read = TagEditor.read(file);
            assertEquals(Optional.of("Smalt"), read.get("com.apple.quicktime.make"));
            assertEquals(Optional.of("0.5"), read.get("com.apple.quicktime.live-photo.vitality-score"));
            assertEquals(Optional.of("wide"), read.get("com.apple.quicktime.camera.focal_length.35mm_equivalent"));
            assertTrue(read.get("com.apple.quicktime.software").isEmpty());
            assertEquals(Optional.of("new"), read.get("com.smalt.added.key"));
            var types = keyedTypes(data);
            assertEquals(23, types.get("com.apple.quicktime.live-photo.vitality-score"));
            assertEquals(1, types.get("com.apple.quicktime.camera.focal_length.35mm_equivalent"));
            assertEquals(1, types.get("com.smalt.added.key"));
            assertEquals(1, keyedBoxes(data), "every keyed item stays in the movie's one keyed metadata box");
            var probe = TagFixtures.ffprobeTags(file);
            assertEquals("Smalt", probe.get(flat("com.apple.quicktime.make")));
            assertEquals("0.500000", probe.get(flat("com.apple.quicktime.live-photo.vitality-score")));
            assertEquals("new", probe.get(flat("com.smalt.added.key")));
            assertFalse(probe.containsKey(flat("com.apple.quicktime.software")));
            assertEquals(mdat(phone()), mdat(data));
            assertTrue(exiftool(file).containsValue("Smalt"));
        }

        static int keyedBoxes(byte[] data) {
            var count = 0;
            for (var moov : Mp4Atoms.all(data, "moov")) {
                for (var child : Mp4Atoms.children(data, moov.payload(), moov.end())) {
                    if (child.type().equals("meta")) {
                        count++;
                    }
                }
            }
            return count;
        }

        static Stream<String> plain() {
            return Stream.of("mp4", "mov");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("plain")
        @DisplayName("adds a QuickTime keyed metadata box to an MP4 or MOV file without one, and removes it again")
        void adds(String extension, @TempDir Path directory) throws IOException {
            var input = Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", TagFixtures.VIDEO), List.of("-c:v",
                    "mpeg4", "-metadata", "title=Plain"));
            var file = save(directory, "plain." + extension, Fixtures.read(input));
            var tags = TagEditor.read(file).toBuilder().set("com.apple.quicktime.make", "Smalt")
                    .set("com.apple.quicktime.location.ISO6709", "+45.4642+009.1900/").build();
            TagEditor.write(file, tags);
            var data = Files.readAllBytes(file);
            var meta = Mp4Atoms.children(data, Mp4Atoms.all(data, "moov").getFirst().payload(),
                    Mp4Atoms.all(data, "moov").getFirst().end()).stream().filter(box -> box.type().equals("meta"))
                    .findFirst().orElseThrow();
            assertEquals("hdlr", Mp4Atoms.type(data, meta.payload() + 4), "a QuickTime atom without version and flags");
            assertEquals(Map.of("com.apple.quicktime.make", 1, "com.apple.quicktime.location.ISO6709", 1),
                    keyedTypes(data));
            var probe = TagFixtures.ffprobeTags(file);
            assertEquals("Smalt", probe.get(flat("com.apple.quicktime.make")));
            assertEquals("+45.4642+009.1900/", probe.get(flat("com.apple.quicktime.location.iso6709")));
            assertEquals("Plain", probe.get("title"));
            var exif = exiftool(file);
            assertTrue(exif.containsValue("Smalt"), exif.toString());
            assertFalse(exiftoolLocations(file).isEmpty(), exif.toString());
            assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, tags));
            TagEditor.stripLocation(file);
            assertEquals(Map.of("com.apple.quicktime.make", 1), keyedTypes(Files.readAllBytes(file)));
            TagEditor.write(file, TagEditor.read(file).toBuilder().remove("com.apple.quicktime.make").build());
            assertEquals(0, keyedBoxes(Files.readAllBytes(file)), "an empty keyed metadata box is removed");
            assertEquals(mdat(Fixtures.read(input)), mdat(Files.readAllBytes(file)));
        }

        @Test
        @DisplayName("moves the coordinates of the 3GPP box, keeping its place name, and rejects text that is no place")
        void loci(@TempDir Path directory) throws IOException {
            var file = save(directory, "loci.mp4", android());
            TagEditor.write(file, TagEditor.read(file).toBuilder().set("loci", "-33.8688+151.2093/").build());
            assertEquals(Optional.of("-33.8688+151.2093/"), TagEditor.read(file).get("loci"));
            var probe = TagFixtures.ffprobeTags(file);
            var location = probe.get("location");
            assertTrue(location.startsWith("-33.8688+151.2093") && location.endsWith("Milano"), probe.toString());
            var input = Fixtures.read(ANDROID);
            assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(input, TagEditor.read(
                    MediaSource.of(input)).toBuilder().set("loci", "Milano").build(), TagWriteOptions.defaults()));
            var added = TagEditor.write(Fixtures.read(PHONE), TagEditor.read(MediaSource.of(Fixtures.read(PHONE)))
                    .toBuilder().set("loci", "+10+020/").build(), TagWriteOptions.defaults());
            assertEquals(Optional.of("+10+020/"), TagEditor.read(MediaSource.of(added)).get("loci"));
        }
    }

    @Nested
    @DisplayName("muxing")
    class Muxing {
        static Stream<Arguments> brands() {
            return Stream.of(Arguments.of("mp4", Mp4MuxerOptions.Brand.MP4), Arguments.of("mov",
                    Mp4MuxerOptions.Brand.QUICKTIME));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("brands")
        @DisplayName("writes keyed metadata, the 3GPP location and XMP that ffprobe, ExifTool and TagEditor read")
        void muxes(String extension, Mp4MuxerOptions.Brand brand, @TempDir Path directory) throws IOException {
            var tags = MediaTags.builder().set(StandardTag.TITLE, "Muxed").set("com.apple.quicktime.make", "Smalt")
                    .set("loci", "+45.4642+009.19/").xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "Packet"))
                    .build();
            var output = MuxedTagsTest.remux(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")),
                    sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(brand).build()), tags);
            var read = TagEditor.read(MediaSource.of(output));
            assertEquals(Optional.of("Smalt"), read.get("com.apple.quicktime.make"));
            assertEquals(Optional.of("+45.4642+009.19/"), read.get("loci"));
            assertEquals(Optional.of("Packet"), read.xmp().orElseThrow().text(XmpPacket.DC, "title"));
            assertEquals(brand == Mp4MuxerOptions.Brand.QUICKTIME, Mp4Atoms.all(output, "moov/udta/XMP_").size() == 1);
            assertArrayEquals(output, TagEditor.write(output, read, TagWriteOptions.defaults()));
            var file = save(directory, "muxed." + extension, output);
            var probe = TagFixtures.ffprobeTags(file);
            assertEquals("Smalt", probe.get(flat("com.apple.quicktime.make")), probe.toString());
            assertTrue(probe.get("location").startsWith("+45.4642+009.19"), probe.toString());
            var exif = exiftool(file);
            assertTrue(exif.containsValue("Packet"), exif.toString());
            Oracle.assertDecodesCleanly(file);
        }
    }
}
