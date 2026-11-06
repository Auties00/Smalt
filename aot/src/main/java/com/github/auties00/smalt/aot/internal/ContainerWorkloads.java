package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
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
import com.github.auties00.smalt.container.subtitle.VobSubDemuxer;
import com.github.auties00.smalt.container.subtitle.VobSubMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.container.wav.WavMuxerOptions;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.BitmapSubtitleDecoder;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.SilenceTrim;
import com.github.auties00.smalt.transcode.TranscodeInput;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;
import com.github.auties00.smalt.video.UncompressedVideoEncoder;
import com.github.auties00.smalt.video.UncompressedVideoFormat;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dv.DvEncoder;
import com.github.auties00.smalt.video.dv.DvEncoderOptions;
import com.github.auties00.smalt.video.dv.DvProfile;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the {@link TrainingFamily#TRANSCODE} workloads of the containers the other workloads leave out: the PCM,
 * AAC, IEC 61937 and elementary audio files, AVI, FLV, DV, Ogg, IVF and ASF, ISO/IEC 23001-17 uncompressed video in
 * MP4, merged and concatenated inputs, the text, PGS and VobSub subtitle files, and HLS and DASH presentations.
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
                file("uncompressed-video", ContainerWorkloads::uncompressedVideo),
                file("asf-files", ContainerWorkloads::asfFiles),
                file("joined-inputs", ContainerWorkloads::joinedInputs),
                file("subtitle-files", ContainerWorkloads::subtitles),
                file("bitmap-subtitles", ContainerWorkloads::bitmapSubtitles),
                file("vobsub-files", ContainerWorkloads::vobSubFiles),
                file("streaming-presentations", ContainerWorkloads::presentations));
    }

    /**
     * Returns a workload that writes and reads audio files: 16-bit PCM as Wave64, AIFF, AU and CAF; AAC packets as
     * ADTS, LOAS, ADIF and CAF; Opus packets in a WAVE file, self-delimited; AC-3 and AAC as IEC 61937 bursts in WAVE
     * files; and that reads the files of the Opus, Vorbis and FLAC in Ogg, raw FLAC, MP3, AMR, AC-3 and E-AC-3 that
     * the transcoder wrote once from a synthetic M4A.
     *
     * @return the workload
     */
    private static TrainingWorkload audio() {
        var m4a = SyntheticFiles.m4a(2);
        var aac = read(m4a);
        var little = pcm(Codec.PCM_S16LE, false);
        var big = pcm(Codec.PCM_S16BE, true);
        var transcoded = new ArrayList<byte[]>();
        var opus = transcode(m4a, TranscodeOptions.of(OutputContainer.OGG));
        var ac3 = transcode(m4a, TranscodeOptions.of(OutputContainer.AC3));
        var opusTracks = read(opus);
        var ac3Tracks = read(ac3);
        for (var output : List.of(
                TranscodeOptions.of(OutputContainer.OGG).toBuilder()
                        .audio(AudioTranscodeOptions.encode(Codec.VORBIS)).build(),
                TranscodeOptions.of(OutputContainer.OGG_FLAC), TranscodeOptions.of(OutputContainer.FLAC),
                TranscodeOptions.of(OutputContainer.MP3), TranscodeOptions.of(OutputContainer.AMR),
                TranscodeOptions.of(OutputContainer.EAC3))) {
            transcoded.add(transcode(m4a, output));
        }
        transcoded.add(opus);
        transcoded.add(ac3);
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
            readAll(wav(opusTracks, WavMuxerOptions.defaults()));
            readAll(wav(ac3Tracks, WavMuxerOptions.forIec61937()));
            readAll(wav(aac, WavMuxerOptions.builder().encapsulation(WavMuxerOptions.Encapsulation.IEC61937_EXTENSIBLE)
                    .build()));
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
     * Returns a workload that encodes synthetic pictures as ISO/IEC 23001-17 uncompressed video in the layouts that
     * select different paths (8-bit 4:2:0 planes, 10-bit 4:2:2 multi-Y units of aligned little-endian values, and
     * zlib-compressed RGBA pixels of 4-bit values), writes each stream as MP4, and reads and decodes it back.
     *
     * @return the workload
     */
    private static TrainingWorkload uncompressedVideo() {
        var formats = List.of(UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV420, 8),
                UncompressedVideoFormat.ofYCbCr(ChromaFormat.YUV422, 10)
                        .withInterleave(UncompressedVideoFormat.Interleave.MULTI_Y).withAlignedComponents(true)
                        .withLittleEndian(true),
                UncompressedVideoFormat.ofRgb(4).withAlpha(true)
                        .withCompression(UncompressedVideoFormat.Compression.ZLIB));
        var picture = YuvPicture.allocate(320, 180, ChromaFormat.YUV444, true, ColorInfo.BT601_LIMITED);
        for (var plane : new byte[][]{picture.y(), picture.u(), picture.v(), picture.alpha()}) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (byte) (i * 7 + i / 320);
            }
        }
        return TrainingWorkload.of("uncompressed-video", () -> {
            for (var format : formats) {
                var packets = new ArrayList<Packet>();
                VideoTrack track;
                try (var encoder = new UncompressedVideoEncoder(format, 320, 180, new Rational(25, 1), null)) {
                    for (var frame = 0; frame < 3; frame++) {
                        encoder.encode(new VideoFrame(picture, frame, 1, true),
                                packet -> packets.add(packet.withTrackId(1)));
                    }
                    track = VideoTrack.builder().id(1).codec(encoder.codec()).timeBase(encoder.timeBase())
                            .width(320).height(180).codecConfig(encoder.codecConfig().orElseThrow()).build();
                }
                var file = write(ContainerFormat.MP4, new Tracks(List.of(track), packets));
                var read = read(file);
                try (var decoder = VideoDecoders.create((VideoTrack) read.tracks().getFirst())) {
                    for (var packet : read.packets()) {
                        decoder.decode(packet, decoded -> Blackhole.consume(decoded.picture().width()));
                    }
                }
            }
        });
    }

    /**
     * Returns a workload that probes the ASF file of {@link #asf()} and reads every packet of it, which reassembles
     * the split media objects, expands the compressed payloads, undoes the audio spread interleaving and converts the
     * Annex B video, and reads it again from a seek to its middle.
     *
     * @return the workload
     */
    private static TrainingWorkload asfFiles() {
        var file = asf().file();
        return TrainingWorkload.of("asf-files", () -> {
            readAll(file);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                demuxer.seek(demuxer.durationMicros() / 2);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    Blackhole.consume(packet.size());
                }
            }
        });
    }

    /**
     * An ASF file and what it was written from.
     *
     * @param file the bytes of the ASF file
     * @param video the H.264 track and packets of its video stream, number 1, as length-prefixed NAL units
     * @param stereo the samples of its 48 kHz stereo 16-bit PCM stream, number 2, stored with audio spread
     * @param mono the samples of its 8 kHz mono 8-bit PCM stream, number 3, stored as compressed payloads
     */
    record AsfFile(byte[] file, Tracks video, byte[] stereo, byte[] mono) {
    }

    /**
     * Returns an ASF file of two seconds: H.264 video with a key frame every half second as an Annex B stream, 48 kHz
     * stereo PCM in objects of 1024 frames interleaved by audio spread (two virtual packets of four 512-byte chunks),
     * and 8 kHz mono 8-bit PCM in objects of 20 milliseconds sent five at a time as compressed payloads.
     *
     * @return the file and its content
     */
    static AsfFile asf() {
        var h264 = asfVideo();
        var video = (VideoTrack) h264.tracks().getFirst();
        var videoPackets = h264.packets();
        var config = video.codecConfig().orElseThrow();
        var lengthSize = SyntheticAsf.lengthSize(config);
        var millis = new Rational(1, 1000);
        var sent = new ArrayList<SentObject>();
        var firstPts = videoPackets.stream().mapToLong(Packet::pts).min().orElse(0);
        for (var packet : videoPackets) {
            var pts = video.timeBase().rescale(packet.pts() - firstPts, millis);
            var dts = video.timeBase().rescale(packet.dts() - firstPts, millis);
            sent.add(new SentObject(dts, new SyntheticAsf.MediaObject(1, pts, packet.keyframe(),
                    SyntheticAsf.annexB(packet.data(), lengthSize))));
        }
        var stereo = new ByteArrayOutputStream();
        pcm(Codec.PCM_S16LE, false).packets().forEach(packet -> stereo.writeBytes(packet.data()));
        var stereoBytes = stereo.toByteArray();
        for (var start = 0; start < stereoBytes.length; start += 4096) {
            var time = start / 4 * 1000L / RATE;
            sent.add(new SentObject(time, new SyntheticAsf.MediaObject(2, time, true,
                    Arrays.copyOfRange(stereoBytes, start, Math.min(stereoBytes.length, start + 4096)))));
        }
        var plane = SyntheticAudio.music(8000, 2)[0];
        var mono = new byte[plane.length];
        for (var index = 0; index < mono.length; index++) {
            mono[index] = (byte) (128 + Math.round(Math.clamp(plane[index], -1f, 1f) * 127));
        }
        for (var start = 0; start < mono.length; start += 160) {
            var time = start / 8L;
            sent.add(new SentObject(time / 100 * 100, new SyntheticAsf.MediaObject(3, time, true,
                    Arrays.copyOfRange(mono, start, Math.min(mono.length, start + 160)))));
        }
        sent.sort(Comparator.comparingLong(SentObject::sendTime));
        var streams = List.of(
                SyntheticAsf.h264(1, video.width(), video.height(), SyntheticAsf.annexBParameterSets(config),
                        10_000_000L / 30),
                SyntheticAsf.pcm(2, 2, RATE, 16, 2, 2048, 512),
                SyntheticAsf.pcm(3, 1, 8000, 8, 0, 0, 0));
        var file = SyntheticAsf.write("Smalt training", streams, sent.stream().map(SentObject::object).toList());
        return new AsfFile(file, new Tracks(List.of(video), videoPackets), stereoBytes, mono);
    }

    /**
     * Returns two seconds of 320x180 H.264 video at 30 frames per second with a key frame every half second, as
     * length-prefixed NAL units.
     *
     * @return the track, number 1, and its packets in decoding order
     */
    private static Tracks asfVideo() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(320, 180, H264EncoderOptions.forFile().toBuilder()
                .timeBase(new Rational(1, 30)).bitrate(1_000_000).keyframeInterval(15).build())) {
            var pts = 0L;
            for (var picture : SyntheticVideo.natural(320, 180, 60)) {
                encoder.encode(new VideoFrame(picture, pts++, 1, false), packet -> packets.add(packet.withTrackId(1)));
            }
            encoder.flush(packet -> packets.add(packet.withTrackId(1)));
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(encoder.timeBase())
                    .codecConfig(encoder.codecConfig().orElseThrow()).width(320).height(180)
                    .frameRate(new Rational(30, 1)).build();
            return new Tracks(List.of(track), List.copyOf(packets));
        }
    }

    /**
     * A media object of an ASF file and the time it is sent at.
     *
     * @param sendTime the time it is sent at, in milliseconds
     * @param object the media object
     */
    private record SentObject(long sendTime, SyntheticAsf.MediaObject object) {
    }

    /**
     * Returns a workload of the transcodes of {@link #joinedJobs}: inputs merged and files concatenated, with their
     * streams re-encoded.
     *
     * @return the workload
     */
    private static TrainingWorkload joinedInputs() {
        var mp4 = SyntheticFiles.mp4(320, 180, 2);
        var m4a = SyntheticFiles.m4a(2);
        var flac = transcode(m4a, TranscodeOptions.of(OutputContainer.FLAC).toBuilder()
                .audio(AudioTranscodeOptions.encode(Codec.FLAC).toBuilder().sampleRate(RATE).build()).build());
        var jobs = joinedJobs(mp4, m4a, flac);
        return TrainingWorkload.of("joined-inputs", () -> {
            for (var job : jobs) {
                var sink = MediaSink.memory();
                Transcoder.transcode(job.inputs(), sink, job.options());
                Blackhole.consume(sink.toByteArray().length);
            }
        });
    }

    /**
     * A transcode of several inputs.
     *
     * @param inputs the inputs, merged when there are several
     * @param options the output container and stream options
     */
    record JoinedJob(List<TranscodeInput> inputs, TranscodeOptions options) {
    }

    /**
     * Returns the transcodes of the joined inputs workload: the video of an MP4 merged with the audio of an M4A as
     * Matroska, the video copied and the audio re-encoded as Opus with its leading and trailing silence trimmed; an
     * M4A and a FLAC file of another sampling rate concatenated as an M4A, re-encoded as AAC with two-pass loudness
     * normalization; and an MP4 concatenated with itself as an MP4, its video re-encoded and its audio copied.
     *
     * @param mp4 an MP4 file of H.264 video and AAC audio
     * @param m4a an M4A file of 44.1 kHz AAC audio
     * @param flac a FLAC file of 48 kHz audio
     * @return the inputs and options of the three transcodes, in that order
     */
    static List<JoinedJob> joinedJobs(byte[] mp4, byte[] m4a, byte[] flac) {
        var merge = TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                .video(VideoTranscodeOptions.copy().toBuilder().input(0).build())
                .audio(AudioTranscodeOptions.encode(Codec.OPUS).toBuilder().input(1).build())
                .silenceTrim(SilenceTrim.defaults()).build();
        var audio = TranscodeOptions.of(OutputContainer.M4A).toBuilder()
                .audio(AudioTranscodeOptions.encode(Codec.AAC).toBuilder()
                        .loudnessNormalization(LoudnessNormalization.ebuR128()).build()).build();
        var video = TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                .video(VideoTranscodeOptions.encode(Codec.H264).toBuilder().maxShortEdge(90).build())
                .audio(AudioTranscodeOptions.copy()).build();
        return List.of(
                new JoinedJob(List.of(TranscodeInput.of(MediaSource.of(mp4)), TranscodeInput.of(MediaSource.of(m4a))),
                        merge),
                new JoinedJob(List.of(TranscodeInput.concat(List.of(MediaSource.of(m4a), MediaSource.of(flac)))),
                        audio),
                new JoinedJob(List.of(TranscodeInput.concat(List.of(MediaSource.of(mp4), MediaSource.of(mp4)))),
                        video));
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
     * Returns a workload that writes twenty PGS subtitles, each shown and then cleared, as a SUP file, reads it back
     * as packets and as bitmap cues, and decodes the packets to bitmaps.
     *
     * @return the workload
     */
    private static TrainingWorkload bitmapSubtitles() {
        var tracks = new Tracks(List.of(SyntheticPgs.track()), SyntheticPgs.displaySets(20, 640, 96));
        return TrainingWorkload.of("bitmap-subtitles", () -> {
            var file = write(ContainerFormat.SUP, tracks);
            readAll(file);
            Blackhole.consume(Subtitles.readBitmaps(MediaSource.of(file)).size());
            var decoder = new BitmapSubtitleDecoder(tracks.tracks().getFirst());
            for (var packet : tracks.packets()) {
                Blackhole.consume(decoder.decode(packet).size());
            }
        });
    }

    /**
     * Returns a workload that writes two tracks of 24 DVD subpictures as a VobSub pair and as an MPEG program stream,
     * reads the pair back as packets, with its index and without it, and as bitmap cues, reads the program stream,
     * and decodes the packets of the first track to bitmaps.
     *
     * @return the workload
     */
    private static TrainingWorkload vobSubFiles() {
        var tracks = vobSubTracks(24);
        return TrainingWorkload.of("vobsub-files", () -> {
            var pair = vobSub(tracks);
            try (var demuxer = new VobSubDemuxer(MediaSource.of(pair[0]), MediaSource.of(pair[1]),
                    DemuxerOptions.defaults())) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    Blackhole.consume(packet.size());
                }
            }
            readAll(pair[1]);
            Blackhole.consume(Subtitles.readBitmaps(MediaSource.of(pair[1])).size());
            readAll(write(ContainerFormat.MPEG_PS, tracks));
            var decoder = new BitmapSubtitleDecoder(tracks.tracks().getFirst());
            for (var packet : tracks.packets()) {
                if (packet.trackId() == 1) {
                    Blackhole.consume(decoder.decode(packet).size());
                }
            }
        });
    }

    /**
     * Returns two DVD subpicture tracks, English and German, of 360 by 64 subpictures, their units interleaved by
     * time.
     *
     * @param count the number of subpictures of each track
     * @return the tracks and packets
     */
    static Tracks vobSubTracks(int count) {
        var english = SyntheticDvdSub.units(1, count, 360, 64, 0);
        var german = SyntheticDvdSub.units(2, count, 360, 64, 5);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < count; index++) {
            packets.add(english.get(index));
            packets.add(german.get(index));
        }
        return new Tracks(List.of(SyntheticDvdSub.track(1, "eng"), SyntheticDvdSub.track(2, "ger")),
                List.copyOf(packets));
    }

    /**
     * Writes DVD subpicture tracks as a VobSub pair.
     *
     * @param tracks the tracks and packets
     * @return the index file and the program stream file, in that order
     */
    static byte[][] vobSub(Tracks tracks) {
        var idx = MediaSink.memory();
        var sub = MediaSink.memory();
        try (var muxer = new VobSubMuxer(idx, sub)) {
            tracks.tracks().forEach(muxer::addTrack);
            tracks.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return new byte[][]{idx.toByteArray(), sub.toByteArray()};
    }

    /**
     * Writes tracks and packets as a WAVE file.
     *
     * @param tracks the tracks and packets
     * @param options the options of the WAVE muxer
     * @return the file
     */
    private static byte[] wav(Tracks tracks, WavMuxerOptions options) {
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink, options)) {
            tracks.tracks().forEach(muxer::addTrack);
            tracks.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
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
     * @param packets the packets, in file order
     */
    record Tracks(List<Track> tracks, List<Packet> packets) {
    }

    /**
     * Reads every track and packet of a file.
     *
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
