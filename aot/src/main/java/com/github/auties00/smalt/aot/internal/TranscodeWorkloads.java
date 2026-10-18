package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessMeter;
import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.ops.BlendMode;
import com.github.auties00.smalt.image.ops.BlurMethod;
import com.github.auties00.smalt.image.ops.CompositeOptions;
import com.github.auties00.smalt.image.ops.ConvolutionKernel;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.ImageAdjustments;
import com.github.auties00.smalt.image.ops.ImageCompositing;
import com.github.auties00.smalt.image.ops.ImageFilters;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ImageQuantization;
import com.github.auties00.smalt.image.ops.Interpolation;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.image.ops.QuantizeOptions;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.image.ops.RotationCanvas;
import com.github.auties00.smalt.image.ops.SmartCropStrategy;
import com.github.auties00.smalt.image.ops.ToneCurve;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import com.github.auties00.smalt.transcode.VideoTranscodeOptions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.file;

/**
 * Creates the workloads of {@link TrainingFamily#TRANSCODE}, the transcoder, containers and
 * processing of {@code smalt-core}.
 */
final class TranscodeWorkloads {
    /**
     * Prevents instantiation.
     */
    private TranscodeWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(
                file("transcode", TranscodeWorkloads::transcode),
                file("containers", TranscodeWorkloads::containers),
                file("image-convert", () -> imageConvert(2)),
                file("image-resize", TranscodeWorkloads::imageResize),
                file("image-effects", () -> imageEffects(1)),
                file("color-management", TranscodeWorkloads::colorManagement),
                file("resample", () -> resample(10)),
                file("loudness", () -> loudness(10)));
    }

    /**
     * Returns a workload that transcodes a synthetic three-second 640x360 MP4 with H.264 video and
     * AAC audio to WebM (VP9 and Opus) and to a smaller MP4 (H.264 at most 240 lines tall and AAC),
     * one output per round in turn.
     *
     * @return the workload
     */
    private static TrainingWorkload transcode() {
        var source = SyntheticFiles.mp4(640, 360, 3);
        var outputs = List.of(
                TranscodeOptions.of(OutputContainer.WEBM),
                TranscodeOptions.of(OutputContainer.MP4).toBuilder()
                        .video(VideoTranscodeOptions.encode(Codec.H264).toBuilder().maxShortEdge(240).build())
                        .audio(AudioTranscodeOptions.encode(Codec.AAC)).build());
        int[] turn = {0};
        return TrainingWorkload.of("transcode", () -> {
            var options = outputs.get(turn[0]++ % outputs.size());
            var sink = MediaSink.memory();
            try (var in = MediaSource.of(source)) {
                Transcoder.transcode(in, sink, options);
            }
            Blackhole.consume(sink.size());
        });
    }

    /**
     * Returns a workload that remuxes a synthetic MP4 with H.264 video and AAC audio to
     * fragmented MP4, Matroska and MPEG-TS, writes stereo PCM as WAV, and probes and demuxes every
     * file, the source included.
     *
     * @return the workload
     */
    private static TrainingWorkload containers() {
        var source = SyntheticFiles.mp4(320, 180, 2);
        var tracks = new ArrayList<Track>();
        var packets = new ArrayList<Packet>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(source))) {
            tracks.addAll(demuxer.tracks());
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        var pcm = pcm(SyntheticAudio.music(48000, 1));
        var pcmTrack = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000))
                .sampleRate(48000).layout(ChannelLayout.STEREO).build();
        List<Function<MediaSink, Muxer>> muxers = List.of(
                FragmentedMp4Muxer::new,
                sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska()),
                TsMuxer::new);
        return TrainingWorkload.of("containers", () -> {
            var files = new ArrayList<byte[]>();
            files.add(source);
            for (var factory : muxers) {
                var sink = MediaSink.memory();
                try (var muxer = factory.apply(sink)) {
                    tracks.forEach(muxer::addTrack);
                    packets.forEach(muxer::writePacket);
                    muxer.finish();
                }
                files.add(sink.toByteArray());
            }
            var wav = MediaSink.memory();
            try (var muxer = new WavMuxer(wav)) {
                muxer.addTrack(pcmTrack);
                muxer.writePacket(new Packet(1, pcm, 0, 0, pcm.length / 4, true));
                muxer.finish();
            }
            files.add(wav.toByteArray());
            for (var file : files) {
                Blackhole.consume(Media.probe(MediaSource.of(file)).hashCode());
                try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        Blackhole.consume(packet.size());
                    }
                }
            }
        });
    }

    /**
     * Returns planar samples as interleaved 16-bit little-endian PCM.
     *
     * @param planes the samples, one plane per channel
     * @return the PCM bytes
     */
    private static byte[] pcm(float[][] planes) {
        var buffer = ByteBuffer.allocate(planes[0].length * planes.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < planes[0].length; i++) {
            for (var plane : planes) {
                buffer.putShort((short) Math.round(plane[i] * 32767));
            }
        }
        return buffer.array();
    }

    /**
     * Returns a workload that converts a 1280x720 4:2:0 picture to RGBA and back, and rotates the
     * RGBA picture a quarter turn, as the image and video preparation paths do.
     *
     * @param count the conversions per round
     * @return the workload
     */
    private static TrainingWorkload imageConvert(int count) {
        var yuv = SyntheticVideo.natural(1280, 720, 1).getFirst();
        var rgba = SyntheticImages.rgba(yuv, false);
        return TrainingWorkload.of("image-convert", () -> {
            for (var i = 0; i < count; i++) {
                Blackhole.consume(ImageOps.toRgba(yuv).pixels()[4097]);
                Blackhole.consume(ImageOps.toYuv(rgba, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED).y()[4097]);
                Blackhole.consume(ImageOps.orient(rgba, Orientation.ROTATE_90).width());
            }
        });
    }

    /**
     * Returns a workload that resizes a 1280x720 picture with every filter, crops it with every
     * smart crop strategy, and quantizes it with every quantizer and dithering.
     *
     * @return the workload
     */
    private static TrainingWorkload imageResize() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(1280, 720, 1).getFirst(), true);
        var small = SyntheticImages.rgba(SyntheticVideo.natural(320, 180, 1).getFirst(), false);
        return TrainingWorkload.of("image-resize", () -> {
            for (var filter : ResizeFilter.values()) {
                Blackhole.consume(ImageOps.resize(picture, 640, 360, filter).width());
                Blackhole.consume(ImageOps.resize(small, 480, 270, filter).width());
            }
            for (var strategy : SmartCropStrategy.values()) {
                Blackhole.consume(ImageOps.smartCrop(picture, 400, 400, strategy, ResizeFilter.LANCZOS3).width());
            }
            for (var method : QuantizeMethod.values()) {
                for (var dithering : Dithering.values()) {
                    Blackhole.consume(ImageQuantization.quantize(small, QuantizeOptions.ofColors(64).toBuilder()
                            .method(method).dithering(dithering).build()).hashCode());
                }
            }
        });
    }

    /**
     * Returns a workload that runs the effects of the picture operations on a 640x360 picture:
     * exact and box Gaussian blurs, an unsharp mask, convolution, a median, tone adjustments,
     * compositing with two blend modes, and bilinear and bicubic rotations, which together
     * exercise every method of the effect and warp kernels.
     *
     * @param count the passes over every effect per round
     * @return the workload
     */
    private static TrainingWorkload imageEffects(int count) {
        var yuv = SyntheticVideo.natural(640, 360, 1).getFirst();
        var opaque = SyntheticImages.rgba(yuv, false);
        var mask = SyntheticImages.rgba(yuv, true);
        var deep = SyntheticImages.deep(opaque);
        var curve = ToneCurve.through(0, 0, 0.25, 0.2, 0.75, 0.8, 1, 1);
        return TrainingWorkload.of("image-effects", () -> {
            for (var i = 0; i < count; i++) {
                Blackhole.consume(ImageFilters.gaussianBlur(opaque, 2, BlurMethod.EXACT).width());
                Blackhole.consume(ImageFilters.gaussianBlur(opaque, 6, BlurMethod.BOX).width());
                Blackhole.consume(ImageFilters.gaussianBlur(deep, 2, BlurMethod.EXACT).width());
                Blackhole.consume(ImageFilters.unsharpMask(opaque, 1.5, 1.2, 2, BlurMethod.BOX).width());
                Blackhole.consume(ImageFilters.convolve(opaque, ConvolutionKernel.emboss()).width());
                Blackhole.consume(ImageFilters.median(opaque, 1).width());
                Blackhole.consume(ImageAdjustments.sepia(opaque).width());
                Blackhole.consume(ImageAdjustments.curves(opaque, curve).width());
                Blackhole.consume(ImageAdjustments.contrast(deep, 1.2).width());
                Blackhole.consume(ImageCompositing.composite(opaque, mask, 10, 10, CompositeOptions.of(BlendMode.SOFT_LIGHT)).width());
                Blackhole.consume(ImageCompositing.composite(opaque, mask, -10, 0, CompositeOptions.of(BlendMode.NORMAL)).width());
                Blackhole.consume(ImageOps.rotate(mask, 7, Interpolation.BILINEAR, RotationCanvas.EXPAND, 0).width());
                Blackhole.consume(ImageOps.rotate(opaque, -12, Interpolation.BICUBIC, RotationCanvas.KEEP, 0xFFFFFFFF).width());
            }
        });
    }

    /**
     * Returns a workload that converts 8-bit Display P3 pixels to sRGB, 16-bit Adobe RGB pixels to
     * Display P3, and a picture with BT.2020 PQ color to SDR sRGB.
     *
     * @return the workload
     */
    private static TrainingWorkload colorManagement() {
        var picture = SyntheticImages.rgba(SyntheticVideo.natural(640, 360, 1).getFirst(), false);
        var deep = SyntheticImages.deep(picture);
        var toSrgb = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
        var toP3 = ColorTransform.create(ColorProfile.adobeRgb(), ColorProfile.displayP3());
        var output = new byte[picture.pixels().length];
        var deepOutput = new short[deep.pixels().length];
        var hdr = SyntheticVideo.hdr(640, 360);
        var pixels = picture.width() * picture.height();
        return TrainingWorkload.of("color-management", () -> {
            toSrgb.transform(picture.pixels(), 0, 4, output, 0, 4, pixels);
            toP3.transform(deep.pixels(), 0, 4, deepOutput, 0, 4, pixels);
            Blackhole.consume(output[4097] + deepOutput[4097]);
            Blackhole.consume(ImageOps.toSrgb(hdr).pixels()[4097]);
        });
    }

    /**
     * Returns a workload that resamples stereo music from 44.1 to 48 kHz and mono speech from 48
     * to 16 kHz, in blocks.
     *
     * @param seconds the audio resampled per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload resample(double seconds) {
        var music = SyntheticAudio.music(44100, seconds);
        var speech = new float[][]{SyntheticAudio.speech(48000, seconds)};
        var up = new Resampler(44100, 48000, 2, Resampler.Quality.STANDARD);
        var down = new Resampler(48000, 16000, 1, Resampler.Quality.STANDARD);
        var musicBlock = new AudioBuffer(ChannelLayout.STEREO, 44100, AudioStreams.BLOCK_FRAMES);
        var speechBlock = new AudioBuffer(ChannelLayout.MONO, 48000, AudioStreams.BLOCK_FRAMES);
        // Room for a block plus the frames the resampler may add while catching up on its filter delay.
        var upOutput = new float[2][2 * AudioStreams.BLOCK_FRAMES];
        var downOutput = new float[1][AudioStreams.BLOCK_FRAMES];
        return TrainingWorkload.of("resample", () -> {
            SyntheticAudio.feed(music, musicBlock,
                    buffer -> Blackhole.consume(up.process(buffer.planes(), buffer.frames(), upOutput, 0)));
            SyntheticAudio.feed(speech, speechBlock,
                    buffer -> Blackhole.consume(down.process(buffer.planes(), buffer.frames(), downOutput, 0)));
        });
    }

    /**
     * Returns a workload that measures the loudness, loudness range and true peak of stereo music,
     * one meter across rounds.
     *
     * @param seconds the music measured per round, in seconds
     * @return the workload
     */
    private static TrainingWorkload loudness(double seconds) {
        var music = SyntheticAudio.music(48000, seconds);
        var meter = new LoudnessMeter(ChannelLayout.STEREO, 48000);
        var block = new AudioBuffer(ChannelLayout.STEREO, 48000, AudioStreams.BLOCK_FRAMES);
        return TrainingWorkload.of("loudness", () -> {
            SyntheticAudio.feed(music, block, meter::accept);
            Blackhole.consume(Double.doubleToRawLongBits(meter.integrated() + meter.loudnessRange() + meter.truePeak()));
        });
    }
}
