package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.image.AmbientViewingEnvironment;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ContentColorVolume;
import com.github.auties00.smalt.internal.sei.H274Sei;
import com.github.auties00.smalt.internal.sei.SeiWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FramePacking;
import com.github.auties00.smalt.video.FrameSideData;
import com.github.auties00.smalt.video.OmnidirectionalVideo;
import com.github.auties00.smalt.video.OmnidirectionalVideo.Equirectangular;
import com.github.auties00.smalt.video.OmnidirectionalVideo.GeneralizedCubemap;
import com.github.auties00.smalt.video.OmnidirectionalVideo.RegionWisePacking;
import com.github.auties00.smalt.video.OmnidirectionalVideo.SphereRotation;
import com.github.auties00.smalt.video.OmnidirectionalVideo.Viewports;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Rec. ITU-T H.274 viewing SEI messages the VVC decoder reports as frame side data: the ambient viewing
 * environment, content colour volume, sample aspect ratio information and omnidirectional messages of
 * {@code sei-h274-420-10bit.vvc} (the low-delay VVenC vector with the messages inserted by
 * {@code tools/testdata/vvc-vectors/make-sei-vector.py}, which outputs its pictures in decoding order). The values
 * of each frame's own messages must equal the raw syntax values VVdeC reports for them
 * ({@code sei-h274-420-10bit.vvdec.txt}), and the persistence, cancellation and ignore rules of H.274 must give each
 * frame the entries listed here.
 */
@DisplayName("VVC viewing SEI messages")
class VvcViewingSeiTest {
    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    private static final double DEGREE = 1.0 / 65536;

    private static final AmbientViewingEnvironment AMBIENT = new AmbientViewingEnvironment(100_000 * 0.0001,
            new Chromaticity(15_635 * 0.00002, 16_450 * 0.00002));

    private static final ContentColorVolume VOLUME = new ContentColorVolume(List.of(
            new ContentColorVolume.Coordinate(15_000 * 0.00002, 30_000 * 0.00002),
            new ContentColorVolume.Coordinate(7_500 * 0.00002, 3_000 * 0.00002),
            new ContentColorVolume.Coordinate(32_000 * 0.00002, 16_500 * 0.00002)), OptionalDouble.of(100 * 1e-7),
            OptionalDouble.of(9_000_000 * 1e-7), OptionalDouble.of(1_000_000 * 1e-7));

    private static final ContentColorVolume MAXIMUM_ONLY = new ContentColorVolume(List.of(), OptionalDouble.empty(),
            OptionalDouble.of(5_000_000 * 1e-7), OptionalDouble.empty());

    private static final Equirectangular GUARDED = new Equirectangular(Optional.of(
            new Equirectangular.GuardBands(2, 8, 6)));

    private static final Equirectangular PLAIN = new Equirectangular(Optional.empty());

    private static final SphereRotation ROTATION = new SphereRotation(-90.5, 45, DEGREE);

    private static final RegionWisePacking PACKING = new RegionWisePacking(false, 256, 128, 128, 96, List.of(
            new RegionWisePacking.Region(0, 128, 128, 0, 0, 64, 96, 0, 0, Optional.empty()),
            new RegionWisePacking.Region(5, 128, 128, 0, 128, 64, 96, 0, 64, Optional.of(
                    new RegionWisePacking.RegionGuardBands(2, 2, 0, 0, true, List.of(1, 2, 3, 0))))));

    private static final Viewports VIEWPORTS = new Viewports(0, List.of(
            new Viewports.Viewport(10, -20, 0, 90, 60), new Viewports.Viewport(-170, 80, 5, 360, 180)));

    private static final GeneralizedCubemap CUBEMAP = new GeneralizedCubemap(1, 2, List.of(
            new GeneralizedCubemap.Face(5, 0, 10, true, 20, false), new GeneralizedCubemap.Face(0, 1, 0, false, 127, true),
            new GeneralizedCubemap.Face(4, 2, 64, true, 64, true), new GeneralizedCubemap.Face(3, 3, 1, false, 2, false),
            new GeneralizedCubemap.Face(1, 0, 0, false, 0, false), new GeneralizedCubemap.Face(2, 1, 30, true, 40, false)),
            Optional.of(new GeneralizedCubemap.CubemapGuardBand(1, true, 4)));

    private static byte[] vector() {
        return Fixtures.read(VECTORS.resolve("sei-h274-420-10bit.vvc"));
    }

    private static List<FrameSideData> decode(byte[] stream) {
        var frames = VvcSeiTest.decode(stream, VvcDecoderOptions.defaults(), new ArrayList<>());
        return frames.stream().map(VideoFrame::sideData).toList();
    }

    private static OmnidirectionalVideo omni(OmnidirectionalVideo.Projection projection, SphereRotation rotation) {
        return new OmnidirectionalVideo(Optional.of(projection), Optional.ofNullable(rotation), Optional.of(PACKING),
                Optional.of(VIEWPORTS));
    }

    @Test
    @DisplayName("gives each frame the entries the persistence, cancellation and ignore rules leave")
    void persistence() {
        var frames = decode(vector());
        assertEquals(10, frames.size());
        var volumes = new ContentColorVolume[]{VOLUME, VOLUME, VOLUME, MAXIMUM_ONLY, null, null, null, null, null,
                null};
        var aspects = new Rational[]{new Rational(4, 3), new Rational(4, 3), new Rational(12, 11), null, null, null,
                null, null, null, null};
        var omnis = new OmnidirectionalVideo[]{omni(GUARDED, ROTATION), omni(GUARDED, ROTATION),
                omni(GUARDED, ROTATION), omni(GUARDED, ROTATION), omni(GUARDED, ROTATION), omni(GUARDED, null), null,
                omni(CUBEMAP, null), null, omni(PLAIN, null)};
        for (var i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            assertEquals(Optional.of(AMBIENT), frame.ambientViewing(), "ambient viewing of frame " + i);
            assertEquals(Optional.ofNullable(volumes[i]), frame.contentColorVolume(), "color volume of frame " + i);
            assertEquals(Optional.ofNullable(aspects[i]), frame.sampleAspectRatio(), "sample aspect of frame " + i);
            assertEquals(Optional.ofNullable(omnis[i]), frame.omnidirectional(), "omnidirectional video of frame " + i);
        }
        // the top and bottom arrangement with a mirrored view is not one under which a projection applies
        assertEquals(FramePacking.Arrangement.TOP_BOTTOM, frames.get(8).framePacking().orElseThrow().arrangement());
        assertEquals(FramePacking.Arrangement.SIDE_BY_SIDE, frames.get(9).framePacking().orElseThrow().arrangement());
    }

    @Test
    @DisplayName("reports the values VVdeC reads from each frame's own messages")
    void matchesVvdec() throws Exception {
        var stream = vector();
        var dump = new String(Fixtures.read(VECTORS.resolve("sei-h274-420-10bit.vvdec.txt")), StandardCharsets.US_ASCII)
                .lines().toArray(String[]::new);
        assertEquals("# " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream)), dump[0],
                "the dump is of the vector");
        var frames = decode(stream);
        var frame = -1;
        var checked = 0;
        for (var index = 1; index < dump.length; index++) {
            var line = dump[index].strip();
            if (line.isEmpty()) {
                continue;
            }
            var fields = line.split(" ");
            if (fields[0].equals("frame")) {
                frame = Integer.parseInt(fields[1]);
                continue;
            }
            var side = frames.get(frame);
            var omni = side.omnidirectional();
            switch (fields[0]) {
                case "ambient" -> assertEquals(new AmbientViewingEnvironment(Long.parseLong(fields[1]) * 0.0001,
                        new Chromaticity(Integer.parseInt(fields[2]) * 0.00002, Integer.parseInt(fields[3]) * 0.00002)),
                        side.ambientViewing().orElseThrow());
                case "ccv" -> {
                    if (fields[1].equals("cancel=1")) {
                        assertTrue(side.contentColorVolume().isEmpty());
                        break;
                    }
                    var primaries = new ArrayList<ContentColorVolume.Coordinate>();
                    var min = OptionalDouble.empty();
                    var max = OptionalDouble.empty();
                    var average = OptionalDouble.empty();
                    for (var i = 3; i < fields.length; i++) {
                        var pair = fields[i].split("=");
                        switch (pair[0]) {
                            case "min" -> min = OptionalDouble.of(Long.parseLong(pair[1]) * 1e-7);
                            case "max" -> max = OptionalDouble.of(Long.parseLong(pair[1]) * 1e-7);
                            case "avg" -> average = OptionalDouble.of(Long.parseLong(pair[1]) * 1e-7);
                            default -> {
                                var xy = pair[1].split(",");
                                primaries.add(new ContentColorVolume.Coordinate(Integer.parseInt(xy[0]) * 0.00002,
                                        Integer.parseInt(xy[1]) * 0.00002));
                            }
                        }
                    }
                    assertEquals(new ContentColorVolume(primaries, min, max, average),
                            side.contentColorVolume().orElseThrow());
                }
                case "sari" -> {
                    if (fields[1].equals("cancel=1")) {
                        assertTrue(side.sampleAspectRatio().isEmpty());
                        break;
                    }
                    var idc = Integer.parseInt(fields[3].substring(4));
                    var expected = idc == 255 ? new Rational(Long.parseLong(fields[4].split("x")[0]),
                            Long.parseLong(fields[4].split("x")[1])) : idc == 2 ? new Rational(12, 11) : null;
                    assertEquals(expected, side.sampleAspectRatio().orElseThrow());
                }
                case "erp" -> {
                    if (fields[1].equals("cancel=1")) {
                        assertTrue(omni.isEmpty());
                        break;
                    }
                    var expected = fields.length > 3 ? new Equirectangular(Optional.of(guardBands(fields[3])))
                            : PLAIN;
                    if (frame == 8) {
                        assertTrue(omni.isEmpty(), "a projection under a top and bottom mirrored arrangement");
                    } else {
                        assertEquals(expected, omni.orElseThrow().projection().orElseThrow());
                    }
                }
                case "gcmp" -> assertEquals(cubemap(fields), omni.orElseThrow().projection().orElseThrow());
                case "rotation" -> {
                    if (fields[1].equals("cancel=1")) {
                        assertTrue(omni.orElseThrow().rotation().isEmpty());
                        break;
                    }
                    assertEquals(new SphereRotation(Integer.parseInt(fields[3]) * DEGREE,
                            Integer.parseInt(fields[4]) * DEGREE, Integer.parseInt(fields[5]) * DEGREE),
                            omni.orElseThrow().rotation().orElseThrow());
                }
                case "rwp" -> assertEquals(packing(fields), omni.orElseThrow().regionWisePacking().orElseThrow());
                case "viewport" -> {
                    var id = Integer.parseInt(fields[1].substring(3));
                    if (id >= 512) {
                        // a reserved identifier: the message is ignored, the earlier one keeps applying
                        assertEquals(VIEWPORTS, omni.orElseThrow().viewports().orElseThrow());
                        break;
                    }
                    var viewports = new ArrayList<Viewports.Viewport>();
                    for (var i = 4; i < fields.length; i++) {
                        var v = fields[i].substring(3).split(",");
                        viewports.add(new Viewports.Viewport(Integer.parseInt(v[0]) * DEGREE,
                                Integer.parseInt(v[1]) * DEGREE, Integer.parseInt(v[2]) * DEGREE,
                                Long.parseLong(v[3]) * DEGREE, Long.parseLong(v[4]) * DEGREE));
                    }
                    assertEquals(new Viewports(id, viewports), omni.orElseThrow().viewports().orElseThrow());
                }
                default -> throw new AssertionError(line);
            }
            checked++;
        }
        assertEquals(17, checked, "messages VVdeC reports");
    }

    private static Equirectangular.GuardBands guardBands(String field) {
        var v = field.substring(6).split(",");
        return new Equirectangular.GuardBands(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
    }

    private static GeneralizedCubemap cubemap(String[] fields) {
        var packing = Integer.parseInt(fields[3].substring(8));
        var mapping = Integer.parseInt(fields[4].substring(8));
        var faces = new ArrayList<GeneralizedCubemap.Face>();
        Optional<GeneralizedCubemap.CubemapGuardBand> guard = Optional.empty();
        for (var i = 5; i < fields.length; i++) {
            if (fields[i].startsWith("guard=")) {
                var v = fields[i].substring(6).split(",");
                guard = Optional.of(new GeneralizedCubemap.CubemapGuardBand(Integer.parseInt(v[0]), v[1].equals("1"),
                        Integer.parseInt(v[2])));
                continue;
            }
            var v = fields[i].substring(3).split(",");
            faces.add(new GeneralizedCubemap.Face(Integer.parseInt(v[0]), Integer.parseInt(v[1]),
                    Integer.parseInt(v[2]), v[3].equals("1"), Integer.parseInt(v[4]), v[5].equals("1")));
        }
        return new GeneralizedCubemap(packing, mapping, faces, guard);
    }

    private static RegionWisePacking packing(String[] fields) {
        var matching = fields[3].equals("matching=1");
        var projected = fields[4].substring(5).split("x");
        var packed = fields[5].substring(7).split("x");
        var regions = new ArrayList<RegionWisePacking.Region>();
        for (var i = 6; i < fields.length; i++) {
            var parts = fields[i].substring(fields[i].indexOf('=') + 1).split(",guard=");
            var v = parts[0].split(",");
            Optional<RegionWisePacking.RegionGuardBands> bands = Optional.empty();
            if (parts.length > 1) {
                var g = parts[1].split(",");
                bands = Optional.of(new RegionWisePacking.RegionGuardBands(Integer.parseInt(g[0]),
                        Integer.parseInt(g[1]), Integer.parseInt(g[2]), Integer.parseInt(g[3]), g[4].equals("1"),
                        List.of(Integer.parseInt(g[5]), Integer.parseInt(g[6]), Integer.parseInt(g[7]),
                                Integer.parseInt(g[8]))));
            }
            regions.add(new RegionWisePacking.Region(Integer.parseInt(v[0]), Long.parseLong(v[1]),
                    Long.parseLong(v[2]), Long.parseLong(v[3]), Long.parseLong(v[4]), Integer.parseInt(v[5]),
                    Integer.parseInt(v[6]), Integer.parseInt(v[7]), Integer.parseInt(v[8]), bands));
        }
        return new RegionWisePacking(matching, Long.parseLong(projected[0]), Long.parseLong(projected[1]),
                Integer.parseInt(packed[0]), Integer.parseInt(packed[1]), regions);
    }

    @Test
    @DisplayName("ignores the frame packing arrangement types H.274 reserves")
    void reservedFramePacking() {
        // a checkerboard arrangement (type 0) is valid in H.264 and H.265 but reserved in H.274
        var checkerboard = VvcSeiTest.sei(45, SeiWriter.framePacking(true, 0, 0, false, 1, false, false, false, false,
                false, false, new int[]{0, 0, 0, 0}, 1, false));
        var sideBySide = VvcSeiTest.sei(45, SeiWriter.framePacking(true, 0, 3, false, 1, false, false, false, false,
                false, false, new int[]{0, 0, 0, 0}, 0, false));
        var frames = decode(VvcSeiTest.insert(VvcSeiTest.stream("ld-420-8bit"),
                picture -> picture == 0 ? List.of(checkerboard) : picture == 1 ? List.of(sideBySide) : List.of()));
        assertTrue(frames.get(0).framePacking().isEmpty());
        assertEquals(FramePacking.Arrangement.SIDE_BY_SIDE, frames.get(1).framePacking().orElseThrow().arrangement());
        assertTrue(frames.get(2).framePacking().isEmpty());
    }

    @Test
    @DisplayName("ends the ambient viewing environment with its coded layer video sequence")
    void ambientEndsWithSequence() {
        var ambient = VvcSeiTest.sei(148, new SeiWriter.Bits().u(32, 2_000_000).u(16, 15_635).u(16, 16_450).bytes());
        var stream = VvcSeiTest.stream("concat-420-8bit");
        // the second sequence starts at picture 9 (its IDR picture) and carries no message of its own
        var frames = decode(VvcSeiTest.insert(stream, picture -> picture == 0 ? List.of(ambient) : List.of()));
        assertEquals(18, frames.size());
        var expected = new AmbientViewingEnvironment(2_000_000 * 0.0001, new Chromaticity(15_635 * 0.00002,
                16_450 * 0.00002));
        for (var i = 0; i < 9; i++) {
            assertEquals(Optional.of(expected), frames.get(i).ambientViewing(), "frame " + i);
        }
        for (var i = 9; i < 18; i++) {
            assertTrue(frames.get(i).ambientViewing().isEmpty(), "frame " + i);
        }
    }

    @ParameterizedTest(name = "payload type {0}")
    @ValueSource(ints = {148, 149, 150, 153, 154, 155, 156, 204})
    @DisplayName("rejects truncated payloads and never throws on random ones")
    void robustness(int type) {
        var random = new Random(type);
        for (var round = 0; round < 2000; round++) {
            var payload = new byte[random.nextInt(48)];
            random.nextBytes(payload);
            var padded = java.util.Arrays.copyOf(payload, payload.length + H264BitReader.PADDING);
            var reader = new H264BitReader();
            reader.init(padded, 0, payload.length);
            var result = switch (type) {
                case 148 -> H274Sei.ambientViewing(reader);
                case 149 -> H274Sei.contentColorVolume(reader);
                case 150 -> H274Sei.equirectangular(reader);
                case 153 -> H274Sei.generalizedCubemap(reader);
                case 154 -> H274Sei.sphereRotation(reader);
                case 155 -> H274Sei.regionWisePacking(reader);
                case 156 -> H274Sei.omniViewport(reader);
                default -> H274Sei.sampleAspectRatio(reader);
            };
            if (payload.length == 0) {
                assertEquals(null, result, "an empty payload");
            }
        }
        // a whole ambient viewing payload less one byte is truncated
        var whole = new SeiWriter.Bits().u(32, 1).u(16, 2).u(16, 3).bytes();
        var reader = new H264BitReader();
        reader.init(java.util.Arrays.copyOf(whole, whole.length + H264BitReader.PADDING), 0, whole.length - 1);
        assertEquals(null, H274Sei.ambientViewing(reader));
        reader.init(java.util.Arrays.copyOf(whole, whole.length + H264BitReader.PADDING), 0, whole.length);
        assertFalse(H274Sei.ambientViewing(reader) == null);
    }
}
