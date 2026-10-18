/**
 * Camera raw decoding of Smalt: DNG (Adobe Digital Negative 1.7.1) and the raw formats of the
 * camera makers, rendered into pictures through demosaicing, white balance, camera color
 * matrices, the DNG opcode lists and camera profiles, exposure and tone curves.
 *
 * <p>{@code RawDecoder} is the entry point; the decoder also registers with the core's image
 * registry, so {@code Images} decodes DNG and camera raw files whenever this module is present.
 * This product includes DNG technology under license by Adobe.
 *
 * <p>The launch requirements (the optional {@code --add-modules jdk.incubator.vector}) and the threading
 * model are those of the core module ({@code com.github.auties00.smalt}).
 *
 * @provides com.github.auties00.smalt.spi.ImageCodecProvider DNG and the camera raw formats
 */
module com.github.auties00.smalt.raw {
    // Smalt core: the model and the codec service-provider interfaces
    requires transitive com.github.auties00.smalt;

    // Smalt image: the JPEG decoder of embedded previews and lossy DNG data
    requires com.github.auties00.smalt.image;

    // Vector API: optional, selected at runtime by the core's internal.simd.Simd
    requires static jdk.incubator.vector;

    // The raw decoder
    exports com.github.auties00.smalt.image.raw;

    // The codec of this module
    provides com.github.auties00.smalt.spi.ImageCodecProvider with
            com.github.auties00.smalt.internal.provider.raw.RawCodecProvider;
}
