package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagReadOptions;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.probe.ProbeOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The drivers the fuzz targets share: each hands an input to one family of Smalt entry points under the
 * {@link Fuzz} contract.
 */
public final class FuzzMedia {
    /** The time base of the packets the decoder drivers build: 90 kHz. */
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    /** The pages of a multi-page image a driver reads. */
    private static final int MAX_PAGES = 8;

    private FuzzMedia() {
        throw new AssertionError();
    }

    /**
     * Decodes an image of one format: probes it, decodes it at full size, and then decodes it at a reduced size
     * with every high-depth and channel option, reads its animation frames and reads its pages (one of these
     * three while fuzzing, see {@link FuzzRun#sampled}).
     *
     * <p>An input that {@link Images#detect} does not recognize as {@code format} is skipped, so the
     * target stays on the decoder it is named after.
     *
     * @param format the format of the target
     * @param data the input
     * @return the run
     */
    public static FuzzRun image(ImageFormat format, byte[] data) {
        var run = Fuzz.start(format.name());
        var detected = run.value("detect", () -> Images.detect(MediaSource.of(data)).orElse(null));
        if (detected.isEmpty() || detected.get() != format) {
            run.skip("detected " + detected.map(Enum::name).orElse("nothing"));
            return run;
        }
        var plain = ImageDecoderOptions.builder().limits(Fuzz.LIMITS).build();
        var deep = ImageDecoderOptions.builder()
                .limits(Fuzz.LIMITS)
                .minSize(16, 16)
                .highBitDepth(true)
                .keepChannels(true)
                .convertToSrgb(true)
                .build();
        run.step("probe", () -> Images.probe(MediaSource.of(data)));
        run.step("decode", () -> touch(run, Images.decode(MediaSource.of(data), plain).picture(), true));
        if (run.sampled(data, 0, 3)) {
            run.step("decode reduced", () -> touch(run, Images.decode(MediaSource.of(data), deep).picture(), true));
        }
        if (run.sampled(data, 1, 3)) {
            animation(run, data, plain);
        }
        if (run.sampled(data, 2, 3)) {
            pages(run, data, plain);
        }
        return run;
    }

    private static void animation(FuzzRun run, byte[] data, ImageDecoderOptions plain) {
        run.step("animation", () -> {
            try (var animation = Images.openAnimation(MediaSource.of(data), plain)) {
                for (var index = 0; index < Fuzz.LIMITS.maxFrames(); index++) {
                    var frame = animation.read();
                    if (frame == null) {
                        break;
                    }
                    touch(run, frame.picture(), index == 0);
                }
            }
        });
    }

    private static void pages(FuzzRun run, byte[] data, ImageDecoderOptions plain) {
        run.step("pages", () -> {
            try (var pages = Images.openPages(MediaSource.of(data), plain)) {
                var count = Math.min(pages.pageCount(), MAX_PAGES);
                for (var index = 0; index < count; index++) {
                    pages.probe(index);
                    touch(run, pages.read(index).picture(), false);
                }
            }
        });
    }

    /**
     * Decodes a video stream in the {@link FuzzPackets} format.
     *
     * <p>Options bit 0 asks for high-bit-depth output. A packet the decoder rejects is skipped and decoding
     * goes on with the next one, as a player would, so the error paths leave a decoder that still obeys the
     * contract. The decoder runs on the calling thread only.
     *
     * @param codec the codec of the target
     * @param data the input
     * @return the run
     */
    public static FuzzRun video(Codec codec, byte[] data) {
        return video(codec, codec.name(), data, null);
    }

    /**
     * Decodes a video stream in the {@link FuzzPackets} format with a decoder the target opens itself, as
     * {@link #video(Codec, byte[])} does with the registry's decoder.
     *
     * @param codec the codec of the track
     * @param name the name of the target
     * @param data the input
     * @param open opens the decoder of the track from the options byte of the header, or {@code null} for the
     *             registry's decoder
     * @return the run
     */
    public static FuzzRun video(Codec codec, String name, byte[] data, BiFunction<VideoTrack, Integer, VideoDecoder> open) {
        var run = Fuzz.start(name);
        var input = FuzzPackets.parse(data, FuzzPackets.VIDEO_FIELDS);
        var header = input.header();
        var track = VideoTrack.builder()
                .id(1)
                .codec(codec)
                .timeBase(TIME_BASE)
                .width(header.unsigned(1, 2))
                .height(header.unsigned(3, 2));
        header.config().ifPresent(track::codecConfig);
        var packets = new ArrayList<Packet>(input.packets().size());
        for (var index = 0; index < input.packets().size(); index++) {
            packets.add(packet(input.packets().get(index), index));
        }
        if (open == null) {
            decode(run, track.build(), (header.options() & 1) != 0, packets);
        } else {
            var built = track.build();
            var options = header.options();
            decode(run, run.value("open", () -> open.apply(built, options)), packets);
        }
        return run;
    }

    /**
     * Decodes the packets of a video track on the calling thread, as {@link #video(Codec, byte[])} does: a packet
     * the decoder rejects is skipped and decoding goes on with the next one.
     *
     * @param run the run to add the steps to
     * @param track the track, which gives the codec and its configuration
     * @param highBitDepth whether to ask for high-bit-depth output
     * @param packets the packets, in decoding order
     */
    public static void decode(FuzzRun run, VideoTrack track, boolean highBitDepth, List<Packet> packets) {
        var options = VideoDecoderOptions.builder()
                .limits(Fuzz.LIMITS)
                .threads(1)
                .highBitDepth(highBitDepth)
                .build();
        decode(run, run.value("open", () -> VideoDecoders.create(track, options)), packets);
    }

    /**
     * Decodes packets with a decoder that may have failed to open: a packet the decoder rejects is skipped and
     * decoding goes on with the next one.
     *
     * @param run the run to add the steps to
     * @param decoder the decoder, empty when it could not be opened
     * @param packets the packets, in decoding order
     */
    private static void decode(FuzzRun run, Optional<? extends VideoDecoder> decoder, List<Packet> packets) {
        if (decoder.isEmpty()) {
            return;
        }
        var frames = new int[1];
        Consumer<VideoFrame> sink = frame -> touch(run, frame.picture(), frames[0]++ == 0);
        try (var opened = decoder.get()) {
            for (var index = 0; index < packets.size(); index++) {
                var packet = packets.get(index);
                run.step("packet " + index, () -> opened.decode(packet, sink));
            }
            run.step("flush", () -> opened.flush(sink));
        }
    }

    /**
     * Decodes an audio stream in the {@link FuzzPackets} format.
     *
     * <p>Options bit 0 asks for output at 48 kHz. Rejected packets are skipped as in
     * {@link #video(Codec, byte[])}.
     *
     * @param codec the codec of the target
     * @param data the input
     * @return the run
     */
    public static FuzzRun audio(Codec codec, byte[] data) {
        var run = Fuzz.start(codec.name());
        var input = FuzzPackets.parse(data, FuzzPackets.AUDIO_FIELDS);
        var header = input.header();
        var track = AudioTrack.builder()
                .id(1)
                .codec(codec)
                .timeBase(TIME_BASE)
                .sampleRate(header.unsigned(1, 3))
                .bitsPerSample(header.unsigned(5, 1));
        var channels = header.unsigned(4, 1);
        if (channels > 0) {
            track.layout(ChannelLayout.defaultFor(Math.min(channels, Fuzz.LIMITS.maxChannels())));
        }
        header.config().ifPresent(track::codecConfig);
        var options = AudioDecoderOptions.builder().limits(Fuzz.LIMITS);
        if ((header.options() & 1) != 0) {
            options.preferredSampleRate(48_000);
        }
        var decoder = run.value("open", () -> AudioDecoders.create(track.build(), options.build()));
        if (decoder.isEmpty()) {
            return run;
        }
        Consumer<AudioBuffer> sink = buffer -> touch(run, buffer);
        try (var opened = decoder.get()) {
            opened.sampleRate();
            opened.layout();
            for (var index = 0; index < input.packets().size(); index++) {
                var packet = packet(input.packets().get(index), index);
                run.step("packet " + index, () -> opened.decode(packet, sink));
            }
            run.step("flush", () -> opened.flush(sink));
        }
        return run;
    }

    /**
     * Demuxes a container of one format: reads its tracks, metadata and every packet, seeks to the start
     * and the middle, probes it and reads its tags, and writes the tags back when the format supports it.
     *
     * <p>An input that {@link Containers#detect} does not recognize as {@code format} is skipped.
     *
     * @param format the format of the target
     * @param data the input
     * @return the run
     */
    public static FuzzRun demuxer(ContainerFormat format, byte[] data) {
        var run = Fuzz.start(format.name());
        var detected = run.value("detect", () -> Containers.detect(MediaSource.of(data)).orElse(null));
        if (detected.isEmpty() || detected.get() != format) {
            run.skip("detected " + detected.map(Enum::name).orElse("nothing"));
            return run;
        }
        var options = DemuxerOptions.builder().limits(Fuzz.LIMITS).build();
        run.step("demux", () -> {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data), options)) {
                demuxer.format();
                for (var track : demuxer.tracks()) {
                    demuxer.trackMetadata(track.id());
                }
                demuxer.metadata();
                demuxer.chapters();
                demuxer.attachedPictures();
                var duration = demuxer.durationMicros();
                drain(run, demuxer, Fuzz.MAX_PACKETS);
                if (demuxer.isSeekable()) {
                    demuxer.seek(0);
                    drain(run, demuxer, 16);
                    if (duration > 0) {
                        demuxer.seek(duration / 2);
                        drain(run, demuxer, 16);
                    }
                }
            }
        });
        run.step("probe", () -> Media.probe(MediaSource.of(data), ProbeOptions.builder()
                .limits(Fuzz.LIMITS)
                .attachedPictures(true)
                .readFirstFrames(true)
                .build()));
        if (TagEditor.isSupported(format)) {
            tags(run, data);
        }
        return run;
    }

    /**
     * Reads the tags of a file, writes them back with a changed title, and reads the result.
     *
     * @param run the run to add the steps to
     * @param data the input
     */
    public static void tags(FuzzRun run, byte[] data) {
        var read = run.value("read tags", () -> TagEditor.read(MediaSource.of(data),
                TagReadOptions.builder().limits(Fuzz.LIMITS).build()));
        if (read.isEmpty()) {
            return;
        }
        var edited = read.get().toBuilder().set(StandardTag.TITLE, "fuzz").build();
        var written = run.value("write tags", () -> TagEditor.write(data, edited,
                TagWriteOptions.builder().limits(Fuzz.LIMITS).build()));
        written.ifPresent(bytes -> run.step("reread tags", () -> {
            var reread = TagEditor.read(MediaSource.of(bytes), TagReadOptions.builder().limits(Fuzz.LIMITS).build());
            if (!"fuzz".equals(reread.get(StandardTag.TITLE).orElse(null))) {
                run.reject("the written title is not read back: " + reread.get(StandardTag.TITLE));
            }
            run.output();
        }));
    }

    /**
     * Returns an empty tag set with one title, for building tag inputs.
     *
     * @return the tags
     */
    public static MediaTags titleOnly() {
        return MediaTags.builder().set(StandardTag.TITLE, "fuzz").build();
    }

    private static void drain(FuzzRun run, Demuxer demuxer, int limit) {
        for (var count = 0; count < limit; count++) {
            var packet = demuxer.read();
            if (packet == null) {
                return;
            }
            packet.size();
            run.output();
        }
    }

    private static Packet packet(FuzzPackets.Part part, int index) {
        var pts = index * 3000L;
        return new Packet(1, part.data(), pts, pts, 3000, part.keyframe() || index == 0);
    }

    private static void touch(FuzzRun run, Picture picture, boolean convert) {
        if (picture.width() <= 0 || picture.height() <= 0) {
            throw new IllegalStateException("decoded an empty picture " + picture.width() + "x" + picture.height());
        }
        picture.hasAlpha();
        picture.bitDepth();
        if (convert && (long) picture.width() * picture.height() <= Fuzz.MAX_CONVERTED_PIXELS) {
            ImageOps.toRgba(picture);
        }
        run.output();
    }

    private static void touch(FuzzRun run, AudioBuffer buffer) {
        var frames = buffer.frames();
        if (frames < 0 || frames > buffer.capacity()) {
            throw new IllegalStateException("audio buffer holds " + frames + " of " + buffer.capacity() + " frames");
        }
        if (buffer.channels() != buffer.layout().channels()) {
            throw new IllegalStateException("audio buffer has " + buffer.planes().length + " planes for "
                    + buffer.layout().channels() + " channels");
        }
        for (var plane : buffer.planes()) {
            if (plane.length < frames) {
                throw new IllegalStateException("audio plane shorter than the frame count");
            }
        }
        run.output();
    }
}
