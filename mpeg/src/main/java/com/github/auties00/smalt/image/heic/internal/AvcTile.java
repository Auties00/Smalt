package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.video.h264.internal.H264DecoderEngine;

/**
 * Presents a decoded AVC picture, cropped by its frame cropping rectangle, as a
 * {@link CodedFrame}.
 *
 * <p>Color samples reach the canvas as the H.264 decoder of this module hands out its output:
 * copied at the source depth, the planes of a shallower component shifted left to the depth of
 * the deeper one, or reduced to 8 bits by rounding to nearest. A tile is a view of the engine's
 * picture, valid while the engine keeps it.
 */
final class AvcTile implements CodedFrame {
    /**
     * The decoded picture.
     */
    private final H264DecoderEngine.OutputFrame frame;

    /**
     * Wraps a decoded picture.
     *
     * @param frame the decoded picture
     */
    AvcTile(H264DecoderEngine.OutputFrame frame) {
        this.frame = frame;
    }

    /**
     * {@return whether the samples may differ from the encoder's reconstruction}
     */
    boolean damaged() {
        return frame.damaged();
    }

    @Override
    public int width() {
        return frame.sps().croppedWidth();
    }

    @Override
    public int height() {
        return frame.sps().croppedHeight();
    }

    @Override
    public ChromaFormat format() {
        if (!frame.hasChroma()) {
            return ChromaFormat.MONOCHROME;
        }
        return switch (frame.sps().chromaFormatIdc) {
            case 2 -> ChromaFormat.YUV422;
            case 3 -> ChromaFormat.YUV444;
            default -> ChromaFormat.YUV420;
        };
    }

    @Override
    public int bitDepth() {
        return frame.hasChroma() ? Math.max(frame.bitDepthLuma(), frame.bitDepthChroma()) : frame.bitDepthLuma();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each H.273 code point the VUI signals is kept, and the full range flag when the VUI
     * carries a video signal type; the chroma siting and the mastering display and content light
     * level SEI messages come from the stream too.
     */
    @Override
    public ColorInfo color() {
        var sps = frame.sps();
        var described = sps.colorDescriptionPresent;
        return Colors.ofStream(described ? sps.colorPrimaries : 2, described ? sps.transferCharacteristics : 2,
                described ? sps.matrixCoefficients : 2, sps.videoSignalTypePresent, sps.videoFullRange, frame.color());
    }

    @Override
    public boolean limitedAlpha(HeifProperty.ColorNclx nclx) {
        if (nclx != null) {
            return !nclx.fullRange();
        }
        return frame.sps().videoSignalTypePresent && !frame.sps().videoFullRange;
    }

    @Override
    public Picture lumaPicture() {
        if (frame.wide()) {
            return HeifPictures.lumaPicture(frame.luma16(), offset(0), frame.lumaStride(), width(), height(),
                    frame.bitDepthLuma());
        }
        return HeifPictures.lumaPicture(frame.luma(), offset(0), frame.lumaStride(), width(), height());
    }

    @Override
    public long pts() {
        return frame.pts();
    }

    @Override
    public long duration() {
        return frame.duration();
    }

    @Override
    public boolean eightBit() {
        return !frame.wide();
    }

    @Override
    public byte[] plane8(int plane) {
        return switch (plane) {
            case 0 -> frame.luma();
            case 1 -> frame.cb();
            default -> frame.cr();
        };
    }

    @Override
    public short[] plane16(int plane) {
        return switch (plane) {
            case 0 -> frame.luma16();
            case 1 -> frame.cb16();
            default -> frame.cr16();
        };
    }

    @Override
    public int offset(int plane) {
        var sps = frame.sps();
        if (plane == 0) {
            return frame.lumaOrigin() + sps.cropTop * frame.lumaStride() + sps.cropLeft;
        }
        var format = format();
        return frame.chromaOrigin() + (sps.cropTop >> format.shiftY()) * frame.chromaStride()
                + (sps.cropLeft >> format.shiftX());
    }

    @Override
    public int stride(int plane) {
        return plane == 0 ? frame.lumaStride() : frame.chromaStride();
    }

    @Override
    public int depth(int plane) {
        return plane == 0 ? frame.bitDepthLuma() : frame.bitDepthChroma();
    }
}
