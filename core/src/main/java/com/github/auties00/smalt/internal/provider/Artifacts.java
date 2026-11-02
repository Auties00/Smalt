package com.github.auties00.smalt.internal.provider;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.track.Codec;

import java.util.Locale;

/**
 * Names the Smalt artifact that implements each codec, image format and container format, so the registries can
 * tell a caller which artifact to add when no provider handles an input.
 *
 * <p>The table is metadata only: it names artifact ids, never a codec class, so core still finds
 * every codec and container through the service-provider interfaces. A codec or format that no Smalt artifact
 * implements has no artifact.
 */
public final class Artifacts {
    /**
     * The artifact of the image codecs and of VP8.
     */
    private static final String IMAGE = "smalt-image";

    /**
     * The artifact of the audio codecs.
     */
    private static final String AUDIO = "smalt-audio";

    /**
     * The artifact of the royalty-free video codecs other than VP8, and of AVIF.
     */
    private static final String VIDEO = "smalt-video";

    /**
     * The artifact of the patent-pool codecs, and of HEIC.
     */
    private static final String MPEG = "smalt-mpeg";

    /**
     * The artifact of the DNG and camera raw decoders.
     */
    private static final String CAMERA_RAW = "smalt-raw";

    /**
     * Prevents instantiation.
     */
    private Artifacts() {
        throw new AssertionError();
    }

    /**
     * Returns the artifact whose decoder handles a codec.
     *
     * @param codec the codec to decode
     * @return the artifact id, or {@code null} when no Smalt artifact decodes it
     */
    public static String decoderOf(Codec codec) {
        return switch (codec) {
            case H264, HEVC, AAC, AMR_NB, AMR_WB, MPEG4_PART2, H263, FLV1, AC3, EAC3 -> MPEG;
            case VP9, AV1, PRORES, DNXHD, QTRLE, CFHD -> VIDEO;
            case VP8, MJPEG, JPEG2000, PNG, TIFF, GIF, HAP -> IMAGE;
            case OPUS, VORBIS, FLAC, MP3, MP2, ALAC, ADPCM_IMA_WAV, ADPCM_IMA_QT, ADPCM_MS, ADPCM_G722, ADPCM_G726, GSM,
                 GSM_MS -> AUDIO;
            default -> null;
        };
    }

    /**
     * Returns the artifact whose encoder produces a codec.
     *
     * @param codec the codec to produce
     * @return the artifact id, or {@code null} when no Smalt artifact encodes it
     */
    public static String encoderOf(Codec codec) {
        return switch (codec) {
            case H264, AAC, AMR_NB, AMR_WB, AC3, EAC3 -> MPEG;
            case VP9, AV1, PRORES, DNXHD, QTRLE -> VIDEO;
            case VP8, MJPEG, JPEG2000, PNG, TIFF, HAP -> IMAGE;
            case OPUS, FLAC, MP3, VORBIS, ADPCM_IMA_WAV, ADPCM_IMA_QT, ADPCM_MS, ADPCM_G722, ADPCM_G726, GSM,
                 GSM_MS -> AUDIO;
            default -> null;
        };
    }

    /**
     * Returns the artifact whose decoder handles an image format.
     *
     * @param format the image format
     * @return the artifact id, or {@code null} when no Smalt artifact decodes it
     */
    public static String imageDecoderOf(ImageFormat format) {
        return switch (format) {
            case JPEG, PNG, GIF, WEBP, BMP, TIFF, ICO, ANI, JP2, JXL, PNM, TGA, QOI, HDR, ICNS, PSD, EXR, DDS, JXR -> IMAGE;
            case AVIF -> VIDEO;
            case HEIC -> MPEG;
            case DNG, RAW -> CAMERA_RAW;
            default -> null;
        };
    }

    /**
     * Returns the artifact whose encoder produces an image format.
     *
     * @param format the image format
     * @return the artifact id, or {@code null} when no Smalt artifact encodes it
     */
    public static String imageEncoderOf(ImageFormat format) {
        return switch (format) {
            case JPEG, PNG, GIF, WEBP, TIFF, ICO, ANI, JP2, PNM, TGA, QOI, HDR, ICNS -> IMAGE;
            case AVIF -> VIDEO;
            default -> null;
        };
    }

    /**
     * Returns the Smalt artifact that encodes animations of an image format.
     *
     * @param format the image format of the animations
     * @return the artifact id, or {@code null} when no Smalt artifact encodes its animations
     */
    public static String imageAnimationEncoderOf(ImageFormat format) {
        return switch (format) {
            case PNG, GIF, WEBP, ANI -> IMAGE;
            case AVIF -> VIDEO;
            default -> null;
        };
    }

    /**
     * Returns the Smalt artifact that encodes multi-page images of an image format.
     *
     * @param format the image format of the pages
     * @return the artifact id, or {@code null} when no Smalt artifact encodes its pages
     */
    public static String imagePageEncoderOf(ImageFormat format) {
        return switch (format) {
            case TIFF, ICO -> IMAGE;
            default -> null;
        };
    }

    /**
     * Returns the artifact that reads and writes a container format core names but does not implement.
     *
     * @param format the container format
     * @return the artifact id, or {@code null} for a format core implements or no Smalt artifact implements
     */
    public static String containerOf(ContainerFormat format) {
        // TODO: name the artifact of each ContainerFormat constant that core recognizes but another artifact
        //  implements, as such containers are added; every format named today is implemented in core.
        return switch (format) {
            default -> null;
        };
    }

    /**
     * Builds the failure raised when no provider handles a container format, naming the missing artifact when one
     * implements it.
     *
     * @param format the short name of the container format
     * @param what what was attempted, completing the sentence "the format is not ..." (for example
     * {@code "demuxable"})
     * @param artifact the artifact id that implements the format, or {@code null}
     * @return the exception to throw, of kind {@link MediaException.Unsupported.Kind#FORMAT}
     */
    public static MediaException.Unsupported unsupportedContainer(String format, String what, String artifact) {
        return new MediaException.Unsupported(MediaException.Unsupported.Kind.FORMAT, format,
                message(format, what, artifact), artifact);
    }

    /**
     * Builds the failure raised when no provider handles a format, naming the missing artifact
     * when one implements it.
     *
     * @param format the short name of the format
     * @param what what was attempted, completing the sentence "the format is not ..." (for
     * example {@code "decodable"})
     * @param artifact the artifact id that implements the format, or {@code null}
     * @return the exception to throw
     */
    public static MediaException.Unsupported unsupported(String format, String what, String artifact) {
        return new MediaException.Unsupported(format, message(format, what, artifact), artifact);
    }

    /**
     * Builds the message of a failure raised when no provider handles a format.
     *
     * @param format the short name of the format
     * @param what what was attempted
     * @param artifact the artifact id that implements the format, or {@code null}
     * @return the message
     */
    private static String message(String format, String what, String artifact) {
        return artifact == null
                ? format + " is not " + what + " by Smalt"
                : format + " is not " + what + " without " + artifact + " (add com.github.auties00:" + artifact
                + " to the module or class path)";
    }

    /**
     * Returns the short lowercase name of an image format.
     *
     * @param format the image format
     * @return the name, for example {@code "webp"}
     */
    public static String nameOf(ImageFormat format) {
        return format.name().toLowerCase(Locale.ROOT);
    }
}
