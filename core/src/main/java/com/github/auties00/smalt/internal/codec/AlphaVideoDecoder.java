package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes a track that carries transparency as a second coded stream
 * ({@link com.github.auties00.smalt.track.VideoTrack#hasAlpha()}): the packets with one decoder and
 * their {@linkplain PacketSideData#alphaFrame() alpha frames} with another, whose luma plane becomes
 * the alpha plane of the frame of the same timestamp.
 *
 * <p>Frames come out as {@link YuvPicture}s or {@link Yuv16Picture}s with an alpha plane; an alpha
 * frame of another bit depth is rescaled to the frame's, rounding to nearest. A frame whose packet
 * carries no alpha frame, whose alpha frame the alpha decoder rejects (as corrupt, unsupported or
 * over the limits) or decodes to another size, is opaque. A frame of any other picture type is passed
 * on unchanged. Frames of both streams are expected in presentation order: a decoded alpha plane
 * older than the frame being passed on is discarded. Instances are not thread-safe.
 */
public final class AlphaVideoDecoder implements VideoDecoder {
    /**
     * The most decoded alpha planes waiting for their frame; older ones are dropped.
     */
    private static final int MAX_PENDING = 16;

    /**
     * The number of wrapped pictures kept for reuse, one per picture buffer a decoder recycles.
     */
    private static final int WRAPPED = 8;

    private final VideoDecoder color;
    private final VideoDecoder alpha;

    /**
     * The decoded alpha planes waiting for their frame, oldest first.
     */
    private final ArrayDeque<AlphaPlane> pending = new ArrayDeque<>();

    /**
     * The alpha planes free for reuse.
     */
    private final ArrayDeque<AlphaPlane> free = new ArrayDeque<>();

    /**
     * The receiver of the alpha decoder's frames.
     */
    private final Consumer<VideoFrame> alphaSink = this::keep;

    /**
     * The receiver of the color decoder's frames.
     */
    private final Consumer<VideoFrame> colorSink = this::merge;

    /**
     * The decoded pictures the entries of {@link #wrappedPictures} were made from, by identity.
     */
    private final Picture[] wrappedSources = new Picture[WRAPPED];

    /**
     * The alpha planes the entries of {@link #wrappedPictures} carry, by identity.
     */
    private final Object[] wrappedPlanes = new Object[WRAPPED];

    /**
     * Pictures sharing the planes of a decoded picture plus an alpha plane, reused while both match.
     */
    private final Picture[] wrappedPictures = new Picture[WRAPPED];

    /**
     * The next entry of {@link #wrappedPictures} to replace.
     */
    private int nextWrapped;

    /**
     * The receiver of the frames of the current call.
     */
    private Consumer<VideoFrame> output;

    /**
     * An opaque 8-bit plane, or {@code null} until needed.
     */
    private byte[] opaque8;

    /**
     * An opaque high bit depth plane, or {@code null} until needed.
     */
    private short[] opaque16;

    /**
     * The bit depth {@link #opaque16} is opaque at.
     */
    private int opaque16Depth;

    /**
     * A scratch 8-bit plane for rescaled alpha.
     */
    private byte[] scratch8;

    /**
     * A scratch high bit depth plane for rescaled alpha.
     */
    private short[] scratch16;

    /**
     * Constructs a decoder over the decoders of the two streams, which it owns.
     *
     * @param color the decoder of the packets
     * @param alpha the decoder of the alpha frames, of the same codec
     */
    public AlphaVideoDecoder(VideoDecoder color, VideoDecoder alpha) {
        this.color = Objects.requireNonNull(color, "color");
        this.alpha = Objects.requireNonNull(alpha, "alpha");
    }

    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
        var coded = packet.sideData().alphaFrame().orElse(null);
        if (coded != null) {
            try {
                alpha.decode(new Packet(packet.trackId(), coded, packet.pts(), packet.dts(), packet.duration(),
                        packet.flags(), PacketSideData.NONE), alphaSink);
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
                // the frame is shown opaque, as players show a frame whose alpha is lost
            }
        }
        this.output = output;
        try {
            color.decode(packet, colorSink);
        } finally {
            this.output = null;
        }
    }

    @Override
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
        try {
            alpha.flush(alphaSink);
        } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded exception) {
            // the frames still held are shown opaque
        }
        this.output = output;
        try {
            color.flush(colorSink);
        } finally {
            this.output = null;
            free.addAll(pending);
            pending.clear();
        }
    }

    @Override
    public void close() {
        try {
            color.close();
        } finally {
            alpha.close();
        }
    }

    /**
     * Copies the luma plane of a decoded alpha frame until the frame of its timestamp arrives.
     *
     * @param frame the alpha decoder's frame, valid only during the call
     */
    private void keep(VideoFrame frame) {
        var picture = frame.picture();
        if (!(picture instanceof YuvPicture) && !(picture instanceof Yuv16Picture)) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            free.add(pending.removeFirst());
        }
        var plane = free.poll();
        if (plane == null) {
            plane = new AlphaPlane();
        }
        plane.load(picture, frame.pts());
        pending.add(plane);
    }

    /**
     * Passes on a decoded frame with the alpha plane of its timestamp.
     *
     * @param frame the color decoder's frame, valid only during the call
     */
    private void merge(VideoFrame frame) {
        var picture = frame.picture();
        if (!(picture instanceof YuvPicture) && !(picture instanceof Yuv16Picture)) {
            output.accept(frame);
            return;
        }
        AlphaPlane plane = null;
        while (!pending.isEmpty() && frame.pts() != Packet.NO_TIMESTAMP) {
            var first = pending.peekFirst();
            if (first.pts != Packet.NO_TIMESTAMP && first.pts >= frame.pts()) {
                break;
            }
            free.add(pending.removeFirst());
        }
        for (var candidate : pending) {
            if (candidate.pts == frame.pts()) {
                plane = candidate;
                break;
            }
        }
        if (plane != null) {
            pending.remove(plane);
        }
        try {
            var usable = plane != null && plane.width == picture.width() && plane.height == picture.height();
            var samples = switch (picture) {
                case YuvPicture yuv -> usable ? plane.eightBit(this) : opaque8(yuv.width() * yuv.height());
                case Yuv16Picture yuv -> usable ? plane.deep(this, yuv.bitDepth())
                        : opaque16(yuv.width() * yuv.height(), yuv.bitDepth());
                default -> throw new AssertionError();
            };
            output.accept(new VideoFrame(wrap(picture, samples), frame.pts(), frame.duration(), frame.keyframe(),
                    frame.sideData()));
        } finally {
            if (plane != null) {
                free.add(plane);
            }
        }
    }

    /**
     * Returns a picture sharing the planes of a decoded picture, with an alpha plane of its size and tight
     * stride.
     *
     * @param picture the decoded picture
     * @param samples the alpha samples, a {@code byte[]} for a {@link YuvPicture} or a {@code short[]} otherwise
     * @return the picture, reused when the same picture and plane were wrapped before
     */
    private Picture wrap(Picture picture, Object samples) {
        for (var index = 0; index < WRAPPED; index++) {
            if (wrappedSources[index] == picture && wrappedPlanes[index] == samples) {
                return wrappedPictures[index];
            }
        }
        Picture wrapped = switch (picture) {
            case YuvPicture yuv -> yuv.toBuilder().alpha((byte[]) samples, yuv.width()).build();
            case Yuv16Picture yuv -> yuv.toBuilder().alpha((short[]) samples, yuv.width()).build();
            default -> throw new AssertionError();
        };
        wrappedSources[nextWrapped] = picture;
        wrappedPlanes[nextWrapped] = samples;
        wrappedPictures[nextWrapped] = wrapped;
        nextWrapped = (nextWrapped + 1) % WRAPPED;
        return wrapped;
    }

    /**
     * {@return an opaque 8-bit plane of at least a size}
     *
     * @param size the number of samples
     */
    private byte[] opaque8(int size) {
        if (opaque8 == null || opaque8.length != size) {
            opaque8 = new byte[size];
            Arrays.fill(opaque8, (byte) 255);
        }
        return opaque8;
    }

    /**
     * {@return an opaque plane of a size at a bit depth}
     *
     * @param size the number of samples
     * @param depth the bit depth
     */
    private short[] opaque16(int size, int depth) {
        if (opaque16 == null || opaque16.length != size || opaque16Depth != depth) {
            opaque16 = new short[size];
            Arrays.fill(opaque16, (short) ((1 << depth) - 1));
            opaque16Depth = depth;
        }
        return opaque16;
    }

    /**
     * {@return a scratch 8-bit plane of a size}
     *
     * @param size the number of samples
     */
    private byte[] scratch8(int size) {
        if (scratch8 == null || scratch8.length != size) {
            scratch8 = new byte[size];
        }
        return scratch8;
    }

    /**
     * {@return a scratch high bit depth plane of a size}
     *
     * @param size the number of samples
     */
    private short[] scratch16(int size) {
        if (scratch16 == null || scratch16.length != size) {
            scratch16 = new short[size];
        }
        return scratch16;
    }

    /**
     * The luma plane of a decoded alpha frame, at its own bit depth and with a tight stride.
     */
    private static final class AlphaPlane {
        private int width;
        private int height;

        /**
         * The bit depth of the samples, 8 for {@link #eight}.
         */
        private int depth;

        /**
         * The 8-bit samples, or {@code null} when the frame was deeper.
         */
        private byte[] eight;

        /**
         * The deeper samples, or {@code null} when the frame was 8-bit.
         */
        private short[] deep;

        /**
         * The buffer {@link #eight} is taken from.
         */
        private byte[] eightBuffer;

        /**
         * The buffer {@link #deep} is taken from.
         */
        private short[] deepBuffer;

        private long pts;

        /**
         * Copies the luma plane of a picture.
         *
         * @param picture a {@link YuvPicture} or a {@link Yuv16Picture}
         * @param pts the frame's timestamp
         */
        void load(Picture picture, long pts) {
            this.pts = pts;
            width = picture.width();
            height = picture.height();
            var size = width * height;
            switch (picture) {
                case YuvPicture yuv -> {
                    if (eightBuffer == null || eightBuffer.length != size) {
                        eightBuffer = new byte[size];
                    }
                    for (var row = 0; row < height; row++) {
                        System.arraycopy(yuv.y(), row * yuv.yStride(), eightBuffer, row * width, width);
                    }
                    eight = eightBuffer;
                    deep = null;
                    depth = 8;
                }
                case Yuv16Picture yuv -> {
                    if (deepBuffer == null || deepBuffer.length != size) {
                        deepBuffer = new short[size];
                    }
                    for (var row = 0; row < height; row++) {
                        System.arraycopy(yuv.y(), row * yuv.yStride(), deepBuffer, row * width, width);
                    }
                    deep = deepBuffer;
                    eight = null;
                    depth = yuv.bitDepth();
                }
                default -> throw new AssertionError();
            }
        }

        /**
         * {@return the samples at 8 bits, rescaled into a scratch plane of the decoder when deeper}
         *
         * @param owner the decoder owning the scratch planes
         */
        byte[] eightBit(AlphaVideoDecoder owner) {
            if (eight != null) {
                return eight;
            }
            var out = owner.scratch8(deep.length);
            var max = (1 << depth) - 1;
            for (var index = 0; index < deep.length; index++) {
                out[index] = (byte) (((deep[index] & 0xFFFF) * 255 + (max >> 1)) / max);
            }
            return out;
        }

        /**
         * {@return the samples at a bit depth above 8, rescaled into a scratch plane of the decoder when needed}
         *
         * @param owner the decoder owning the scratch planes
         * @param target the bit depth
         */
        short[] deep(AlphaVideoDecoder owner, int target) {
            if (deep != null && depth == target) {
                return deep;
            }
            var size = width * height;
            var out = owner.scratch16(size);
            var to = (1 << target) - 1;
            if (eight != null) {
                for (var index = 0; index < size; index++) {
                    out[index] = (short) (((eight[index] & 0xFF) * to + 127) / 255);
                }
            } else {
                var from = (1 << depth) - 1;
                for (var index = 0; index < size; index++) {
                    out[index] = (short) ((int) (((deep[index] & 0xFFFF) * (long) to + (from >> 1)) / from));
                }
            }
            return out;
        }
    }
}
