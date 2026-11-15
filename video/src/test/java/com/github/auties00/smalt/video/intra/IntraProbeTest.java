package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.VideoStreamInfo;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.dnxhd.internal.Vc3Compression;
import com.github.auties00.smalt.video.dnxhd.internal.Vc3Header;
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import com.github.auties00.smalt.video.prores.internal.ProResFrameHeader;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the core prober and the codecs' own frame header parsers on the same ProRes and VC-3
 * frames, for every ProRes flavor and every VC-3 compression ID, and checks that they agree on
 * the size, bit depth, chroma format, profile name and color description.
 */
@DisplayName("ProRes and DNxHD frame headers, probed and parsed")
class IntraProbeTest {
    static final ColorInfo HDR = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
            ColorPrimaries.BT2020, TransferCharacteristics.PQ);

    static Packet encode(VideoEncoder encoder, boolean rgb) {
        var picture = Vp9TestVideo.synthesize(encoder.width(), encoder.height(), 1).getFirst();
        var frame = new VideoFrame(rgb ? ImageOps.toRgba(picture) : picture, 0, 3600, true);
        var packets = new ArrayList<Packet>();
        encoder.encode(frame, packets::add);
        return packets.getFirst();
    }

    static VideoStreamInfo probe(Path directory, Codec codec, VideoEncoder encoder, Packet packet) {
        var file = directory.resolve("intra.mov");
        try (var muxer = new Mp4Muxer(MediaSink.of(file), Mp4MuxerOptions.quickTime())) {
            muxer.addTrack(VideoTrack.builder().id(1).codec(codec).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(encoder.width())
                    .height(encoder.height()).build());
            muxer.writePacket(packet.withTrackId(1));
            muxer.finish();
        }
        com.github.auties00.smalt.testkit.validate.Validators.assertValid(file);
        return Media.probe(MediaSource.of(file)).videoStreams().getFirst();
    }

    static Stream<Arguments> proresCases() {
        var cases = new ArrayList<Arguments>();
        for (var profile : ProResProfile.values()) {
            cases.add(Arguments.of(profile, FieldOrder.PROGRESSIVE, false));
            cases.add(Arguments.of(profile, FieldOrder.TOP_FIRST, true));
        }
        return cases.stream();
    }

    static String proresName(ProResProfile profile) {
        return switch (profile) {
            case PROXY -> "Proxy";
            case LT -> "LT";
            case STANDARD -> "Standard";
            case HQ -> "HQ";
            case PRORES_4444 -> "4444";
            case PRORES_4444_XQ -> "XQ";
        };
    }

    @ParameterizedTest(name = "{0} {1} rgb {2}")
    @MethodSource("proresCases")
    @DisplayName("agree on every ProRes flavor")
    void prores(ProResProfile profile, FieldOrder order, boolean rgb, @TempDir Path directory) {
        var options = ProResEncoderOptions.of(profile).toBuilder().fieldOrder(order).color(HDR).threads(1).build();
        try (var encoder = new ProResEncoder(64, 48, options)) {
            var packet = encode(encoder, rgb);
            var video = probe(directory, Codec.PRORES, encoder, packet);
            var data = packet.data();
            var header = ProResFrameHeader.parse(data, 0, data.length);
            var picture = new ProResDecoder().decodeFrame(data, 0, data.length);
            assertEquals(header.width(), video.width());
            assertEquals(header.height(), video.height());
            assertEquals(picture.width(), video.width());
            assertEquals(picture.height(), video.height());
            assertEquals(OptionalInt.of(picture.bitDepth()), video.bitDepth());
            assertEquals(header.chromaFormat() == 3 ? ChromaFormat.YUV444 : ChromaFormat.YUV422,
                    video.chromaFormat().orElseThrow());
            assertEquals(profile.chromaFormat(), video.chromaFormat().orElseThrow());
            assertEquals(proresName(ProResProfile.ofConfig(encoder.codecConfig().orElseThrow()).orElseThrow()),
                    video.profile().orElseThrow());
            var color = video.color().orElseThrow();
            assertEquals(header.matrix(), color.matrix().code());
            assertEquals(header.primaries(), color.primaries().code());
            assertEquals(header.transfer(), color.transfer().code());
            assertEquals(ColorRange.LIMITED, color.range());
            if (rgb) {
                assertEquals(HDR.matrix(), color.matrix());
            }
        }
    }

    static Stream<Arguments> dnxhdCases() {
        var cases = new ArrayList<Arguments>();
        for (var compression : Vc3Compression.ALL) {
            cases.add(Arguments.of(compression.cid(), compression.cid() == 1256));
        }
        cases.add(Arguments.of(1270, true));
        return cases.stream();
    }

    static DnxhdEncoder dnxhd(Vc3Compression compression, boolean rgb) {
        if (compression.resolutionIndependent()) {
            var profile = switch (compression.cid()) {
                case 1270 -> DnxhdProfile.DNXHR_444;
                case 1271 -> DnxhdProfile.DNXHR_HQX;
                case 1272 -> DnxhdProfile.DNXHR_HQ;
                case 1273 -> DnxhdProfile.DNXHR_SQ;
                default -> DnxhdProfile.DNXHR_LB;
            };
            return new DnxhdEncoder(64, 48, DnxhdEncoderOptions.of(profile).toBuilder().rgb(rgb).threads(1).build());
        }
        var depth = Integer.numberOfTrailingZeros(compression.depths());
        var order = compression.interlaced() ? FieldOrder.TOP_FIRST : FieldOrder.PROGRESSIVE;
        var options = DnxhdEncoderOptions.of(DnxhdProfile.DNXHD).toBuilder().compressionId(compression.cid())
                .bitDepth(depth).fieldOrder(order).rgb(rgb).threads(1).build();
        return new DnxhdEncoder(compression.width(), compression.lines() * compression.codingUnits(), options);
    }

    @ParameterizedTest(name = "ID {0} rgb {1}")
    @MethodSource("dnxhdCases")
    @DisplayName("agree on every VC-3 compression ID")
    void dnxhd(int cid, boolean rgb, @TempDir Path directory) {
        var compression = Vc3Compression.of(cid).orElseThrow();
        try (var encoder = dnxhd(compression, rgb)) {
            assertEquals(cid, encoder.compressionId());
            var packet = encode(encoder, rgb);
            var video = probe(directory, Codec.DNXHD, encoder, packet);
            var data = packet.data();
            var header = Vc3Header.parse(data, 0, data.length);
            assertEquals(header.width(), video.width());
            assertEquals(header.lines() * header.fieldCount(), video.height());
            assertEquals(OptionalInt.of(header.depth()), video.bitDepth());
            var chroma = switch (header.subsampling()) {
                case 1 -> ChromaFormat.YUV420;
                case 2 -> ChromaFormat.YUV444;
                default -> ChromaFormat.YUV422;
            };
            assertEquals(chroma, video.chromaFormat().orElseThrow());
            var name = switch (cid) {
                case 1270 -> "DNXHR 444";
                case 1271 -> "DNXHR HQX";
                case 1272 -> "DNXHR HQ";
                case 1273 -> "DNXHR SQ";
                case 1274 -> "DNXHR LB";
                default -> "DNXHD";
            };
            assertEquals(name, video.profile().orElseThrow());
            assertEquals(header.rgb(), rgb);
            if (header.rgb()) {
                assertEquals(MatrixCoefficients.IDENTITY, video.color().orElseThrow().matrix());
            } else {
                assertTrue(video.color().map(color -> color.matrix() != MatrixCoefficients.IDENTITY).orElse(true));
            }
        }
    }
}
