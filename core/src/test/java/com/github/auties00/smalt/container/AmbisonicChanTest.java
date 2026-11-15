package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.caf.CafDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.container.caf.CafMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the Core Audio channel layouts of ambisonic sound fields in CAF {@code chan} chunks and QuickTime
 * {@code chan} atoms: the higher-order ambisonics layout tags ({@code 190 << 16} SN3D, {@code 191 << 16} N3D, ORed
 * with the channel count), the B-format tag ({@code 107 << 16 | 4}), and the HOA ({@code 2 << 16} SN3D,
 * {@code 3 << 16} N3D, ORed with the ACN) and B-format (200 to 203) channel labels, the values of Apple's
 * {@code CoreAudioBaseTypes.h}.
 */
@DisplayName("ambisonic Core Audio channel layouts")
class AmbisonicChanTest {
    private static final int FRAMES = 480;

    static Stream<ChannelLayout> layouts() {
        var headLocked = new ArrayList<Speaker>(Collections.nCopies(4, Speaker.UNKNOWN));
        headLocked.addAll(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));
        return Stream.of(
                ChannelLayout.ofAmbisonics(1),
                ChannelLayout.ofAmbisonics(3),
                ChannelLayout.ofAmbisonics(Ambisonics.of(2, Ambisonics.Normalization.N3D)),
                ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4)),
                ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(3, 1, 2, 0))),
                ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.N3D, List.of(0, 1, 2, 3, 6))),
                ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.FUMA, List.of(1, 2, 3, 0))),
                ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)),
                new ChannelLayout(headLocked, Optional.of(Ambisonics.of(1, Ambisonics.Normalization.N3D))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    @DisplayName("a CAF file comes back with its sound field")
    void caf(ChannelLayout layout) {
        assertEquals(layout, layout(layout, CafMuxer::new, CafDemuxer::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layouts")
    @DisplayName("a QuickTime file comes back with its sound field")
    void quickTime(ChannelLayout layout) {
        assertEquals(layout, layout(layout, AmbisonicChanTest::quickTime, Mp4Demuxer::new));
    }

    @Nested
    @DisplayName("chan bodies")
    class Bodies {
        @Test
        @DisplayName("full ACN sound fields get the HOA tags, Furse-Malham W X Y Z the B-format tag")
        void tags() {
            assertEquals(190 << 16 | 4, cafChan(ChannelLayout.ofAmbisonics(1)).getInt(0));
            assertEquals(190 << 16 | 16, cafChan(ChannelLayout.ofAmbisonics(3)).getInt(0));
            assertEquals(191 << 16 | 9,
                    cafChan(ChannelLayout.ofAmbisonics(Ambisonics.of(2, Ambisonics.Normalization.N3D))).getInt(0));
            assertEquals(107 << 16 | 4, cafChan(ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(4))).getInt(0));
            assertEquals(190 << 16 | 4, movChan(ChannelLayout.ofAmbisonics(1)).getInt(0));
            assertEquals(191 << 16 | 25,
                    movChan(ChannelLayout.ofAmbisonics(Ambisonics.of(4, Ambisonics.Normalization.N3D))).getInt(0));
        }

        @Test
        @DisplayName("other sound fields label each channel with its component")
        void labels() {
            var sn3d = ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D, List.of(3, 1, 2, 0)));
            assertEquals(List.of(2 << 16 | 3, 2 << 16 | 1, 2 << 16 | 2, 2 << 16), labelsOf(cafChan(sn3d)));
            assertEquals(List.of(2 << 16 | 3, 2 << 16 | 1, 2 << 16 | 2, 2 << 16), labelsOf(movChan(sn3d)));
            var n3d = ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D),
                    List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));
            assertEquals(List.of(3 << 16, 3 << 16 | 1, 3 << 16 | 2, 3 << 16 | 3, 1, 2), labelsOf(cafChan(n3d)));
            // Furse-Malham Y Z X W
            var fuma = ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.FUMA, List.of(1, 2, 3, 0)));
            assertEquals(List.of(202, 203, 201, 200), labelsOf(cafChan(fuma)));
            assertEquals(List.of(202, 203, 201, 200), labelsOf(movChan(fuma)));
        }

        @Test
        @DisplayName("a second-order Furse-Malham component, which no label names, is labelled unknown")
        void unlabelled() {
            var fuma = ChannelLayout.ofAmbisonics(Ambisonics.ofFurseMalham(9));
            assertEquals(List.of(200, 201, 202, 203, -1, -1, -1, -1, -1), labelsOf(cafChan(fuma)));
            assertEquals(new ChannelLayout(Collections.nCopies(9, Speaker.UNKNOWN)),
                    layout(fuma, CafMuxer::new, CafDemuxer::new), "unknown labels name no component");
        }

        @Test
        @DisplayName("mixed normalizations, a component twice and an unknown channel beside them give no sound field")
        void inconsistent() {
            var unknown = new ChannelLayout(Collections.nCopies(4, Speaker.UNKNOWN));
            assertEquals(unknown, cafLayout(new int[]{2 << 16, 2 << 16 | 1, 3 << 16 | 2, 2 << 16 | 3}));
            assertEquals(unknown, cafLayout(new int[]{2 << 16, 2 << 16 | 1, 2 << 16 | 1, 2 << 16 | 3}));
            assertEquals(unknown, cafLayout(new int[]{2 << 16, 2 << 16 | 1, 2 << 16 | 2, -1}));
            assertEquals(unknown, cafLayout(new int[]{2 << 16, 2 << 16 | 1, 2 << 16 | 2, 500}),
                    "the generic HOA label names no component");
            assertEquals(unknown, cafLayout(new int[]{2 << 16, 2 << 16 | 1, 2 << 16 | 2, 2 << 16 | 65024}),
                    "a component above order 14");
            assertEquals(new ChannelLayout(List.of(Speaker.UNKNOWN, Speaker.FRONT_LEFT),
                            Optional.of(new Ambisonics(Ambisonics.Normalization.FUMA, List.of(0)))),
                    cafLayout(new int[]{200, 1}), "W alone beside a speaker is a sound field");
        }

        @Test
        @DisplayName("an HOA tag of a count that is not a full order gives no layout")
        void oddTag() {
            for (var count : new int[]{3, 5, 15, 17, 63}) {
                var body = ByteBuffer.allocate(12).putInt(190 << 16 | count).array();
                var layout = cafTagLayout(body, count);
                assertEquals(new ChannelLayout(Collections.nCopies(count, Speaker.UNKNOWN)).channels(),
                        layout.channels());
                assertEquals(Optional.empty(), layout.ambisonics(), count + " channels");
            }
        }
    }

    @Test
    @DisplayName("the committed fuzz seeds read with their sound fields")
    void seeds() throws java.io.IOException {
        var seeds = java.nio.file.Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "fuzz",
                "DemuxerFuzzTestInputs");
        try (var mov = new Mp4Demuxer(MediaSource.of(java.nio.file.Files.readAllBytes(
                seeds.resolve("mp4/valid-ambisonic-chan.mov"))))) {
            assertEquals(4, ((AudioTrack) mov.tracks().getFirst()).layout().orElseThrow().ambisonics().orElseThrow()
                    .channels());
        }
        try (var caf = new CafDemuxer(MediaSource.of(java.nio.file.Files.readAllBytes(
                seeds.resolve("caf/valid-ambisonic-labels.caf"))))) {
            assertEquals(List.of(3, 1, 2, 0), ((AudioTrack) caf.tracks().getFirst()).layout().orElseThrow()
                    .ambisonics().orElseThrow().components());
        }
    }

    /**
     * Returns the layout of a file written and read again.
     */
    private static ChannelLayout layout(ChannelLayout layout, Function<MediaSink, Muxer> muxer,
                                        Function<MediaSource, Demuxer> demuxer) {
        var bytes = write(layout, muxer);
        try (var reader = demuxer.apply(MediaSource.of(bytes))) {
            return ((AudioTrack) reader.tracks().stream().filter(track -> track.kind() == TrackKind.AUDIO)
                    .findFirst().orElseThrow()).layout().orElseThrow();
        }
    }

    private static byte[] write(ChannelLayout layout, Function<MediaSink, Muxer> muxer) {
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000))
                .sampleRate(48000).layout(layout).build();
        var packet = new Packet(1, new byte[2 * layout.channels() * FRAMES], 0, 0, FRAMES, true);
        return MuxSupport.toMemory(List.of(track), List.of(packet), muxer);
    }

    private static Muxer quickTime(MediaSink sink) {
        return new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.QUICKTIME).build());
    }

    /** Returns the AudioChannelLayout of the {@code chan} chunk of a CAF file of a layout. */
    private static ByteBuffer cafChan(ChannelLayout layout) {
        var file = write(layout, CafMuxer::new);
        var at = indexOf(file, "chan");
        var size = (int) ByteBuffer.wrap(file).getLong(at + 4);
        return ByteBuffer.wrap(file, at + 12, size).slice();
    }

    /** Returns the AudioChannelLayout of the {@code chan} atom of a QuickTime file of a layout. */
    private static ByteBuffer movChan(ChannelLayout layout) {
        var file = write(layout, AmbisonicChanTest::quickTime);
        var at = indexOf(file, "chan");
        var size = ByteBuffer.wrap(file).getInt(at - 4);
        return ByteBuffer.wrap(file, at + 8, size - 12).slice();
    }

    private static List<Integer> labelsOf(ByteBuffer chan) {
        assertEquals(0, chan.getInt(0), "a layout described by labels");
        var labels = new ArrayList<Integer>();
        for (var index = 0; index < chan.getInt(8); index++) {
            labels.add(chan.getInt(12 + 20 * index));
        }
        return labels;
    }

    /** Returns the layout CAF reads for a {@code chan} chunk describing every channel by a label. */
    private static ChannelLayout cafLayout(int[] labels) {
        var body = ByteBuffer.allocate(12 + 20 * labels.length);
        body.putInt(0).putInt(0).putInt(labels.length);
        for (var label : labels) {
            body.putInt(label).put(new byte[16]);
        }
        return cafTagLayout(body.array(), labels.length);
    }

    /** Returns the layout of a CAF file whose {@code chan} chunk is replaced. */
    private static ChannelLayout cafTagLayout(byte[] chan, int channels) {
        var file = write(new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN)), CafMuxer::new);
        var at = indexOf(file, "chan");
        var size = (int) ByteBuffer.wrap(file).getLong(at + 4);
        var patched = new byte[file.length - size + chan.length];
        System.arraycopy(file, 0, patched, 0, at + 4);
        ByteBuffer.wrap(patched).putLong(at + 4, chan.length);
        System.arraycopy(chan, 0, patched, at + 12, chan.length);
        System.arraycopy(file, at + 12 + size, patched, at + 12 + chan.length, file.length - at - 12 - size);
        try (var reader = new CafDemuxer(MediaSource.of(patched))) {
            return ((AudioTrack) reader.tracks().getFirst()).layout().orElseThrow();
        }
    }

    private static int indexOf(byte[] data, String type) {
        var pattern = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("no " + type);
    }
}
