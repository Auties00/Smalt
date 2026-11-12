package com.github.auties00.smalt.video.dnxhd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.internal.Vc3FrameDecoder;
import com.github.auties00.smalt.video.intra.internal.IntraDecoderCore;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes Avid DNxHD and DNxHR video, the VC-3 format of SMPTE ST 2019-1, as found in QuickTime
 * files ({@code AVdn} and {@code AVdh} sample entries).
 *
 * <p>Every compression ID of ST 2019-1 decodes: the HD-profile IDs 1235 to 1260 (DNxHD at
 * 1920x1080, 1440x1080, 1280x720 and 960x720, progressive and interlaced, 8 and 10-bit, including
 * the 4:4:4 RGB ID 1256 and the adaptive field/frame macroblocks of ID 1260) and the
 * resolution-independent IDs 1270 to 1274 (DNxHR 444, HQX, HQ, SQ and LB, at any size up to
 * 16384x16384, in 4:2:2, 4:2:0 or 4:4:4 Y'CbCr or RGB, at 8, 10 or 12 bits). Frames are
 * independent: every packet yields one key frame with the packet's timestamps, and
 * {@link #flush(Consumer)} has nothing to drain unless {@link DnxhdDecoderOptions#threads()} asks
 * for several frames at once.
 *
 * <p>Y'CbCr frames decode to {@link com.github.auties00.smalt.image.YuvPicture}s (8-bit) or
 * {@link com.github.auties00.smalt.image.Yuv16Picture}s (10 and 12-bit, by default), RGB frames to
 * full-range {@link com.github.auties00.smalt.image.Rgba16Picture}s (or 8-bit
 * {@link com.github.auties00.smalt.image.RgbaPicture}s with high bit depth off). A frame is RGB
 * when its color format flag is set (ST 2019-1 clause 7.2.5); its macroblocks may carry R, G and B
 * or Y'CbCr, which is converted with the BT.709 matrix (clause 8.1.1), and the decoder returns RGB
 * either way, as clause 6 requires. Field-encoded interlaced frames are woven into one picture.
 *
 * <p>A frame with alpha decodes to a picture with an alpha plane (or a meaningful alpha channel),
 * full range at the picture's depth. Both forms of alpha are read: the in-band alpha channel of
 * ST 2019-1 and its Amendment 1:2023 (DCT-coded on every compression ID that permits it,
 * losslessly run-length coded on IDs 1256 and 1270), and the alpha Avid's QuickTime encoders store
 * after the coding units of each frame, followed by the size of the video part, as 8-bit samples
 * or as a second coding unit whose luma holds the alpha. Pictures always carry straight alpha: the
 * video of a frame with in-band alpha that sets the premultiplied alpha flag {@code PMA} of
 * Amendment 1:2023 is divided by that alpha (luma, red, green and blue about black, Cb and Cr
 * about the middle code, a subsampled chroma sample by the mean alpha of the luma samples it
 * covers, rounded to nearest and clipped), and samples whose alpha is zero come out black.
 *
 * <p>Instances are not thread-safe. Large frames are decoded in parallel macroblock rows on the
 * module's shared helper threads unless {@link DnxhdDecoderOptions#threads()} is {@code 1}. With
 * more than one thread, up to that many frames are decoded at once, and a frame reaches the
 * consumer during the {@link #decode(Packet, Consumer)} or {@link #flush(Consumer)} call after it
 * finished, which may follow the call that received its packet, in decoding order; a corrupt frame
 * is reported by that call, and {@link #close()} waits for the frames still being decoded. The
 * packets' arrays must not change until their frames are handed out.
 * {@link #decodeFrame(byte[], int, int)} always returns its frame at once. The pictures do not
 * depend on the threading. A corrupt frame raises {@link MediaException.Malformed} and does not
 * affect the next one.
 *
 * @implNote This implementation follows ST 2019-1 directly, also where widely used decoders depart
 * from it (the reconstruction of AC levels, the weights of ID 1271, the reading of the color format
 * flag, the order and levels of RGB channels). The inverse transform is the single-precision
 * orthonormal DCT the standard defines, so samples may differ from those of a fixed-point decoder by
 * one code value.
 */
public final class DnxhdDecoder implements VideoDecoder {
    /**
     * The track being decoded, or {@code null} for frames without a track.
     */
    private final VideoTrack track;

    private final DnxhdDecoderOptions options;

    /**
     * The calls of this decoder.
     */
    private final IntraDecoderCore core;

    /**
     * Constructs a decoder for frames that come with no track, under the
     * {@link DnxhdDecoderOptions#defaults()}.
     */
    public DnxhdDecoder() {
        this(DnxhdDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for frames that come with no track.
     *
     * @param options the decoding options
     */
    public DnxhdDecoder(DnxhdDecoderOptions options) {
        this.track = null;
        this.options = Objects.requireNonNull(options, "options");
        this.core = core(options);
    }

    /**
     * Constructs a decoder for a DNxHD track under the {@link DnxhdDecoderOptions#defaults()}.
     *
     * @param track the track description; its codec must be {@link Codec#DNXHD}
     * @throws IllegalArgumentException if the track is not a DNxHD track
     */
    public DnxhdDecoder(VideoTrack track) {
        this(track, DnxhdDecoderOptions.defaults());
    }

    /**
     * Constructs a decoder for a DNxHD track.
     *
     * @param track the track description; its codec must be {@link Codec#DNXHD}
     * @param options the decoding options
     * @throws IllegalArgumentException if the track is not a DNxHD track
     */
    public DnxhdDecoder(VideoTrack track, DnxhdDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (track.codec() != Codec.DNXHD) {
            throw new IllegalArgumentException("not a DNxHD track: " + track.codec());
        }
        this.track = track;
        this.options = options;
        this.core = core(options);
    }

    /**
     * Creates the decoding core for a set of options.
     *
     * @param options the decoding options
     * @return the core
     */
    private static IntraDecoderCore core(DnxhdDecoderOptions options) {
        return new IntraDecoderCore("DNxHD", options.threads(),
                threads -> new Vc3FrameDecoder(options.limits(), threads, options.highBitDepth()));
    }

    /**
     * {@return the track being decoded, or {@code null} for a decoder of frames without a track}
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * {@return the decoding options}
     */
    public DnxhdDecoderOptions options() {
        return options;
    }

    /**
     * Decodes one packet holding one DNxHD frame.
     *
     * @param packet the next packet
     * @param output receives the frame, a key frame carrying the packet's timestamps, valid only
     * during the call
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if the frame uses an unknown compression ID
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    @Override
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        core.decode(packet, output);
    }

    /**
     * Decodes one DNxHD frame held in part of an array.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the decoded picture, whose planes the next call of this decoder may overwrite
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
     * @throws MediaException.Malformed if the frame is corrupt
     * @throws MediaException.Unsupported if the frame uses an unknown compression ID
     * @throws MediaException.LimitExceeded if the frame exceeds the limits
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        return core.decodeFrame(data, offset, length);
    }

    /**
     * Hands out the frames still being decoded concurrently; there are none unless the options ask
     * for several threads.
     *
     * @param output receives the frames, valid only during the call
     * @throws MediaException.Malformed if a frame is corrupt
     */
    @Override
    public void flush(Consumer<VideoFrame> output) {
        core.flush(output);
    }

    /**
     * Waits for the frames still being decoded concurrently and drops them.
     */
    @Override
    public void close() {
        core.close();
    }
}
