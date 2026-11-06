package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.h263.H263Decoder;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the {@link TrainingFamily#MPEG} workloads of the coding tools no Smalt encoder writes, on the bundled
 * streams of the reference encoders: USAC (libxaac's encoder), the optional modes of H.263 (the TMN 3.2 encoder and
 * the Annex encoder of {@code tools/testdata/h263-annexes}) and H.264's separately coded color planes (the JM
 * encoder's monochrome streams merged into separate planes).
 */
final class MpegStreamWorkloads {
    /**
     * The USAC training streams: the frequency domain coder in stereo at 48 kHz, the switched coder with eSBR and
     * MPEG-D DRC in mono at 32 kHz, and the switched coder with eSBR and MPEG Surround 2-1-2 at 48 kHz.
     */
    static final List<String> USAC_STREAMS = List.of("usac-fd-stereo", "usac-switched-esbr-drc", "usac-mps212");

    /**
     * The H.263 training streams: PB-frames with syntax-based arithmetic coding, B-pictures, SNR and spatial
     * scalability, Advanced INTRA Coding with the alternative INTER VLC and modified quantization, Reduced-Resolution
     * Update, data partitioned slices with Improved PB-frames, Reference Picture Resampling, and Independent Segment
     * Decoding with rectangular slices.
     */
    static final List<String> H263_STREAMS = List.of("tmn-pb-sac", "smalt-b", "tmn-snr", "tmn-spatial-horizontal",
            "smalt-ist", "smalt-rru", "smalt-dps-ipb", "smalt-rpr", "smalt-isd-rect");

    /**
     * The H.264 training streams of separately coded color planes: CAVLC with B-pictures, CABAC with slices, and
     * CAVLC with scaling matrices.
     */
    static final List<String> H264_SEPARATE_PLANE_STREAMS = List.of("m8-cavlc-sep", "m8-cabac-slices-sep",
            "m8-cavlc-scaling-sep");

    /**
     * The separator between the parts of a USAC training stream.
     */
    private static final byte[] MARKER = "FUZZ-TAG".getBytes(StandardCharsets.US_ASCII);

    /**
     * Prevents instantiation.
     */
    private MpegStreamWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(
                file("usac-decode", MpegStreamWorkloads::usacDecode),
                file("h263-decode-optional-modes", MpegStreamWorkloads::h263Decode),
                file("h264-decode-separate-planes", MpegStreamWorkloads::h264SeparatePlanes));
    }

    /**
     * Returns a workload that decodes the USAC training streams, one decoder per stream kept across rounds.
     *
     * @return the workload
     */
    private static TrainingWorkload usacDecode() {
        var rounds = new ArrayList<Runnable>();
        for (var name : USAC_STREAMS) {
            var stream = usac(name);
            var decoder = new AacDecoder(stream.track());
            Consumer<AudioBuffer> output = AudioStreams::consume;
            rounds.add(() -> {
                for (var packet : stream.packets()) {
                    decoder.decode(packet, output);
                }
            });
        }
        return TrainingWorkload.of("usac-decode", () -> rounds.forEach(Runnable::run));
    }

    /**
     * A USAC training stream.
     *
     * @param track the track, carrying the {@code AudioSpecificConfig}
     * @param packets the access units, in decoding order
     */
    record UsacStream(AudioTrack track, List<Packet> packets) {
    }

    /**
     * Reads a USAC training stream, stored in the packet format of the fuzzing seeds: a header (an options byte, the
     * sampling rate in 24 bits, the channel count and the bits per sample in a byte each, then the
     * {@code AudioSpecificConfig}) and the access units, each after the separator {@code FUZZ-TAG} and a flags byte.
     *
     * @param name the stream name
     * @return the stream
     */
    static UsacStream usac(String name) {
        var data = TrainingStreams.bytes("usac/" + name + ".bin");
        var parts = new ArrayList<byte[]>();
        var start = 0;
        for (var position = indexOf(data, 0); position >= 0; position = indexOf(data, start)) {
            parts.add(Arrays.copyOfRange(data, start, position));
            start = position + MARKER.length;
        }
        parts.add(Arrays.copyOfRange(data, start, data.length));
        var header = parts.getFirst();
        var rate = (header[1] & 0xFF) << 16 | (header[2] & 0xFF) << 8 | (header[3] & 0xFF);
        var channels = header[4] & 0xFF;
        var config = Arrays.copyOfRange(header, 6, header.length);
        var track = AudioStreams.track(Codec.AAC, rate, channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO,
                config);
        var packets = new ArrayList<Packet>();
        var frame = 2048L;
        for (var index = 1; index < parts.size(); index++) {
            var part = parts.get(index);
            var payload = Arrays.copyOfRange(part, 1, part.length);
            packets.add(new Packet(1, payload, (index - 1) * frame, (index - 1) * frame, frame, true));
        }
        return new UsacStream(track, List.copyOf(packets));
    }

    /**
     * Returns where the separator next occurs in data.
     *
     * @param from the index to search from
     * @return the index, or {@code -1}
     */
    private static int indexOf(byte[] data, int from) {
        outer:
        for (var index = from; index + MARKER.length <= data.length; index++) {
            for (var k = 0; k < MARKER.length; k++) {
                if (data[index + k] != MARKER[k]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    /**
     * Returns a workload that decodes the H.263 training streams of the optional modes, one decoder per stream
     * kept across rounds.
     *
     * @return the workload
     */
    private static TrainingWorkload h263Decode() {
        var rounds = new ArrayList<Runnable>();
        for (var name : H263_STREAMS) {
            var stream = TrainingStreams.demux("h263/" + name + ".263");
            rounds.add(VideoStreams.decodeRound(stream.packets(), new H263Decoder((VideoTrack) stream.track(),
                    VideoDecoderOptions.defaults())));
        }
        return TrainingWorkload.of("h263-decode-optional-modes", () -> rounds.forEach(Runnable::run));
    }

    /**
     * Returns a workload that decodes the H.264 training streams of separately coded color planes.
     *
     * @return the workload
     */
    private static TrainingWorkload h264SeparatePlanes() {
        var rounds = new ArrayList<Runnable>();
        for (var name : H264_SEPARATE_PLANE_STREAMS) {
            var stream = TrainingStreams.demux("h264/" + name + ".264");
            rounds.add(VideoStreams.decodeRound(stream.packets(), new H264Decoder((VideoTrack) stream.track(),
                    H264DecoderOptions.defaults())));
        }
        return TrainingWorkload.of("h264-decode-separate-planes", () -> rounds.forEach(Runnable::run));
    }
}
