package com.github.auties00.smalt.mpeg.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzGainMaps;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;
import com.github.auties00.smalt.video.mpeg4.Mpeg4ElementaryStream;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fuzz targets of the video and audio decoders of this module, fed streams in the
 * {@link com.github.auties00.smalt.testkit.fuzz.FuzzPackets} format (see {@link FuzzMedia#video} and
 * {@link FuzzMedia#audio}), among them the HEVC decoder decoding the layers of an output layer set, of the HEIC
 * image decoder (see {@link FuzzMedia#image}), of the MPEG-4 Part 2 and H.263 elementary stream reader, of the
 * H.264 parameter set parser and of the H.264 decoder decoding every layer of a stream (see {@link #h264Layers}).
 */
class MpegFuzzTest {
    /** The packets of an elementary stream that are decoded after it is split. */
    private static final int DECODED_PACKETS = 8;

    @FuzzTest
    void h264(byte[] data) {
        FuzzMedia.video(Codec.H264, data);
    }

    /**
     * Decodes an H.264 stream in the {@link FuzzPackets} format at its highest scalable layer, with every view and
     * the alpha of auxiliary pictures; options bit 0 asks for low-delay decoding, bit 1 for the depth views and bit 2
     * for the enhanced resolution of frame-compatible stereo. A packet the decoder rejects is skipped and decoding
     * goes on with the next one.
     */
    @FuzzTest
    void h264Layers(byte[] data) {
        var run = Fuzz.start("h264Layers");
        var input = FuzzPackets.parse(data, FuzzPackets.VIDEO_FIELDS);
        var options = H264DecoderOptions.builder()
                .limits(Fuzz.LIMITS)
                .realtime((input.header().options() & 1) != 0)
                .highestLayer()
                .allViews()
                .depth((input.header().options() & 2) != 0)
                .enhancedResolution((input.header().options() & 4) != 0)
                .build();
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).build();
        var decoder = run.value("open", () -> new H264Decoder(track, options));
        if (decoder.isEmpty()) {
            return;
        }
        Consumer<VideoFrame> sink = frame -> {
            var picture = frame.picture();
            if (picture.width() <= 0 || picture.height() <= 0) {
                throw new IllegalStateException("decoded an empty picture " + picture.width() + "x" + picture.height());
            }
            frame.sideData().layer();
            picture.hasAlpha();
            run.output();
        };
        try (var opened = decoder.get()) {
            for (var index = 0; index < input.packets().size(); index++) {
                var part = input.packets().get(index);
                var pts = index * 3000L;
                var packet = new Packet(1, part.data(), pts, pts, 3000, part.keyframe() || index == 0);
                run.step("packet " + index, () -> opened.decode(packet, sink));
            }
            run.step("flush", () -> opened.flush(sink));
        }
    }

    @FuzzTest
    void hevc(byte[] data) {
        FuzzMedia.video(Codec.HEVC, data);
    }

    /**
     * Decodes HEVC streams with the layers of an output layer set: bit 0 of the options asks for high-bit-depth
     * output, bits 1 to 3 give the output layer set index (7 for the last one) and bit 4 leaves the alpha layers
     * undecoded.
     */
    @FuzzTest
    void hevcLayers(byte[] data) {
        FuzzMedia.video(Codec.HEVC, "hevcLayers", data, (track, options) -> {
            var builder = HevcDecoderOptions.builder()
                    .limits(Fuzz.LIMITS)
                    .threads(1)
                    .highBitDepth((options & 1) != 0)
                    .alpha((options & 16) == 0);
            var outputLayerSet = (options >> 1) & 7;
            if (outputLayerSet == 7) {
                builder.lastOutputLayerSet();
            } else {
                builder.outputLayerSet(outputLayerSet);
            }
            return new HevcDecoder(track, builder.build());
        });
    }

    @FuzzTest
    void mpeg4(byte[] data) {
        FuzzMedia.video(Codec.MPEG4_PART2, data);
    }

    @FuzzTest
    void h263(byte[] data) {
        FuzzMedia.video(Codec.H263, data);
    }

    @FuzzTest
    void aac(byte[] data) {
        FuzzMedia.audio(Codec.AAC, data);
    }

    @FuzzTest
    void amrNb(byte[] data) {
        FuzzMedia.audio(Codec.AMR_NB, data);
    }

    @FuzzTest
    void amrWb(byte[] data) {
        FuzzMedia.audio(Codec.AMR_WB, data);
    }

    @FuzzTest
    void ac3(byte[] data) {
        FuzzMedia.audio(Codec.AC3, data);
    }

    @FuzzTest
    void eac3(byte[] data) {
        FuzzMedia.audio(Codec.EAC3, data);
    }

    @FuzzTest
    void heic(byte[] data) {
        FuzzMedia.image(ImageFormat.HEIC, data);
    }

    @FuzzTest
    void elementaryStream(byte[] data) {
        var run = Fuzz.start("elementaryStream");
        var options = DemuxerOptions.builder().limits(Fuzz.LIMITS).build();
        var track = new VideoTrack[1];
        var packets = new ArrayList<Packet>();
        run.step("split", () -> {
            try (var stream = Mpeg4ElementaryStream.open(MediaSource.of(data), options)) {
                track[0] = stream.track();
                var joined = new ByteArrayOutputStream(data.length);
                Packet packet;
                while (packets.size() < Fuzz.MAX_PACKETS && (packet = stream.read()) != null) {
                    if (packet.trackId() != track[0].id() || packet.size() == 0) {
                        throw new AssertionError("packet " + packets.size() + " of track " + packet.trackId()
                                + " holds " + packet.size() + " bytes");
                    }
                    joined.writeBytes(packet.data());
                    packets.add(packet);
                    run.output();
                }
                // the packets cut the stream at picture boundaries, so together they are the stream
                if (packets.size() < Fuzz.MAX_PACKETS && !Arrays.equals(joined.toByteArray(), data)) {
                    throw new AssertionError("the " + packets.size() + " packets do not add up to the stream");
                }
            }
        });
        if (track[0] != null && !packets.isEmpty()) {
            FuzzMedia.decode(run, track[0], false, packets.subList(0, Math.min(packets.size(), DECODED_PACKETS)));
        }
    }

    @FuzzTest
    void h264Info(byte[] data) {
        var run = Fuzz.start("h264Info");
        run.value("probe", () -> H264StreamInfo.probe(data)).ifPresent(info -> {
            info.profileIdc();
            info.constraintFlags();
            info.levelIdc();
            info.level();
            info.isConstrainedBaseline();
            info.profileName();
            info.chromaFormat();
            info.bitDepthLuma();
            info.bitDepthChroma();
            info.transformBypass();
            info.separateColorPlanes();
            info.frameMbsOnly();
            info.mbAdaptiveFrameField();
            info.maxNumRefFrames();
            info.nalLengthSize();
            info.frameRate();
            info.sampleAspectRatio();
            info.color();
            info.decoderSupported();
            info.toString();
            if (info.codedWidth() % 16 != 0 || info.codedHeight() % 16 != 0 || info.width() <= 0
                    || info.height() <= 0 || info.width() > info.codedWidth() || info.height() > info.codedHeight()) {
                throw new AssertionError("inconsistent frame size: " + info);
            }
            run.output();
        });
    }

    @FuzzTest
    void heicGainMap(byte[] data) {
        FuzzGainMaps.run("heicGainMap", data, new FuzzGainMaps.Decoder() {
            @Override
            public DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
                return HeicDecoder.decode(source, options);
            }

            @Override
            public Optional<GainMap> gainMap(MediaSource source, ImageDecoderOptions options) {
                return HeicDecoder.decodeGainMap(source, options);
            }

            @Override
            public List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
                return HeicDecoder.auxiliaryImages(source, limits);
            }

            @Override
            public DecodedImage auxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
                return HeicDecoder.decodeAuxiliaryImage(source, index, options);
            }
        });
    }
}
