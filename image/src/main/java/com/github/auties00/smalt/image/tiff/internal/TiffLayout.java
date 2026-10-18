package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.image.Orientation;

/**
 * Holds the sample layout of a directory, as both its description and its decoder read it.
 *
 * <p>A missing photometric interpretation is black-is-zero for one color channel and RGB for
 * three or more. The alpha sample is the first extra sample when it is associated or unassociated
 * alpha, or when it is unspecified and there are more than three samples per pixel (CMYK
 * excepted); a four-sample RGB image without {@code ExtraSamples} carries associated alpha.
 * Instances are immutable and thread-safe.
 *
 * @implNote This implementation reads the tags as libtiff's {@code TIFFRGBAImageOK} and
 * {@code TIFFRGBAImageBegin} do.
 * @param samples the samples per pixel, from 0 to 65535
 * @param photometric the {@code PhotometricInterpretation} code, as stored or as defaulted
 * @param colorChannels the color samples the interpretation needs: 1, 3 or 4, and 1 for an
 * interpretation the decoder does not support
 * @param alpha the index of the alpha sample in a pixel, or -1 without alpha
 * @param associated whether the alpha is associated (premultiplied)
 * @param planar whether the samples are stored in separate planes
 * @param orientation the display orientation, {@link Orientation#NORMAL} when absent or invalid
 */
public record TiffLayout(int samples, int photometric, int colorChannels, int alpha, boolean associated,
                         boolean planar, Orientation orientation) {
    /**
     * Reads the layout of a directory.
     *
     * @param directory the directory whose tags are read
     * @return the layout
     * @throws com.github.auties00.smalt.MediaException.Malformed if a tag it reads has an invalid type or
     * truncated values
     */
    public static TiffLayout of(TiffDirectory directory) {
        var samples = (int) Math.clamp(directory.longValue(TiffTags.SAMPLES_PER_PIXEL, 1), 0, 0xFFFF);
        var extrasIndex = directory.find(TiffTags.EXTRA_SAMPLES);
        var extras = extrasIndex < 0 ? 0 : (int) Math.min(64, directory.valueCount(extrasIndex));
        var photometric = (int) directory.longValue(TiffTags.PHOTOMETRIC,
                samples - extras >= 3 ? TiffPageDecoder.RGB : TiffPageDecoder.BLACK_IS_ZERO);
        var colors = switch (photometric) {
            case TiffPageDecoder.RGB, TiffPageDecoder.YCBCR, TiffPageDecoder.CIELAB, TiffPageDecoder.ICCLAB,
                 TiffPageDecoder.ITULAB, TiffPageDecoder.LOG_LUV -> 3;
            case TiffPageDecoder.SEPARATED -> 4;
            default -> 1;
        };
        var alpha = -1;
        var associated = false;
        if (samples > colors) {
            if (extras > 0) {
                var kind = directory.longValue(TiffTags.EXTRA_SAMPLES, 0);
                if (kind == 1 || kind == 2 || (kind == 0 && samples > 3 && photometric != TiffPageDecoder.SEPARATED)) {
                    alpha = colors;
                    associated = kind != 2;
                }
            } else if (photometric == TiffPageDecoder.RGB && samples == 4) {
                alpha = 3;
                associated = true;
            }
        }
        var planar = samples > 1 && directory.longValue(TiffTags.PLANAR_CONFIGURATION, 1) == 2;
        var orientation = Orientation.ofExif((int) Math.clamp(directory.longValue(TiffTags.ORIENTATION, 1), 0, 9));
        return new TiffLayout(samples, photometric, colors, alpha, associated, planar, orientation);
    }
}
