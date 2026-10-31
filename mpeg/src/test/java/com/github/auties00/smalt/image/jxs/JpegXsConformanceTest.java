package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.jxs.internal.JxsFrameDecoder;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the conformance codestreams of ISO/IEC 21122-4 (third edition) and requires every sample of every component
 * to equal the reference reconstruction the suite ships (big-endian PGX planes, the reference decoder's output); for
 * the Bayer codestreams the reference is the color filter array mosaic. The smoke tier holds the small codestreams
 * (the JXS file, the narrow ones, 4:2:0, MLS, Bayer, CHigh); the full tier, fetched by the nightly build, holds every
 * codestream, the temporal differential coding sequences included, decoded frame after frame against one frame
 * buffer per field parity.
 */
@DisplayName("JPEG XS conformance codestreams (ISO/IEC 21122-4)")
class JpegXsConformanceTest {
    private static final Tolerance TOLERANCE = Tolerance.exact();
    private static final Pattern CODESTREAM = Pattern.compile("^([0-9]+)\\.(jxc|jxs)$");
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT.toBuilder().maxDimension(65535)
            .maxPixels(1L << 30).build();

    @TestFactory
    @DisplayName("smoke tier decodes bit-exactly")
    Stream<DynamicTest> smoke() {
        return streams("jpeg-xs-conformance-smoke", Set.of());
    }

    @TestFactory
    @DisplayName("full tier decodes bit-exactly")
    Stream<DynamicTest> full() {
        var smoke = new HashSet<>(Conformance.archive("jpeg-xs-conformance-smoke").members());
        return streams("jpeg-xs-conformance", smoke);
    }

    private static Stream<DynamicTest> streams(String set, Set<String> excluded) {
        var archive = Conformance.archive(set);
        var members = new HashSet<>(archive.members());
        var tests = new ArrayList<DynamicTest>();
        for (var member : archive.members()) {
            var matcher = CODESTREAM.matcher(member);
            if (matcher.matches() && !excluded.contains(member)) {
                var number = matcher.group(1);
                tests.add(DynamicTest.dynamicTest(member, () -> check(archive, members, number, member)));
            }
        }
        for (var directory : List.of("301", "302", "303", "304", "305", "306", "307", "308")) {
            if (members.stream().anyMatch(name -> name.startsWith(directory + "/"))) {
                tests.add(DynamicTest.dynamicTest(directory + "/ (temporal differential coding)",
                        () -> temporal(archive, members, directory)));
            }
        }
        return tests.stream();
    }

    private static void check(Conformance.Archive archive, Set<String> members, String number, String member)
            throws IOException {
        var data = Files.readAllBytes(archive.fetch(member));
        var image = JpegXsDecoder.decodeImage(MediaSource.of(data), LIMITS);
        var references = references(archive, members, number + ".pgx");
        if (image.cfaPattern().isPresent()) {
            assertEquals(1, references.size(), "reference planes of " + member);
            var reference = references.getFirst();
            assertEquals(image.width(), reference.width(), member + " width");
            assertEquals(image.height(), reference.height(), member + " height");
            TOLERANCE.assertWithin(member + " mosaic", reference.samples(), image.mosaic().orElseThrow(),
                    reference.depth());
            return;
        }
        assertEquals(references.size(), image.components().size(), "components of " + member);
        for (var c = 0; c < references.size(); c++) {
            var reference = references.get(c);
            var component = image.components().get(c);
            assertEquals(reference.width(), component.width(), member + " component " + c + " width");
            assertEquals(reference.height(), component.height(), member + " component " + c + " height");
            assertEquals(reference.depth(), component.precision(), member + " component " + c + " precision");
            TOLERANCE.assertWithin(member + " component " + c, reference.samples(), component.samples(),
                    reference.depth());
        }
    }

    // Each frame is its own file; the fields of 306 (frame_<n>t, frame_<n>b) each predict from the field of the same
    // parity. Every frame is checked component by component, and those without a color transformation also through
    // JpegXsVideoDecoder, whose 16-bit luma plane is then the first component. The listings of 306 name the planes of
    // another frame, so the planes are found from the listing's own name.
    private static void temporal(Conformance.Archive archive, Set<String> members, String directory)
            throws IOException {
        var frames = members.stream().filter(name -> name.startsWith(directory + "/") && name.endsWith(".jxc"))
                .sorted().toList();
        assertTrue(frames.size() > 1, directory + " has frames");
        var buffers = new HashMap<String, JxsFrameDecoder.FrameBuffer>();
        var decoders = new HashMap<String, JpegXsVideoDecoder>();
        for (var frame : frames) {
            var id = frame.substring(frame.lastIndexOf('_') + 1, frame.length() - 4);
            var parity = Character.isDigit(id.charAt(id.length() - 1)) ? "" : id.substring(id.length() - 1);
            var listing = members.stream().filter(name -> name.startsWith(directory + "/")
                    && name.endsWith("_" + id + ".pgx")).findFirst().orElseThrow();
            var data = Files.readAllBytes(archive.fetch(frame));
            var decoded = JxsFrameDecoder.decode(data, 0, data.length, LIMITS,
                    buffers.computeIfAbsent(parity, _ -> new JxsFrameDecoder.FrameBuffer()));
            var components = decoded.layout().components;
            for (var c = 0; c < components; c++) {
                var raw = listing + "_" + c + ".raw";
                var header = listing + "_" + c + ".h";
                assertTrue(members.contains(raw) && members.contains(header), frame + " has reference plane " + c);
                var reference = plane(Files.readString(archive.fetch(header), StandardCharsets.ISO_8859_1),
                        Files.readAllBytes(archive.fetch(raw)));
                assertEquals(reference.width(), decoded.layout().componentWidth[c], frame + " component " + c);
                TOLERANCE.assertWithin(frame + " component " + c, reference.samples(), decoded.samples()[c],
                        reference.depth());
            }
            if (decoded.header().colorTransform == 0) {
                var decoder = decoders.computeIfAbsent(parity, _ -> new JpegXsVideoDecoder(VideoTrack.builder().id(1)
                        .codec(Codec.JPEG_XS).timeBase(new Rational(1, 25)).width(1).height(1).build(),
                        VideoDecoderOptions.builder().limits(LIMITS).highBitDepth(true).build()));
                var pictures = new ArrayList<VideoFrame>();
                decoder.decode(new Packet(1, data, 0, 0, 1, true), pictures::add);
                var picture = assertInstanceOf(Yuv16Picture.class, pictures.getFirst().picture());
                assertFalse(pictures.getFirst().keyframe(), frame + " is a key frame");
                var luma = new short[decoded.samples()[0].length];
                var width = decoded.layout().componentWidth[0];
                for (var y = 0; y < luma.length / width; y++) {
                    System.arraycopy(picture.y(), y * picture.yStride(), luma, y * width, width);
                }
                TOLERANCE.assertWithin(frame + " video luma", decoded.samples()[0], luma,
                        decoded.header().precision[0]);
            }
        }
    }

    /**
     * One reference plane: the samples of a PGX raw file and its header.
     */
    record Plane(int width, int height, int depth, short[] samples) {
    }

    private static List<Plane> references(Conformance.Archive archive, Set<String> members, String listing)
            throws IOException {
        var planes = new ArrayList<Plane>();
        var prefix = listing.contains("/") ? listing.substring(0, listing.lastIndexOf('/') + 1) : "";
        for (var line : Files.readAllLines(archive.fetch(listing), StandardCharsets.ISO_8859_1)) {
            var raw = line.strip();
            if (raw.isEmpty()) {
                continue;
            }
            var name = prefix + raw;
            var header = prefix + raw.substring(0, raw.length() - 4) + ".h";
            assertTrue(members.contains(name) && members.contains(header), listing + " names " + raw);
            planes.add(plane(Files.readString(archive.fetch(header), StandardCharsets.ISO_8859_1),
                    Files.readAllBytes(archive.fetch(name))));
        }
        return planes;
    }

    /**
     * Reads a reference plane: its header is {@code PG ML +<depth> <width> <height>}, its samples big-endian, one
     * byte each up to 8 bits and two above.
     */
    static Plane plane(String header, byte[] raw) {
        var fields = header.strip().split("\\s+");
        var depth = Integer.parseInt(fields[2].replace("+", ""));
        var width = Integer.parseInt(fields[3]);
        var height = Integer.parseInt(fields[4]);
        var samples = new short[width * height];
        if (depth <= 8) {
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (short) (raw[i] & 0xFF);
            }
        } else {
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (short) ((raw[2 * i] & 0xFF) << 8 | raw[2 * i + 1] & 0xFF);
            }
        }
        return new Plane(width, height, depth, samples);
    }
}
