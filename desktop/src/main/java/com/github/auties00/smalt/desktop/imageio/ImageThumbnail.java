package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;

/**
 * A thumbnail of an image read by {@link SmaltImageReader}: a thumbnail item of a HEIF file, or the
 * thumbnail of an EXIF block.
 */
sealed interface ImageThumbnail {
    /**
     * {@return the width in pixels}
     */
    int width();

    /**
     * {@return the height in pixels}
     */
    int height();

    /**
     * A thumbnail item of an AVIF or HEIC file.
     *
     * @param format the file's format
     * @param index the index of the item in the file's auxiliary images
     * @param width the width the file declares
     * @param height the height the file declares
     */
    record Item(ImageFormat format, int index, int width, int height) implements ImageThumbnail {
    }

    /**
     * The thumbnail of an EXIF block.
     */
    sealed interface Embedded extends ImageThumbnail {
        /**
         * Decodes the thumbnail.
         *
         * @return the picture
         * @throws com.github.auties00.smalt.MediaException if the thumbnail cannot be decoded
         */
        Picture decode();
    }

    /**
     * A JPEG thumbnail of an EXIF block.
     *
     * @param jpeg the JPEG file
     * @param width the width its header declares
     * @param height the height its header declares
     */
    record Jpeg(byte[] jpeg, int width, int height) implements Embedded {
        @Override
        public Picture decode() {
            return Images.decode(MediaSource.of(jpeg)).picture();
        }
    }

    /**
     * An uncompressed 8-bit RGB thumbnail of an EXIF block.
     *
     * @param samples the interleaved R, G, B samples, row by row
     * @param width the width in pixels
     * @param height the height in pixels
     */
    record Rgb(byte[] samples, int width, int height) implements Embedded {
        @Override
        public Picture decode() {
            var picture = RgbaPicture.allocate(width, height, false);
            var pixels = picture.pixels();
            var stride = picture.stride();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var from = (y * width + x) * 3;
                    var at = y * stride + x * 4;
                    pixels[at] = samples[from];
                    pixels[at + 1] = samples[from + 1];
                    pixels[at + 2] = samples[from + 2];
                    pixels[at + 3] = (byte) 0xFF;
                }
            }
            return picture;
        }
    }
}
