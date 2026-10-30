package com.github.auties00.smalt.mpeg.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.aac.AacDecoder;
import com.github.auties00.smalt.audio.aac.AacDecoderOptions;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.heic.HeicDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzGainMaps;
import com.github.auties00.smalt.testkit.fuzz.FuzzMedia;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264FirstPassStats;
import com.github.auties00.smalt.video.hevc.HevcDecoder;
import com.github.auties00.smalt.video.hevc.HevcDecoderOptions;
import com.github.auties00.smalt.video.mpeg4.Mpeg4Decoder;
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
 * MPEG-4 Part 2 decoder decoding the two layers of a scalable object (see {@link #mpeg4Layers}), of the H.264
 * parameter set parser, of the H.264 decoder decoding every layer of a stream (see {@link #h264Layers}) and of the
 * AAC decoder decoding the layers of a scalable object from several elementary streams (see {@link #aacScalable})
 * or a downmix with a separate MPEG Surround elementary stream (see {@link #aacSurround}), and of the H.264 first
 * pass statistics (see {@link #h264FirstPassStats}).
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
    void mpeg1Video(byte[] data) {
        FuzzMedia.video(Codec.MPEG1_VIDEO, data);
    }

    @FuzzTest
    void mpeg2Video(byte[] data) {
        FuzzMedia.video(Codec.MPEG2_VIDEO, data);
    }

    /**
     * Decodes the two layers of a scalable MPEG-4 Part 2 object: a 4-byte big-endian length, the base layer's
     * elementary stream of that length and the enhancement layer's elementary stream, each split by
     * {@link Mpeg4ElementaryStream}, whose packets are fed alternately. A packet the decoder rejects is skipped and
     * decoding goes on with the next one.
     */
    @FuzzTest
    void mpeg4Layers(byte[] data) {
        var run = Fuzz.start("mpeg4Layers");
        if (data.length < 4) {
            return;
        }
        var length = (int) Math.min(data.length - 4L, ((data[0] & 0xFFL) << 24 | (data[1] & 0xFF) << 16
                | (data[2] & 0xFF) << 8 | (data[3] & 0xFF)));
        var layers = new byte[][]{Arrays.copyOfRange(data, 4, 4 + length),
                Arrays.copyOfRange(data, 4 + length, data.length)};
        var options = DemuxerOptions.builder().limits(Fuzz.LIMITS).build();
        var tracks = new VideoTrack[2];
        var packets = List.of(new ArrayList<Packet>(), new ArrayList<Packet>());
        for (var layer = 0; layer < 2; layer++) {
            var index = layer;
            run.step("split " + layer, () -> {
                try (var stream = Mpeg4ElementaryStream.open(MediaSource.of(layers[index]), options)) {
                    tracks[index] = stream.track().toBuilder().id(index + 1).build();
                    Packet packet;
                    while (packets.get(index).size() < DECODED_PACKETS && (packet = stream.read()) != null) {
                        packets.get(index).add(packet.withTrackId(index + 1));
                    }
                }
            });
        }
        if (tracks[0] == null || tracks[1] == null || tracks[0].codec() != Codec.MPEG4_PART2
                || tracks[1].codec() != Codec.MPEG4_PART2) {
            return;
        }
        var decoderOptions = VideoDecoderOptions.builder().limits(Fuzz.LIMITS).threads(1).build();
        var decoder = run.value("open", () -> new Mpeg4Decoder(tracks[0], tracks[1], decoderOptions));
        if (decoder.isEmpty()) {
            return;
        }
        Consumer<VideoFrame> sink = frame -> {
            var picture = frame.picture();
            if (picture.width() <= 0 || picture.height() <= 0) {
                throw new IllegalStateException("decoded an empty picture " + picture.width() + "x" + picture.height());
            }
            run.output();
        };
        try (var opened = decoder.get()) {
            for (var index = 0; index < DECODED_PACKETS; index++) {
                for (var layer = 0; layer < 2; layer++) {
                    if (index < packets.get(layer).size()) {
                        var packet = packets.get(layer).get(index);
                        run.step("packet " + layer + "." + index, () -> opened.decode(packet, sink));
                    }
                }
            }
            run.step("flush", () -> opened.flush(sink));
        }
    }

    @FuzzTest
    void h263(byte[] data) {
        FuzzMedia.video(Codec.H263, data);
    }

    @FuzzTest
    void flv1(byte[] data) {
        FuzzMedia.video(Codec.FLV1, data);
    }

    @FuzzTest
    void aac(byte[] data) {
        FuzzMedia.audio(Codec.AAC, data);
    }

    /**
     * Decodes a scalable AAC object whose layers come in several elementary streams: the codec configuration of the
     * {@link FuzzPackets} input lists the {@code AudioSpecificConfig} of each stream and every packet the access
     * units of one frame, in the format of {@link #units(byte[])}; bits 0 to 2 of the options give the highest layer
     * to decode, 7 for every layer. Inputs whose first configuration is not of object type 6 or 20 are skipped.
     */
    @FuzzTest
    void aacScalable(byte[] data) {
        var run = Fuzz.start("aacScalable");
        var input = FuzzPackets.parse(data, FuzzPackets.AUDIO_FIELDS);
        var configs = units(input.header().config().orElse(new byte[0]));
        var type = configs.isEmpty() || configs.getFirst().length == 0 ? 0 : (configs.getFirst()[0] & 0xFF) >>> 3;
        if (type != 6 && type != 20) {
            run.skip("not scalable AAC");
            return;
        }
        var options = AacDecoderOptions.builder().limits(Fuzz.LIMITS);
        var layer = input.header().options() & 7;
        if (layer != 7) {
            options.maxLayer(layer);
        }
        decodeStreams(run, input, configs, options.build());
    }

    /**
     * Decodes an AAC downmix with a separate MPEG Surround elementary stream: the codec configuration of the
     * {@link FuzzPackets} input lists the {@code AudioSpecificConfig} of the downmix and of the MPEG Surround stream
     * (object type 30), and every packet their access units, in the format of {@link #units(byte[])}; bit 0 of the
     * options turns MPEG Surround off. Inputs whose second configuration is not of object type 30 are skipped.
     */
    @FuzzTest
    void aacSurround(byte[] data) {
        var run = Fuzz.start("aacSurround");
        var input = FuzzPackets.parse(data, FuzzPackets.AUDIO_FIELDS);
        var configs = units(input.header().config().orElse(new byte[0]));
        if (configs.size() < 2 || configs.get(1).length == 0 || (configs.get(1)[0] & 0xFF) >>> 3 != 30) {
            run.skip("no MPEG Surround stream");
            return;
        }
        var options = AacDecoderOptions.builder().limits(Fuzz.LIMITS)
                .mpegSurround((input.header().options() & 1) == 0).build();
        decodeStreams(run, input, configs, options);
    }

    /**
     * Decodes an AAC object from the access units of several elementary streams, one track per configuration.
     *
     * @param run the fuzz run
     * @param input the parsed input, every packet a list of access units
     * @param configs the {@code AudioSpecificConfig} of each elementary stream
     * @param options the decoder options
     */
    private static void decodeStreams(FuzzRun run, FuzzPackets input, List<byte[]> configs,
                                      AacDecoderOptions options) {
        var tracks = new ArrayList<AudioTrack>();
        for (var config : configs) {
            tracks.add(AudioTrack.builder().id(tracks.size() + 1).codec(Codec.AAC).timeBase(new Rational(1, 48_000))
                    .codecConfig(config).build());
        }
        var decoder = run.value("open", () -> new AacDecoder(tracks, options));
        if (decoder.isEmpty()) {
            return;
        }
        Consumer<AudioBuffer> sink = buffer -> {
            for (var channel = 0; channel < buffer.channels(); channel++) {
                var plane = buffer.plane(channel);
                for (var i = 0; i < buffer.frames(); i++) {
                    if (!Float.isFinite(plane[i])) {
                        throw new IllegalStateException("decoded a non-finite sample");
                    }
                }
            }
            run.output();
        };
        try (var opened = decoder.get()) {
            for (var index = 0; index < input.packets().size(); index++) {
                var packets = new ArrayList<Packet>();
                for (var unit : units(input.packets().get(index).data())) {
                    packets.add(new Packet(1, unit, index * 1024L, index * 1024L, 1024, true));
                }
                run.step("packet " + index, () -> opened.decode(packets, sink));
            }
        }
    }

    /**
     * Splits a list of byte strings: a count byte followed by, for each entry, its 16-bit big-endian length and its
     * bytes. A truncated entry ends the list.
     *
     * @param data the list
     * @return the entries
     */
    static List<byte[]> units(byte[] data) {
        var units = new ArrayList<byte[]>();
        if (data.length == 0) {
            return units;
        }
        var position = 1;
        for (var i = 0; i < (data[0] & 0xFF) && position + 2 <= data.length; i++) {
            var length = (data[position] & 0xFF) << 8 | data[position + 1] & 0xFF;
            position += 2;
            if (position + length > data.length) {
                break;
            }
            units.add(Arrays.copyOfRange(data, position, position + length));
            position += length;
        }
        return units;
    }

    /**
     * Joins byte strings into the list format of {@link #units(byte[])}.
     *
     * @param units the entries, at most 255, each at most 65535 bytes
     * @return the list
     */
    static byte[] units(List<byte[]> units) {
        var out = new ByteArrayOutputStream();
        out.write(units.size());
        for (var unit : units) {
            out.write(unit.length >>> 8);
            out.write(unit.length);
            out.writeBytes(unit);
        }
        return out.toByteArray();
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

    /**
     * Restores H.264 first pass statistics, checks that they serialize back to the input, and for statistics of
     * pictures of at most 64x64 pixels codes the second pass of a few more frames than they measured with them.
     */
    @FuzzTest
    void h264FirstPassStats(byte[] data) {
        var run = Fuzz.start("h264FirstPassStats");
        var parsed = run.value("parse", () -> H264FirstPassStats.fromByteArray(data));
        if (parsed.isEmpty()) {
            return;
        }
        var stats = parsed.get();
        if (!Arrays.equals(stats.toByteArray(), data)) {
            throw new AssertionError("first pass statistics do not serialize back to their input");
        }
        run.output();
        if (stats.width() > 64 || stats.height() > 64) {
            run.skip("statistics of pictures larger than 64x64");
            return;
        }
        run.step("second pass", () -> {
            var options = H264EncoderOptions.builder().bitrate(100_000).lookahead(4).bFrames(2).limits(Fuzz.LIMITS)
                    .firstPassStats(stats).build();
            var picture = YuvPicture.allocate(stats.width(), stats.height(), ChromaFormat.YUV420, false,
                    ColorInfo.BT709_LIMITED);
            Arrays.fill(picture.y(), (byte) 100);
            Arrays.fill(picture.u(), (byte) 128);
            Arrays.fill(picture.v(), (byte) 128);
            try (var encoder = new H264Encoder(stats.width(), stats.height(), options)) {
                var frames = Math.min(DECODED_PACKETS, stats.frameCount() + 2);
                for (var i = 0; i < frames; i++) {
                    picture.y()[i % picture.y().length] = (byte) (40 * i);
                    encoder.encode(new VideoFrame(picture, i, 0, false), packet -> run.output());
                }
                encoder.flush(packet -> run.output());
            }
        });
    }
}
