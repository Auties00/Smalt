/**
 * Video codecs of Smalt: the VP9 and AV1 decoders and the encoders that come with them, the
 * AVIF image decoder built on the AV1 decoder, and the professional intra codecs Apple ProRes and
 * Avid DNxHD/DNxHR (VC-3), decoders and encoders.
 *
 * <p>The codecs register with {@code VideoDecoders} and {@code VideoEncoders} through the core's
 * service-provider interfaces, so {@code VideoReader} decodes these codecs, and
 * {@code VideoEncoders} encodes VP9, AV1, ProRes and DNxHD, whenever this module is present; the AVIF
 * decoder registers with {@code Images} the same way.
 *
 * <p>The launch requirements (the optional {@code --add-modules jdk.incubator.vector}) and the threading
 * model are those of the core module ({@code com.github.auties00.smalt}).
 *
 * @provides com.github.auties00.smalt.spi.VideoCodecProvider VP9, AV1, ProRes and DNxHD/DNxHR
 * @provides com.github.auties00.smalt.spi.ImageCodecProvider AVIF
 */
module com.github.auties00.smalt.video {
    // Smalt core: the model and the codec service-provider interfaces
    requires transitive com.github.auties00.smalt;

    // Vector API: optional, selected at runtime by the core's internal.simd.Simd
    requires static jdk.incubator.vector;

    // Video codecs
    exports com.github.auties00.smalt.video.av1;
    exports com.github.auties00.smalt.video.dnxhd;
    exports com.github.auties00.smalt.video.prores;
    exports com.github.auties00.smalt.video.vp9;

    // Image codecs built on the video codecs
    exports com.github.auties00.smalt.image.avif;

    // The codecs of this module
    provides com.github.auties00.smalt.spi.VideoCodecProvider with
            com.github.auties00.smalt.internal.provider.video.Vp9CodecProvider,
            com.github.auties00.smalt.internal.provider.video.Av1CodecProvider,
            com.github.auties00.smalt.internal.provider.video.ProResCodecProvider,
            com.github.auties00.smalt.internal.provider.video.DnxhdCodecProvider;
    provides com.github.auties00.smalt.spi.ImageCodecProvider with
            com.github.auties00.smalt.internal.provider.video.AvifCodecProvider;
    // The AVIF decoder, for the HEIF decoders of the artifacts that do not depend on this one
    provides com.github.auties00.smalt.internal.heif.HeifImageCodec with
            com.github.auties00.smalt.internal.provider.video.AvifImageCodec;
}
