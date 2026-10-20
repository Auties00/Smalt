/**
 * Image codecs of Smalt: the JPEG, PNG (and APNG), GIF, WebP, BMP, TIFF, ICO/CUR, JPEG 2000 and
 * JPEG XL decoders, the JPEG, PNG (and APNG), GIF, WebP, BMP, TIFF, ICO/CUR and JPEG 2000 encoders
 * (still, animated and multi-page), lossless JPEG transforms (rotation, flips, cropping, grayscale
 * and progressive transcoding without decoding pixels), EXIF reading and writing, image metadata
 * (EXIF, XMP, IPTC and ICC read from every image format and edited in JPEG, PNG, WebP, GIF, HEIC,
 * AVIF, TIFF and BMP files without re-encoding them), the Motion-JPEG codec, and the VP8 codec, since
 * a lossy WebP image is a VP8 key frame.
 *
 * <p>The codecs register with the core registries through the core's service-provider interfaces,
 * so {@code Images} and {@code VideoDecoders} decode these formats (Motion-JPEG and VP8 video
 * included), and {@code Images} and {@code VideoEncoders} encode JPEG, PNG, GIF, WebP, BMP, TIFF, ICO,
 * JPEG 2000, animated WebP, GIF and APNG, Motion-JPEG and VP8, whenever this module is present.
 *
 * <p>The launch requirements (the optional {@code --add-modules jdk.incubator.vector}) and the threading
 * model are those of the core module ({@code com.github.auties00.smalt}).
 *
 * @provides com.github.auties00.smalt.spi.ImageCodecProvider BMP, GIF, ICO and CUR, JPEG 2000, JPEG, JPEG XL,
 * PNG and APNG, TIFF and WebP
 * @provides com.github.auties00.smalt.spi.VideoCodecProvider Motion-JPEG and VP8
 */
module com.github.auties00.smalt.image {
    // Smalt core: the model and the codec service-provider interfaces
    requires transitive com.github.auties00.smalt;

    // Vector API: optional, selected at runtime by the core's internal.simd.Simd
    requires static jdk.incubator.vector;

    // Image codecs and processing
    exports com.github.auties00.smalt.image.bmp;
    exports com.github.auties00.smalt.image.exif;
    exports com.github.auties00.smalt.image.gif;
    exports com.github.auties00.smalt.image.ico;
    exports com.github.auties00.smalt.image.jp2;
    exports com.github.auties00.smalt.image.jpeg;
    exports com.github.auties00.smalt.image.jxl;
    exports com.github.auties00.smalt.image.metadata;
    exports com.github.auties00.smalt.image.png;
    exports com.github.auties00.smalt.image.tiff;
    exports com.github.auties00.smalt.image.webp;

    // Video codecs
    exports com.github.auties00.smalt.video.vp8;

    // Internals shared with the raw decoder: the TIFF strip and tile decoding, the zlib codec and
    // the JPEG decoding of raw data
    exports com.github.auties00.smalt.image.tiff.internal to com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.image.internal.zlib to com.github.auties00.smalt.raw;
    exports com.github.auties00.smalt.image.jpeg.internal to com.github.auties00.smalt.raw;

    // The codecs of this module
    provides com.github.auties00.smalt.spi.ImageCodecProvider with
            com.github.auties00.smalt.internal.provider.image.BmpCodecProvider,
            com.github.auties00.smalt.internal.provider.image.GifCodecProvider,
            com.github.auties00.smalt.internal.provider.image.IcoCodecProvider,
            com.github.auties00.smalt.internal.provider.image.Jp2CodecProvider,
            com.github.auties00.smalt.internal.provider.image.JpegCodecProvider,
            com.github.auties00.smalt.internal.provider.image.JxlCodecProvider,
            com.github.auties00.smalt.internal.provider.image.PngCodecProvider,
            com.github.auties00.smalt.internal.provider.image.TiffCodecProvider,
            com.github.auties00.smalt.internal.provider.image.WebpCodecProvider;
    provides com.github.auties00.smalt.spi.VideoCodecProvider with
            com.github.auties00.smalt.internal.provider.image.MjpegCodecProvider,
            com.github.auties00.smalt.internal.provider.image.Vp8CodecProvider;
}
