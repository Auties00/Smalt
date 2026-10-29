package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.heic.internal.HeicAnimationWriter;
import com.github.auties00.smalt.image.heic.internal.HeicStillEncoder;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Encodes HEIC images (ISO/IEC 23008-12 with HEVC images, MIAF ISO/IEC 23000-22): still pictures as HEVC image
 * items, and animations as HEVC image sequences.
 *
 * <p>A still picture is coded as one HEVC picture of the Main Still Picture profile (8-bit) or the Main 10 Still
 * Picture profile (10-bit) at a constant quality, with
 * {@link com.github.auties00.smalt.video.hevc.encoder.HevcEncoder HevcEncoder}.
 * The file holds the color item and, for a picture with transparency, an alpha auxiliary item; its properties
 * describe the size, bit depth, color (an {@code nclx} description and an optional ICC profile), the display
 * orientation ({@code irot} and {@code imir}) and, for high dynamic range content, the content light level and
 * mastering display. Pictures over the size of HEVC level 5.1, or any picture when the options ask for it, are
 * split into an image grid of separately coded images. Exif and XMP metadata become items that describe the
 * primary item. The file's brands are {@code heic} (or {@code heix} at 10 bits), {@code mif1} and {@code miaf}.
 *
 * <p>Every coded image is 4:2:0: RGB pictures are converted with the options' color description, and planar
 * pictures keep their own description, their chroma resampled to 4:2:0 and their depth rescaled to 8 or 10 bits.
 * A picture of odd width or height is coded one sample larger and cropped back by a clean aperture property.
 *
 * <p>Encoding runs on the module's shared helper threads, the calling thread taking part, unless the options ask
 * for one thread; the file does not depend on the threading.
 */
public final class HeicEncoder {
    /**
     * Prevents instantiation.
     */
    private HeicEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a HEIC file with the {@link HeicEncoderOptions#defaults()}.
     *
     * @param picture the image to encode
     * @return the HEIC file
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     * @see #encode(Picture, HeicEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, HeicEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as a HEIC file.
     *
     * <p>An {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture} or
     * {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture} contributes an alpha item only when it
     * reports alpha and is not fully opaque; so does a planar picture with an alpha plane.
     *
     * @param picture the image to encode
     * @param options the encoder settings
     * @return the HEIC file
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits, needs a grid of more than
     * 256 images per side or more images than the file's 65535 items hold, or the file would exceed 2 GiB
     * @throws MediaException.Unsupported if the picture's color description needs 4:4:4 chroma (the identity
     * or a YCgCo-R matrix)
     */
    public static byte[] encode(Picture picture, HeicEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            LimitChecks.checkPicture(options.limits(), picture.width(), picture.height());
            try {
                return HeicStillEncoder.encode(picture, options);
            } catch (OutOfMemoryError error) {
                throw new MediaException.LimitExceeded("out of memory while encoding a HEIC image");
            }
        }
    }

    /**
     * Encodes a picture as a HEIC file and writes it to a sink, from its position.
     *
     * <p>The bytes written are those {@link #encode(Picture, HeicEncoderOptions)} returns.
     *
     * @param picture the image to encode
     * @param options the encoder settings
     * @param sink the output; not closed
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     * @throws MediaException.Unsupported if the picture's color description cannot be coded
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, HeicEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Creates a writer that encodes an animation as a HEIC image sequence ({@code msf1}) into a sink.
     *
     * <p>The color frames form an HEVC track of the Main (8-bit) or Main 10 profile; transparent animations
     * carry an alpha track that refers to it. Whether the frames after the first predict from other frames
     * follows {@link HeicEncoderOptions#interFrames()}. The file also holds the first frame as its primary image,
     * for readers that only show still images, and the Exif and XMP metadata of the options become items that
     * describe it. The major brand is {@code heic} ({@code heix} at 10 bits), as for a still image, and the
     * compatible brands add {@code msf1}, {@code hevc} ({@code hevx}) and {@code iso8} for the sequence.
     *
     * @param width the canvas width, positive
     * @param height the canvas height, positive
     * @param options the encoder settings, including the loop count
     * @param sink the output, written from its position when the writer finishes; not closed
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if a dimension is not positive or exceeds the HEVC picture size
     * @throws MediaException.LimitExceeded if the canvas exceeds the options' limits; the writer's {@code finish}
     * throws it when the file would exceed 2 GiB
     */
    public static AnimationWriter openAnimationWriter(int width, int height, HeicEncoderOptions options,
                                                      MediaSink sink) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("invalid canvas size " + width + "x" + height);
        }
        LimitChecks.checkPicture(options.limits(), width, height);
        return new HeicAnimationWriter(width, height, options, sink);
    }
}
