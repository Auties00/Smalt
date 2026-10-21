package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the entry points added to {@link Containers} ({@code tryDetect}, {@code openDemuxer},
 * {@code openMuxer} and {@code isMuxable(OutputContainer, Codec)}) and the per-muxer codec
 * predicates they rely on.
 */
@DisplayName("Containers entry points")
class ContainersApiTest {
    private static byte[] wav() {
        var pcm = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 4800);
        return MuxSupport.toMemory(List.of(pcm.track()), pcm.packets(), WavMuxer::new);
    }

    private static byte[] mp3Frame() {
        // MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, no padding: 417 bytes
        var frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) 0x90;
        frame[3] = (byte) 0x44;
        return frame;
    }

    @Nested
    @DisplayName("detect")
    class DetectFormat {
        @Test
        @DisplayName("returns the recognized format")
        void recognized() {
            assertEquals(Optional.of(ContainerFormat.WAV), Containers.detect(MediaSource.of(wav())));
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {"ftyp", "styp", "sidx", "moof", "junk"})
        @DisplayName("recognizes an ISO base media file by its leading box")
        void leadingBox(String box) {
            var file = new byte[64];
            file[3] = 16;
            System.arraycopy(box.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, file, 4, 4);
            assertEquals(Optional.of(ContainerFormat.MP4), Containers.detect(MediaSource.of(file)));
        }

        @Test
        @DisplayName("skips a leading ID3v2 tag before recognizing an MPEG audio stream")
        void id3Prefix() {
            var frame = mp3Frame();
            var file = new byte[10 + 300 + frame.length * 2];
            System.arraycopy(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 2, 44}, 0, file, 0, 10);
            System.arraycopy(frame, 0, file, 310, frame.length);
            System.arraycopy(frame, 0, file, 310 + frame.length, frame.length);
            assertEquals(Optional.of(ContainerFormat.MPEG_AUDIO), Containers.detect(MediaSource.of(file)));
        }

        @Test
        @DisplayName("returns empty for content that is not a container")
        void unknown() {
            assertEquals(Optional.empty(), Containers.detect(MediaSource.of(new byte[300])));
            assertEquals(Optional.empty(), Containers.detect(MediaSource.of(new byte[0])));
        }
    }

    @Nested
    @DisplayName("openDemuxer")
    class OpenDemuxer {
        @Test
        @DisplayName("opens the demuxer of the recognized format")
        void opens() {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(wav()))) {
                assertEquals(ContainerFormat.WAV, demuxer.format());
                assertEquals(1, demuxer.tracks().size());
            }
        }

        @Test
        @DisplayName("enforces the limits of the options")
        void limits() {
            var options = DemuxerOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> Containers.openDemuxer(MediaSource.of(wav()), options));
        }

        @Test
        @DisplayName("rejects content that is not a container")
        void unknown() {
            assertThrows(MediaException.Unsupported.class, () -> Containers.openDemuxer(MediaSource.of(new byte[300])));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("reports running out of stack or memory while reading the structure as a limit")
        void resources(boolean stack) {
            var data = MediaSource.of(wav());
            // every read after the first fails the way a parser recursing without bound, or allocating without
            // bound, fails inside the demuxer
            var source = new MediaSource() {
                private int reads;

                @Override
                public long size() {
                    return data.size();
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    if (reads++ > 0) {
                        throw stack ? new StackOverflowError() : new OutOfMemoryError();
                    }
                    return data.read(position, buffer, offset, length);
                }

                @Override
                public void close() {
                }
            };
            var exception = assertThrows(MediaException.LimitExceeded.class, () -> Containers.openDemuxer(source));
            assertEquals(stack ? "container nests too deeply" : "container needs more memory than is available",
                    exception.getMessage());
        }
    }

    @Nested
    @DisplayName("openMuxer")
    class OpenMuxer {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ContainerFormat.class)
        @DisplayName("creates a muxer for every format")
        void format(ContainerFormat format) {
            try (var muxer = Containers.openMuxer(format, MediaSink.memory())) {
                assertNotNull(muxer);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(OutputContainer.class)
        @DisplayName("creates the muxer of an output profile")
        void profile(OutputContainer container) {
            try (var muxer = Containers.openMuxer(container, MediaSink.memory())) {
                switch (container) {
                    case MP4, M4A, MOV -> assertInstanceOf(Mp4Muxer.class, muxer);
                    case FRAGMENTED_MP4 -> assertInstanceOf(FragmentedMp4Muxer.class, muxer);
                    case MATROSKA, WEBM -> assertInstanceOf(MatroskaMuxer.class, muxer);
                    // the profiles narrower than their format's muxer reject the other codecs
                    case OGG_FLAC, AC3, EAC3 -> assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(
                            com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.PCM_S16LE)
                                    .timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000).layout(ChannelLayout.STEREO)
                                    .build()));
                    default -> {
                        try (var expected = Containers.openMuxer(container.format(), MediaSink.memory())) {
                            assertEquals(expected.getClass(), muxer.getClass());
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("lets an MPEG audio file carry tags")
        void rawTags() {
            var track = com.github.auties00.smalt.track.AudioTrack.builder().id(1).codec(Codec.MP3)
                    .timeBase(Rational.timeBaseOf(44_100)).sampleRate(44_100).layout(ChannelLayout.STEREO).build();
            var sink = MediaSink.memory();
            try (var muxer = Containers.openMuxer(ContainerFormat.MPEG_AUDIO, sink)) {
                muxer.addTrack(track);
                assertTrue(muxer.supportsTags());
                muxer.setTags(com.github.auties00.smalt.metadata.MediaTags.builder().add("title", "T").build());
                muxer.writePacket(new com.github.auties00.smalt.track.Packet(1, mp3Frame(), 0, 0, 1152, true));
                muxer.finish();
            }
            var file = sink.toByteArray();
            assertEquals("ID3", new String(file, 0, 3, java.nio.charset.StandardCharsets.US_ASCII));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals("T", demuxer.metadata().get("TIT2"));
            }
        }

        @Test
        @DisplayName("applies the brand of the profile")
        void brand() {
            var video = VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(Rational.MILLISECONDS).width(16).height(16).build();
            try (var m4a = Containers.openMuxer(OutputContainer.M4A, MediaSink.memory());
                 var mp4 = Containers.openMuxer(OutputContainer.MP4, MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> m4a.addTrack(video));
                mp4.addTrack(video);
            }
        }
    }

    @Nested
    @DisplayName("codec predicates")
    class Predicates {
        @ParameterizedTest(name = "{0}")
        @EnumSource(OutputContainer.class)
        @DisplayName("isMuxable(OutputContainer, Codec) follows OutputContainer.supports")
        void outputContainer(OutputContainer container) {
            for (var codec : Codec.values()) {
                assertEquals(container.supports(codec), Containers.isMuxable(container, codec), codec.name());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Codec.class)
        @DisplayName("isMuxable(ContainerFormat, Codec) keeps its answers for MP4, Ogg and the elementary streams")
        void formats(Codec codec) {
            assertEquals(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, codec), Containers.isMuxable(ContainerFormat.MP4, codec));
            assertEquals(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, codec) || codec == Codec.PRORES || codec == Codec.DNXHD,
                    FragmentedMp4Muxer.supports(codec));
            assertEquals(codec == Codec.OPUS || codec == Codec.VORBIS || codec == Codec.FLAC || codec == Codec.SPEEX
                    || codec == Codec.THEORA, Containers.isMuxable(ContainerFormat.OGG, codec));
            assertEquals(codec == Codec.MP3 || codec == Codec.MP2, Containers.isMuxable(ContainerFormat.MPEG_AUDIO, codec));
            assertEquals(codec == Codec.FLAC, Containers.isMuxable(ContainerFormat.FLAC, codec));
            assertEquals(codec == Codec.AMR_NB || codec == Codec.AMR_WB, Containers.isMuxable(ContainerFormat.AMR, codec));
            assertEquals(codec == Codec.AC3 || codec == Codec.EAC3, Containers.isMuxable(ContainerFormat.AC3, codec));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Codec.class)
        @DisplayName("Mp4Muxer.supports narrows by brand")
        void mp4Brands(Codec codec) {
            var mp4 = Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, codec);
            var m4a = Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, codec);
            var quickTime = Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, codec);
            if (m4a) {
                assertTrue(mp4 && quickTime && codec.kind() == TrackKind.AUDIO);
            }
            if (mp4) {
                assertTrue(quickTime);
            }
            // ISO files have no sample entry for ProRes, DNxHD, 8-bit PCM and G.711
            assertEquals(codec == Codec.PRORES || codec == Codec.DNXHD || codec == Codec.PCM_U8 || codec == Codec.PCM_S8
                    || codec == Codec.PCM_ALAW || codec == Codec.PCM_MULAW, quickTime && !mp4);
        }

        @Test
        @DisplayName("OggMuxer and RawMuxer name their codecs")
        void oggAndRaw() {
            assertTrue(OggMuxer.supports(Codec.OPUS));
            assertTrue(OggMuxer.supports(Codec.VORBIS));
            assertTrue(OggMuxer.supports(Codec.FLAC));
            assertFalse(OggMuxer.supports(Codec.AAC));
            assertTrue(RawMuxer.supports(ContainerFormat.AMR, Codec.AMR_WB));
            assertFalse(RawMuxer.supports(ContainerFormat.AMR, Codec.MP3));
            assertFalse(RawMuxer.supports(ContainerFormat.WAV, Codec.PCM_S16LE));
        }
    }
}
