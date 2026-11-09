package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Codes the alpha planes of a video stream as a second stream of the same codec, attached to the
 * packets of the first as {@link com.github.auties00.smalt.track.PacketSideData#alphaFrame()}, as
 * WebM carries transparent VP8 and VP9 video.
 *
 * <p>The owning encoder passes each picture to {@link #push(Picture, long, long)} with the timestamp
 * and duration it codes the picture at, and each packet it produces to {@link #attach(Packet)},
 * which codes the alpha plane of the packet's picture and returns the packet with it. The alpha
 * picture is a limited-range BT.601 4:2:0 picture (the given {@code color}) whose luma is the straight
 * alpha plane at 8 bits and whose chroma is neutral; a picture without alpha is coded opaque. The
 * engine must code every frame it receives at once into one shown packet, without dropping frames or
 * holding them back; it is asked for a key frame whenever the packet it pairs with is one, so that a
 * reader can start both streams at every key frame. Packets flagged {@link PacketFlag#DISCARD} (hidden
 * frames) get no alpha frame, and a packet whose picture was not pushed is returned unchanged.
 * Instances are not thread-safe.
 */
public final class AlphaStreamEncoder implements AutoCloseable {
    /**
     * The most pictures waiting for their packet, more than any lookahead holds.
     */
    private static final int MAX_PENDING = 64;

    private final VideoEncoder engine;
    private final int width;
    private final int height;
    private final ColorInfo color;

    /**
     * The neutral chroma plane, shared by both chroma planes of every alpha picture.
     */
    private final byte[] neutral;

    /**
     * The alpha planes waiting for their packet, in the order they were pushed.
     */
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();

    /**
     * The alpha planes free for reuse.
     */
    private final ArrayDeque<Pending> free = new ArrayDeque<>();

    /**
     * The receiver of the engine's packets.
     */
    private final Consumer<Packet> sink = this::take;

    /**
     * The payload of the shown packet the engine produced in the current call for {@link #expected}, or
     * {@code null}.
     */
    private byte[] coded;

    /**
     * The timestamp of the alpha picture of the current call.
     */
    private long expected;

    /**
     * Constructs an alpha stream over an engine, which it owns.
     *
     * @param engine the encoder of the alpha pictures, of the stream's size, codec and time base
     * @param color the color description the engine codes without conversion, limited-range 4:2:0
     */
    public AlphaStreamEncoder(VideoEncoder engine, ColorInfo color) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.color = Objects.requireNonNull(color, "color");
        this.width = engine.width();
        this.height = engine.height();
        this.neutral = new byte[ChromaFormat.YUV420.chromaWidth(width) * ChromaFormat.YUV420.chromaHeight(height)];
        Arrays.fill(neutral, (byte) 128);
    }

    /**
     * Keeps the alpha plane of a picture until the packet of its timestamp is attached.
     *
     * @param picture the picture, of the stream's size; it is copied
     * @param pts the timestamp the owning encoder codes it at
     * @param duration the duration it codes it with, or {@link Packet#UNKNOWN_DURATION}
     */
    public void push(Picture picture, long pts, long duration) {
        if (pending.size() >= MAX_PENDING) {
            free.add(pending.removeFirst());
        }
        var entry = free.poll();
        if (entry == null) {
            entry = new Pending();
        }
        extract(picture, entry.plane, width, height);
        entry.pts = pts;
        entry.duration = duration;
        pending.add(entry);
    }

    /**
     * Codes the alpha frame of a packet's picture and returns the packet carrying it.
     *
     * <p>The pictures pushed before the packet's and never attached (frames the owning encoder
     * dropped) are discarded.
     *
     * @param packet a packet of the owning encoder
     * @return the packet with its alpha frame, or {@code packet} itself when it is hidden, its picture
     * was not pushed or the engine produced no frame
     */
    public Packet attach(Packet packet) {
        if (packet.flags().contains(PacketFlag.DISCARD)) {
            return packet;
        }
        while (!pending.isEmpty() && pending.peekFirst().pts < packet.pts()) {
            free.add(pending.removeFirst());
        }
        if (pending.isEmpty() || pending.peekFirst().pts != packet.pts()) {
            return packet;
        }
        var entry = pending.removeFirst();
        try {
            if (packet.keyframe()) {
                engine.requestKeyframe();
            }
            coded = null;
            expected = entry.pts;
            engine.encode(new VideoFrame(entry.picture(this), entry.pts, entry.duration, false), sink);
            var alpha = coded;
            coded = null;
            if (alpha == null) {
                return packet;
            }
            return packet.withSideData(packet.sideData().toBuilder().alphaFrame(alpha).build());
        } finally {
            free.add(entry);
        }
    }

    /**
     * Closes the engine.
     */
    @Override
    public void close() {
        engine.close();
    }

    /**
     * Keeps the payload of the engine's shown packet of the picture of the current call.
     *
     * @param packet a packet of the engine
     */
    private void take(Packet packet) {
        if (!packet.flags().contains(PacketFlag.DISCARD) && packet.pts() == expected) {
            coded = packet.data();
        }
    }

    /**
     * Copies the straight alpha of a picture, at 8 bits, into a plane with a tight stride.
     *
     * <p>Deeper alpha is rounded to the nearest 8-bit value; a picture without alpha gives 255.
     *
     * @param picture the picture, of any representation
     * @param plane the destination, {@code width * height} samples
     * @param width the picture width
     * @param height the picture height
     * @throws IllegalArgumentException if the picture is not of that size
     */
    static void extract(Picture picture, byte[] plane, int width, int height) {
        if (picture.width() != width || picture.height() != height) {
            throw new IllegalArgumentException("picture is " + picture.width() + "x" + picture.height()
                    + ", encoder expects " + width + "x" + height);
        }
        if (!picture.hasAlpha()) {
            Arrays.fill(plane, 0, width * height, (byte) 255);
            return;
        }
        switch (picture) {
            case YuvPicture yuv -> {
                for (var row = 0; row < height; row++) {
                    System.arraycopy(yuv.alpha(), row * yuv.alphaStride(), plane, row * width, width);
                }
            }
            case Yuv16Picture yuv -> {
                var max = (1 << yuv.bitDepth()) - 1;
                var alpha = yuv.alpha();
                for (var row = 0; row < height; row++) {
                    var src = row * yuv.alphaStride();
                    var dst = row * width;
                    for (var x = 0; x < width; x++) {
                        plane[dst + x] = (byte) (((alpha[src + x] & 0xFFFF) * 255 + (max >> 1)) / max);
                    }
                }
            }
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                for (var row = 0; row < height; row++) {
                    var src = row * rgba.stride() + 3;
                    var dst = row * width;
                    for (var x = 0; x < width; x++) {
                        plane[dst + x] = pixels[src + 4 * x];
                    }
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                for (var row = 0; row < height; row++) {
                    var src = row * rgba.stride() + 3;
                    var dst = row * width;
                    for (var x = 0; x < width; x++) {
                        plane[dst + x] = (byte) (((pixels[src + 4 * x] & 0xFFFF) * 255 + 32767) / 65535);
                    }
                }
            }
            case ChannelPicture channels -> extractChannel(channels, plane, width, height);
        }
    }

    /**
     * Copies the alpha channel of a channel picture, at 8 bits, into a plane with a tight stride.
     *
     * @param picture the picture, with an alpha channel
     * @param plane the destination
     * @param width the picture width
     * @param height the picture height
     */
    private static void extractChannel(ChannelPicture picture, byte[] plane, int width, int height) {
        var count = picture.channelCount();
        var index = picture.layout().alphaIndex();
        var stride = picture.stride();
        for (var row = 0; row < height; row++) {
            var src = row * stride + index;
            var dst = row * width;
            switch (picture) {
                case Channel8Picture eight -> {
                    var pixels = eight.pixels();
                    for (var x = 0; x < width; x++) {
                        plane[dst + x] = pixels[src + x * count];
                    }
                }
                case Channel16Picture sixteen -> {
                    var pixels = sixteen.pixels();
                    for (var x = 0; x < width; x++) {
                        plane[dst + x] = (byte) (((pixels[src + x * count] & 0xFFFF) * 255 + 32767) / 65535);
                    }
                }
                case ChannelFloatPicture floating -> {
                    var pixels = floating.pixels();
                    for (var x = 0; x < width; x++) {
                        plane[dst + x] = (byte) Math.round(Math.clamp(pixels[src + x * count], 0.0f, 1.0f) * 255);
                    }
                }
            }
        }
    }

    /**
     * An alpha plane waiting for its packet.
     */
    private final class Pending {
        private final byte[] plane = new byte[width * height];

        /**
         * The picture coding {@link #plane}, created on first use.
         */
        private YuvPicture picture;

        private long pts;

        private long duration;

        /**
         * {@return the alpha picture of this plane}
         *
         * @param owner the stream, whose neutral chroma the picture shares
         */
        YuvPicture picture(AlphaStreamEncoder owner) {
            if (picture == null) {
                var chromaWidth = ChromaFormat.YUV420.chromaWidth(width);
                picture = YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(owner.color)
                        .luma(plane, width).chroma(owner.neutral, owner.neutral, chromaWidth).build();
            }
            return picture;
        }
    }
}
