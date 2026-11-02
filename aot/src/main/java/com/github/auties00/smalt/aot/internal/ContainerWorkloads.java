package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.dash.DashDemuxer;
import com.github.auties00.smalt.container.hls.HlsDemuxer;
import com.github.auties00.smalt.container.packaging.PackageOutput;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.container.packaging.Rendition;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeInput;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dv.DvEncoder;
import com.github.auties00.smalt.video.dv.DvEncoderOptions;
import com.github.auties00.smalt.video.dv.DvProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the {@link TrainingFamily#TRANSCODE} workloads of the containers the other workloads leave out: the PCM,
 * AAC and elementary audio files, AVI, FLV, DV, Ogg and IVF, joined inputs, the text subtitle files, and HLS and
 * DASH presentations.
 */
final class ContainerWorkloads {
    /**
     * The sampling rate of the synthetic PCM tracks.
     */
    private static final int RATE = 48000;

    /**
     * Prevents instantiation.
     */
    private ContainerWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(
                file("containers-audio", ContainerWorkloads::audio),
                file("containers-video", ContainerWorkloads::video),
                file("subtitle-files", ContainerWorkloads::subtitles),
                file("streaming-presentations", ContainerWorkloads::presentations));
    }

    /**
     * Returns a workload that writes and reads audio files: 16-bit PCM as Wave64, AIFF, AU and CAF; AAC packets as
     * ADTS, LOAS, ADIF and CAF; and that reads the files of the Opus, Vorbis and FLAC in Ogg, raw FLAC, MP3, AMR,
     * AC-3 and E-AC-3 that the transcoder wrote once from a synthetic M4A.
     *
     * @return the workload
     */
    private static TrainingWorkload audio() {
        var m4a = SyntheticFiles.m4a(2);
        var aac = read(m4a);
        var little = pcm(Codec.PCM_S16LE, false);
        var big = pcm(Codec.PCM_S16BE, true);
        var transcoded = new ArrayList<byte[]>();
        for (var output : List.of(TranscodeOptions.of(OutputContainer.OGG),
                TranscodeOptions.of(OutputContainer.OGG).toBuilder()
                        .audio(AudioTranscodeOptions.encode(Codec.VORBIS)).build(),
                TranscodeOptions.of(OutputContainer.OGG_FLAC), TranscodeOptions.of(OutputContainer.FLAC),
                TranscodeOptions.of(OutputContainer.MP3), TranscodeOptions.of(OutputContainer.AMR),
                TranscodeOptions.of(OutputContainer.AC3), TranscodeOptions.of(OutputContainer.EAC3))) {
            transcoded.add(transcode(m4a, output));
        }
        return TrainingWorkload.of("containers-audio", () -> {
            for (var format : List.of(ContainerFormat.W64, ContainerFormat.AIFF, ContainerFormat.AU,
                    ContainerFormat.CAF)) {
                readAll(write(format, format == ContainerFormat.W64 ? little : big));
            }
            for (var format : List.of(ContainerFormat.ADTS, ContainerFormat.LOAS, ContainerFormat.ADIF,
                    ContainerFormat.CAF)) {
                readAll(write(format, aac));
            }
            for (var file : transcoded) {
                readAll(file);
            }
        });
    }

    /**
     * Returns a workload that writes the H.264 and AAC packets of a synthetic MP4 as AVI and FLV and two DV frames
     * as a raw DV file, reads them back, reads an IVF file of VP8 the transcoder wrote once, and joins two MP4 files
     * into a Matroska file, copying their streams.
     *
     * @return the workload
     */
    private static TrainingWorkload video() {
        var mp4 = SyntheticFiles.mp4(320, 180, 2);
        var stream = read(mp4);
        var ivf = transcode(mp4, TranscodeOptions.of(OutputContainer.IVF).toBuilder()
                .video(VideoTranscodeOptions.encode(Codec.VP8).toBuilder().maxShortEdge(90).build()).build());
        var dv = dv();
        var concat = TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder().video(VideoTranscodeOptions.copy())
                .audio(AudioTranscodeOptions.copy()).build();
        return TrainingWorkload.of("containers-video", () -> {
            readAll(write(ContainerFormat.AVI, stream));
            readAll(write(ContainerFormat.FLV, stream));
            readAll(write(ContainerFormat.DV, dv));
            readAll(ivf);
            var sink = MediaSink.memory();
            Transcoder.transcode(List.of(TranscodeInput.concat(List.of(MediaSource.of(mp4), MediaSource.of(mp4)))),
                    sink, concat);
            readAll(sink.toByteArray());
        });
    }

    /**
     * Returns two NTSC DV frames of the synthetic natural clip.
     *
     * @return the track and its packets
     */
    private static Tracks dv() {
        var profile = DvProfile.DV_525;
        var packets = new ArrayList<Packet>();
        try (var encoder = new DvEncoder(DvEncoderOptions.of(profile))) {
            var pts = 0L;
            for (var picture : SyntheticVideo.natural(profile.width(), profile.height(), 2)) {
                encoder.encode(new VideoFrame(picture, pts++, 1, true), packet -> packets.add(packet.withTrackId(1)));
            }
            encoder.flush(packet -> packets.add(packet.withTrackId(1)));
        }
        return new Tracks(List.of(VideoStreams.track(Codec.DV, profile.width(), profile.height(), null)),
                List.copyOf(packets));
    }

    /**
     * Returns a workload that writes synthetic cues with markup as SubRip, WebVTT, ASS, TTML, SAMI, LRC and MicroDVD
     * files and reads each back.
     *
     * @return the workload
     */
    private static TrainingWorkload subtitles() {
        var cues = new ArrayList<SubtitleCue>();
        for (var index = 0; index < 40; index++) {
            var start = index * 1_500_000L;
            var text = index % 3 == 0 ? "<i>Line " + index + "</i>\nsecond <b>row</b>" : "Cue number " + index;
            cues.add(SubtitleCue.of(start, start + 1_200_000L, text));
        }
        var formats = List.of(ContainerFormat.SUBRIP, ContainerFormat.WEBVTT, ContainerFormat.ASS,
                ContainerFormat.TTML, ContainerFormat.SAMI, ContainerFormat.LRC, ContainerFormat.MICRODVD);
        return TrainingWorkload.of("subtitle-files", () -> {
            for (var format : formats) {
                var sink = MediaSink.memory();
                Subtitles.write(cues, SubtitleMarkup.SUBRIP, format, sink);
                Blackhole.consume(Subtitles.read(MediaSource.of(sink.toByteArray())).size());
            }
        });
    }

    /**
     * Returns a workload that packages a synthetic MP4 as an HLS presentation of MPEG-TS segments and as a DASH
     * presentation of fragmented MP4 segments, in memory, and reads every packet of each presentation back.
     *
     * @return the workload
     */
    private static TrainingWorkload presentations() {
        var mp4 = SyntheticFiles.mp4(320, 180, 4);
        return TrainingWorkload.of("streaming-presentations", () -> {
            for (var options : List.of(PackagingOptions.hls(), PackagingOptions.dash())) {
                var files = new HashMap<String, ByteArrayMediaSink>();
                PackageOutput output = name -> files.computeIfAbsent(name, ignored -> MediaSink.memory());
                var result = Packager.pack(List.of(Rendition.of(MediaSource.of(mp4))), output, options);
                var resolver = new DataReferenceResolver() {
                    @Override
                    public Optional<MediaSource> resolve(DataReference reference) {
                        var file = files.get(reference.fileName());
                        return file == null ? Optional.empty() : Optional.of(MediaSource.of(file.toByteArray()));
                    }
                };
                var demuxerOptions = DemuxerOptions.builder().dataReferences(resolver).build();
                var entry = result.masterPlaylist().or(result::manifest).orElseThrow();
                var source = MediaSource.of(files.get(entry).toByteArray());
                try (Demuxer demuxer = result.masterPlaylist().isPresent() ? new HlsDemuxer(source, demuxerOptions)
                        : new DashDemuxer(source, demuxerOptions)) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        Blackhole.consume(packet.size());
                    }
                }
            }
        });
    }

    /**
     * The tracks and packets of a file.
     *
     * @param tracks the tracks
     * @param packets the packets, in file order
     */
    record Tracks(List<Track> tracks, List<Packet> packets) {
    }

    /**
     * Reads every track and packet of a file.
     *
     * @param file the file
     * @return the tracks and packets
     */
    static Tracks read(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return new Tracks(List.copyOf(demuxer.tracks()), List.copyOf(packets));
        }
    }

    /**
     * Probes a file and reads every packet of it.
     *
     * @param file the file
     */
    private static void readAll(byte[] file) {
        Blackhole.consume(Media.probe(MediaSource.of(file)).hashCode());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                Blackhole.consume(packet.size());
            }
        }
    }

    /**
     * Writes tracks and packets in a container format.
     *
     * @param format the format
     * @param stream the tracks and packets
     * @return the file
     */
    static byte[] write(ContainerFormat format, Tracks stream) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(format, sink)) {
            stream.tracks().forEach(muxer::addTrack);
            stream.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /**
     * Transcodes a file.
     *
     * @param file the file
     * @param options the options
     * @return the transcoded file
     */
    private static byte[] transcode(byte[] file, TranscodeOptions options) {
        var sink = MediaSink.memory();
        try (var in = MediaSource.of(file)) {
            Transcoder.transcode(in, sink, options);
        }
        return sink.toByteArray();
    }

    /**
     * Returns a track of two seconds of synthetic stereo music as 16-bit PCM, in packets of 4096 frames.
     *
     * @param codec {@link Codec#PCM_S16LE} or {@link Codec#PCM_S16BE}
     * @param bigEndian whether the samples are big endian
     * @return the track and its packets
     */
    static Tracks pcm(Codec codec, boolean bigEndian) {
        var planes = SyntheticAudio.music(RATE, 2);
        var track = AudioTrack.builder().id(1).codec(codec).timeBase(new Rational(1, RATE)).sampleRate(RATE)
                .layout(ChannelLayout.STEREO).build();
        var packets = new ArrayList<Packet>();
        var frames = planes[0].length;
        for (var start = 0; start < frames; start += 4096) {
            var count = Math.min(4096, frames - start);
            var data = new byte[count * 4];
            for (var frame = 0; frame < count; frame++) {
                for (var channel = 0; channel < 2; channel++) {
                    var value = (int) Math.round(Math.clamp(planes[channel][start + frame], -1f, 1f) * 32767);
                    var at = (frame * 2 + channel) * 2;
                    data[at + (bigEndian ? 1 : 0)] = (byte) value;
                    data[at + (bigEndian ? 0 : 1)] = (byte) (value >> 8);
                }
            }
            packets.add(new Packet(1, data, start, start, count, true));
        }
        return new Tracks(List.of(track), List.copyOf(packets));
    }
}
