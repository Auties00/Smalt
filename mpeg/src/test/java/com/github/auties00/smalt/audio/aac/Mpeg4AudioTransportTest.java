package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Decodes the transport conformance streams of ISO/IEC 14496-26 2nd edition, ADTS ({@code compressedAdts}), ADIF
 * ({@code compressedAdif}) and LOAS/LATM ({@code compressedAss}, both {@code audioMuxVersion} values, read through
 * the LOAS demuxer past the header of the files), through {@link Containers} and the registered decoders: each must decode to exactly the samples of the MP4 file of the same
 * stream in {@code compressedMp4}, its transport aside the same access units.
 */
@DisplayName("ISO/IEC 14496-26 transport streams")
class Mpeg4AudioTransportTest {
    record Member(String set, String path) {
        String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        /** The MP4 file of the same stream: the name without its {@code _amv} suffix. */
        String twin() {
            var base = name().substring(0, name().lastIndexOf('.'));
            return base.replaceFirst("_amv[01]$", "") + ".mp4";
        }

        @Override
        public String toString() {
            return name();
        }
    }

    static Stream<Member> members() {
        var result = new ArrayList<Member>();
        for (var set : List.of("mpeg4audio-ed2-adts", "mpeg4audio-ed2-adif", "mpeg4audio-ed2-loas")) {
            for (var member : Conformance.archive(set).members()) {
                var candidate = new Member(set, member);
                // the curation keeps the transport file of a few streams whose MP4 file it leaves out
                if (Mpeg4AudioVectors.exists(candidate.twin())) {
                    result.add(candidate);
                }
            }
        }
        return result.stream();
    }

    /**
     * Returns the AudioSyncStream of a {@code compressedAss} file: the files start with a header of their own, the
     * extension of the stream's MP4 file, a line feed and {@code ASS} followed by a zero byte, before the LOAS sync
     * word.
     */
    static byte[] audioSyncStream(Path file) {
        var data = com.github.auties00.smalt.testkit.Fixtures.read(file);
        var marker = new byte[]{'\n', 'A', 'S', 'S', 0};
        for (var i = 0; i + marker.length <= Math.min(data.length, 64); i++) {
            if (Arrays.equals(data, i, i + marker.length, marker, 0, marker.length)) {
                return Arrays.copyOfRange(data, i + marker.length, data.length);
            }
        }
        throw new AssertionError(file + " has no ASS header");
    }

    /** Decodes the first audio track of a file to one plane per channel. */
    static float[][] decode(Path file) {
        var chunks = new ArrayList<float[][]>();
        try (var demuxer = file.toString().endsWith(".ass")
                ? new com.github.auties00.smalt.container.aac.LoasDemuxer(MediaSource.of(audioSyncStream(file)))
                : Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().stream().filter(candidate -> candidate instanceof AudioTrack)
                    .findFirst().orElseThrow();
            try (var decoder = AudioDecoders.create(track)) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == track.id()) {
                        decoder.decode(packet, buffer -> {
                            var copy = new float[buffer.channels()][];
                            for (var c = 0; c < copy.length; c++) {
                                copy[c] = Arrays.copyOf(buffer.plane(c), buffer.frames());
                            }
                            chunks.add(copy);
                        });
                    }
                }
            }
        }
        assertFalse(chunks.isEmpty(), file + " decoded nothing");
        var channels = chunks.getLast().length;
        var length = chunks.stream().mapToInt(chunk -> chunk[0].length).sum();
        var planes = new float[channels][length];
        var position = 0;
        for (var chunk : chunks) {
            assertEquals(channels, chunk.length, file + " channel count changes");
            for (var c = 0; c < channels; c++) {
                System.arraycopy(chunk[c], 0, planes[c], position, chunk[c].length);
            }
            position += chunk[0].length;
        }
        return planes;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("members")
    @DisplayName("decodes to the samples of the stream's MP4 file")
    void matchesMp4(Member member) {
        var transported = decode(Conformance.archive(member.set()).member(member.path()));
        float[][] reference;
        try {
            reference = decode(Mpeg4AudioVectors.file(member.twin()));
        } catch (MediaException exception) {
            throw new AssertionError(member + ": the MP4 twin " + member.twin() + " fails: " + exception, exception);
        }
        assertEquals(reference.length, transported.length, member + " channels");
        for (var c = 0; c < reference.length; c++) {
            assertArrayEquals(reference[c], transported[c], member + " channel " + c);
        }
    }
}
