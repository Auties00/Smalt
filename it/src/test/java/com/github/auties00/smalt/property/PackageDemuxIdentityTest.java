package com.github.auties00.smalt.property;

import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.dash.DashDemuxer;
import com.github.auties00.smalt.container.hls.HlsDemuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.packaging.PackageOutput;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.container.packaging.Rendition;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaging then reading back preserves every packet, for every codec a segment format carries: a stream of each
 * codec, written to a {@code PackageWriter} as an HLS presentation of transport stream segments (read back with the
 * HLS demuxer) and as an HLS and DASH presentation of CMAF segments (read back with both demuxers), comes back with
 * its packets in order, with the same bytes (Annex B access units for H.264 and HEVC, as {@link MuxDemuxIdentityTest}
 * compares them), key frame flags and timestamps, behind a track of the same codec, size, rate and channel count.
 * CMAF video measures its composition offsets from the first sample's (negative ones where needed), so its decoding
 * times are checked to increase and to precede the presentation times, which are exact.
 */
@DisplayName("Package then demux")
class PackageDemuxIdentityTest {
    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        for (var codec : Catalog.allCodecs()) {
            if (codec.kind() == TrackKind.DATA) {
                continue;
            }
            if (TsMuxer.supports(codec)) {
                arguments.add(Arguments.of("hls-ts", codec));
            }
            if (FragmentedMp4Muxer.supports(codec)) {
                arguments.add(Arguments.of("hls-cmaf", codec));
                arguments.add(Arguments.of("dash", codec));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} carrying {1}")
    @MethodSource("cases")
    @DisplayName("returns the packets of one track unchanged")
    void single(String layout, Codec codec) {
        var stream = Catalog.stream(codec);
        var options = switch (layout) {
            case "hls-ts" -> PackagingOptions.hls();
            case "hls-cmaf" -> PackagingOptions.hlsCmaf();
            default -> PackagingOptions.dash();
        };
        var files = new LinkedHashMap<String, byte[]>();
        try (var writer = Packager.open(List.of(Rendition.builder(List.of(stream.track())).name("r").build()),
                output(files), options.toBuilder().segmentMicros(200_000).noTags().build())) {
            for (var packet : stream.packets()) {
                writer.writePacket("r", packet);
            }
            writer.finish();
        }
        DataReferenceResolver resolver = reference -> Optional.ofNullable(files.get(reference.location()))
                .map(MediaSource::of);
        var demuxerOptions = DemuxerOptions.builder().dataReferences(resolver).build();
        var manifest = layout.equals("dash") ? files.get("manifest.mpd") : files.get("master.m3u8");
        try (var demuxer = layout.equals("dash") ? new DashDemuxer(MediaSource.of(manifest), demuxerOptions)
                : new HlsDemuxer(MediaSource.of(manifest), demuxerOptions)) {
            var tracks = demuxer.tracks();
            assertEquals(1, tracks.size(), layout + " " + codec + " tracks " + tracks);
            List<Packet> packets = new ArrayList<>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            var container = layout.equals("hls-ts") ? OutputContainer.MPEG_TS : OutputContainer.FRAGMENTED_MP4;
            var what = layout + " " + codec + " (" + new String(manifest, StandardCharsets.UTF_8).length()
                    + "-byte manifest)";
            var read = tracks.getFirst();
            MuxDemuxIdentityTest.assertTrack(what, stream.track(), read);
            if (!layout.equals("hls-ts") && codec.kind() == TrackKind.VIDEO
                    && packets.size() == stream.packets().size()) {
                var tick = Math.max(stream.track().timeBase().toMicros(1), read.timeBase().toMicros(1)) + 1;
                var previous = Long.MIN_VALUE;
                var retimed = new ArrayList<Packet>();
                for (var index = 0; index < packets.size(); index++) {
                    var got = packets.get(index);
                    var dts = read.timeBase().toMicros(got.dts());
                    assertTrue(dts > previous && dts <= read.timeBase().toMicros(got.pts()) + tick, what + " packet "
                            + index + " dts " + dts + " us after " + previous + " us");
                    previous = dts;
                    // the decoding times checked, the comparison takes the written ones
                    var want = stream.packets().get(index);
                    retimed.add(got.withTiming(got.pts(), want.dts() == Packet.NO_TIMESTAMP ? got.dts()
                            : stream.track().timeBase().rescale(want.dts(), read.timeBase()), got.duration()));
                }
                packets = retimed;
            }
            MuxDemuxIdentityTest.assertPackets(what, container, stream, read, packets);
        }
    }

    /** Returns an output that keeps the files in memory. */
    private static PackageOutput output(Map<String, byte[]> files) {
        return name -> new MediaSink() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            @Override
            public long position() {
                return bytes.size();
            }

            @Override
            public void position(long position) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isSeekable() {
                return false;
            }

            @Override
            public void write(ByteBuffer data) {
                var copy = new byte[data.remaining()];
                data.get(copy);
                bytes.writeBytes(copy);
            }

            @Override
            public void close() {
                files.put(name, bytes.toByteArray());
            }
        };
    }
}
