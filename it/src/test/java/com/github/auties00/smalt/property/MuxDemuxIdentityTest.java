package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Muxing then demuxing preserves every packet, for every container Smalt writes and every codec it can carry: the
 * demuxer returns, per track, the packets the muxer was given, in the same order, with the same bytes, key frame
 * flags and timestamps (to the precision of the container's clock), behind a track of the same codec, size, rate
 * and channel count. Multi-track containers are also checked with a video and an audio track interleaved.
 */
@DisplayName("Mux then demux")
class MuxDemuxIdentityTest {
    /** The elementary video streams Smalt writes but cannot read back. */
    private static final Set<OutputContainer> WRITE_ONLY = EnumSet.of(OutputContainer.H264, OutputContainer.HEVC,
            OutputContainer.AV1);

    static Stream<Arguments> singleTrack() {
        var arguments = new ArrayList<Arguments>();
        for (var container : OutputContainer.values()) {
            if (WRITE_ONLY.contains(container)) {
                continue;
            }
            for (var codec : Catalog.allCodecs()) {
                if (container.supports(codec)) {
                    arguments.add(Arguments.of(container, codec));
                }
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> twoTracks() {
        var arguments = new ArrayList<Arguments>();
        for (var container : OutputContainer.values()) {
            if (WRITE_ONLY.contains(container)) {
                continue;
            }
            var video = Catalog.smaltCodecs().stream()
                    .filter(codec -> codec.kind() == TrackKind.VIDEO && container.supports(codec)).findFirst();
            var audio = Catalog.smaltCodecs().stream()
                    .filter(codec -> codec.kind() == TrackKind.AUDIO && container.supports(codec)).findFirst();
            if (video.isPresent() && audio.isPresent()) {
                arguments.add(Arguments.of(container, video.get(), audio.get()));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} carrying {1}")
    @MethodSource("singleTrack")
    @DisplayName("returns the packets of one track unchanged")
    void single(OutputContainer container, Codec codec) {
        var stream = Catalog.stream(codec);
        assertRoundTrip(container, List.of(stream));
    }

    @ParameterizedTest(name = "{0} carrying {1} and {2}")
    @MethodSource("twoTracks")
    @DisplayName("returns the packets of an interleaved video and audio track unchanged")
    void interleaved(OutputContainer container, Codec video, Codec audio) {
        assertRoundTrip(container, List.of(Catalog.stream(video), Catalog.stream(audio)));
    }

    static void assertRoundTrip(OutputContainer container, List<Streams.Encoded> streams) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(container, sink)) {
            for (var stream : streams) {
                muxer.addTrack(stream.track());
            }
            var all = new ArrayList<Packet>();
            streams.forEach(stream -> all.addAll(stream.packets()));
            all.sort(Comparator.comparingLong(packet -> micros(streams, packet, packet.dts() != Packet.NO_TIMESTAMP
                    ? packet.dts() : packet.pts())));
            all.forEach(muxer::writePacket);
            muxer.finish();
        }
        var data = sink.toByteArray();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            var tracks = demuxer.tracks();
            assertEquals(streams.size(), tracks.size(), container + " track count");
            var read = new ArrayList<List<Packet>>();
            for (var index = 0; index < tracks.size(); index++) {
                read.add(new ArrayList<>());
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                for (var index = 0; index < tracks.size(); index++) {
                    if (tracks.get(index).id() == packet.trackId()) {
                        read.get(index).add(packet);
                    }
                }
            }
            for (var index = 0; index < streams.size(); index++) {
                var original = streams.get(index);
                var demuxed = tracks.stream().filter(track -> track.codec() == original.track().codec()
                                || isPcm(track.codec()) && isPcm(original.track().codec())).findFirst()
                        .orElseThrow(() -> new AssertionError(container + " lost the " + original.track().codec()
                                + " track, read " + tracks));
                var packets = read.get(tracks.indexOf(demuxed));
                var what = container + " " + original.track().codec();
                assertTrack(what, original.track(), demuxed);
                assertPackets(what, container, original, demuxed, packets);
            }
        }
    }

    private static long micros(List<Streams.Encoded> streams, Packet packet, long time) {
        for (var stream : streams) {
            if (stream.track().id() == packet.trackId()) {
                return stream.track().timeBase().toMicros(time);
            }
        }
        throw new AssertionError("packet of unknown track " + packet.trackId());
    }

    static void assertTrack(String what, Track original, Track demuxed) {
        if (!isPcm(original.codec())) {
            assertEquals(original.codec(), demuxed.codec(), what + " codec");
        }
        if (original instanceof VideoTrack video && demuxed instanceof VideoTrack read) {
            assertEquals(video.width(), read.width(), what + " width");
            assertEquals(video.height(), read.height(), what + " height");
        }
        if (original instanceof AudioTrack audio && demuxed instanceof AudioTrack read) {
            assertEquals(audio.sampleRate(), read.sampleRate(), what + " sample rate");
            assertEquals(audio.layout().map(ChannelLayout::channels), read.layout().map(ChannelLayout::channels),
                    what + " channels");
        }
    }

    static void assertPackets(String what, OutputContainer container, Streams.Encoded original, Track demuxed,
                              List<Packet> packets) {
        if (isPcm(original.track().codec())) {
            // PCM has no packets of its own: WAV and AIFF re-chunk it and Matroska stores some layouts in another
            // byte order, so the samples are compared.
            assertArrayEquals(pcm(original.track(), original.packets()), pcm(demuxed, packets), what + " samples");
            return;
        }
        var expected = original.packets();
        assertEquals(expected.size(), packets.size(), what + " packet count");
        var tick = Math.max(original.track().timeBase().toMicros(1), demuxed.timeBase().toMicros(1)) + 1;
        if (container.format() == ContainerFormat.MATROSKA) {
            tick = Math.max(tick, 1001);
        }
        // A container with a timeline of its own is compared from its middle packet on, whose time it does not
        // derive from the stream start (the first Vorbis packet of an Ogg stream decodes to nothing).
        var own = anchorsItsOwnTimeline(container, original.track().codec());
        var anchor = own ? expected.size() / 2 : 0;
        // AVI stores no reordering: both of its timestamps count frames in decoding order.
        var decodeOrder = container.format() == ContainerFormat.AVI && original.track().codec().kind() == TrackKind.VIDEO;
        var offset = demuxed.timeBase().toMicros(packets.get(anchor).pts())
                - original.track().timeBase().toMicros(decodeOrder ? decodeTime(expected.get(anchor))
                : expected.get(anchor).pts());
        own |= decodeOrder;
        if (Math.abs(offset) > tick && !own) {
            throw new AssertionError(what + " moved the first packet by " + offset + " us");
        }
        var nal = original.track().codec() == Codec.H264 || original.track().codec() == Codec.HEVC;
        var config = original.track().codecConfig().orElse(null);
        for (var index = 0; index < expected.size(); index++) {
            var want = expected.get(index);
            var got = packets.get(index);
            var same = nal ? sameSlices(original.track().codec(), config, want.data(), got.data())
                    : Arrays.equals(payload(want), payload(got));
            if (!same) {
                throw new AssertionError(what + " packet " + index + " differs: " + want.size() + " bytes written, "
                        + got.size() + " read");
            }
            assertEquals(want.keyframe(), got.keyframe(), what + " packet " + index + " key frame flag");
            var wantPts = original.track().timeBase().toMicros(decodeOrder ? decodeTime(want) : want.pts()) + offset;
            var gotPts = demuxed.timeBase().toMicros(got.pts());
            if (Math.abs(wantPts - gotPts) > tick && !(own && index == 0 && !decodeOrder)) {
                throw new AssertionError(what + " packet " + index + " pts " + gotPts + " us, expected " + wantPts);
            }
            if (want.dts() != Packet.NO_TIMESTAMP && got.dts() != Packet.NO_TIMESTAMP && !decodeOrder
                    && !(own && index == 0)) {
                var wantDts = original.track().timeBase().toMicros(want.dts()) + offset;
                var gotDts = demuxed.timeBase().toMicros(got.dts());
                if (Math.abs(wantDts - gotDts) > tick) {
                    throw new AssertionError(what + " packet " + index + " dts " + gotDts + " us, expected " + wantDts);
                }
            }
        }
    }

    private static long decodeTime(Packet packet) {
        return packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts();
    }

    /**
     * Returns whether a container stamps a codec's packets on a timeline of its own: Ogg derives every timestamp
     * from granule positions (an Opus stream starting at minus its pre-skip, a Vorbis one ending its first packet at
     * zero), and Matroska stamps an Opus block with its time plus the pre-skip, which it signals in band.
     */
    private static boolean anchorsItsOwnTimeline(OutputContainer container, Codec codec) {
        return container.format() == ContainerFormat.OGG
                || container.format() == ContainerFormat.MATROSKA && codec == Codec.OPUS;
    }

    /**
     * Returns the bytes of a packet with the container-specific framing that carries no information removed: the
     * frame size and {@code icpf} identifier of a ProRes frame, which QuickTime stores and Matroska omits.
     */
    private static byte[] payload(Packet packet) {
        var data = packet.data();
        if (data.length >= 8 && data[4] == 'i' && data[5] == 'c' && data[6] == 'p' && data[7] == 'f') {
            return Arrays.copyOfRange(data, 8, data.length);
        }
        return data;
    }

    private static boolean isPcm(Codec codec) {
        return codec.name().startsWith("PCM_");
    }

    /** Decodes PCM packets into the samples of every channel, one channel after the other. */
    private static float[] pcm(Track track, List<Packet> packets) {
        var channels = new java.util.ArrayList<java.io.ByteArrayOutputStream>();
        try (var decoder = AudioDecoders.create((AudioTrack) track)) {
            java.util.function.Consumer<AudioBuffer> sink = buffer -> {
                while (channels.size() < buffer.channels()) {
                    channels.add(new java.io.ByteArrayOutputStream());
                }
                for (var channel = 0; channel < buffer.channels(); channel++) {
                    var plane = buffer.plane(channel);
                    for (var index = 0; index < buffer.frames(); index++) {
                        var bits = Float.floatToRawIntBits(plane[index]);
                        channels.get(channel).write(bits >>> 24);
                        channels.get(channel).write(bits >>> 16);
                        channels.get(channel).write(bits >>> 8);
                        channels.get(channel).write(bits);
                    }
                }
            };
            packets.forEach(packet -> decoder.decode(packet, sink));
            decoder.flush(sink);
        }
        var all = new java.io.ByteArrayOutputStream();
        channels.forEach(channel -> all.writeBytes(channel.toByteArray()));
        var bytes = all.toByteArray();
        var samples = new float[bytes.length / 4];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = Float.intBitsToFloat((bytes[4 * index] & 0xFF) << 24 | (bytes[4 * index + 1] & 0xFF) << 16
                    | (bytes[4 * index + 2] & 0xFF) << 8 | bytes[4 * index + 3] & 0xFF);
        }
        return samples;
    }

    /**
     * Returns the NAL units of a length-prefixed H.264 or HEVC packet without the access unit delimiters and the
     * copies of the track's parameter sets, which a transport stream or AVI carries in band.
     */
    private static List<byte[]> slices(Codec codec, byte[] config, byte[] data) {
        var parameterSets = configNals(codec, config);
        var units = new ArrayList<byte[]>();
        for (var offset = 0; offset + 4 <= data.length; ) {
            var length = (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                    | data[offset + 3] & 0xFF;
            var unit = Arrays.copyOfRange(data, offset + 4, Math.min(data.length, offset + 4 + length));
            offset += 4 + length;
            var type = codec == Codec.HEVC ? (unit[0] >> 1) & 0x3F : unit[0] & 0x1F;
            var delimiter = codec == Codec.HEVC ? type == 35 : type == 9;
            if (!delimiter && parameterSets.stream().noneMatch(set -> Arrays.equals(set, unit))) {
                units.add(unit);
            }
        }
        return units;
    }

    /** Returns the NAL units of an {@code avcC} or {@code hvcC} record. */
    private static List<byte[]> configNals(Codec codec, byte[] config) {
        var units = new ArrayList<byte[]>();
        if (config == null) {
            return units;
        }
        if (codec == Codec.H264) {
            var offset = 5;
            for (var kind = 0; kind < 2 && offset < config.length; kind++) {
                var count = config[offset++] & (kind == 0 ? 0x1F : 0xFF);
                for (var index = 0; index < count && offset + 2 <= config.length; index++) {
                    var length = (config[offset] & 0xFF) << 8 | config[offset + 1] & 0xFF;
                    units.add(Arrays.copyOfRange(config, offset + 2, offset + 2 + length));
                    offset += 2 + length;
                }
            }
        } else {
            var offset = 22;
            var arrays = config[offset++] & 0xFF;
            for (var array = 0; array < arrays; array++) {
                offset++;
                var count = (config[offset] & 0xFF) << 8 | config[offset + 1] & 0xFF;
                offset += 2;
                for (var index = 0; index < count; index++) {
                    var length = (config[offset] & 0xFF) << 8 | config[offset + 1] & 0xFF;
                    units.add(Arrays.copyOfRange(config, offset + 2, offset + 2 + length));
                    offset += 2 + length;
                }
            }
        }
        return units;
    }

    private static boolean sameSlices(Codec codec, byte[] config, byte[] written, byte[] read) {
        var want = slices(codec, config, written);
        var got = slices(codec, config, read);
        if (want.size() != got.size()) {
            return false;
        }
        for (var index = 0; index < want.size(); index++) {
            if (!Arrays.equals(want.get(index), got.get(index))) {
                return false;
            }
        }
        return true;
    }
}
