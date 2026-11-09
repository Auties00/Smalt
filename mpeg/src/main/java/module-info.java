/**
 * Codecs of Smalt whose standards are licensed through patent pools or by Dolby: the H.264 decoder
 * and encoder, the HEVC decoder, the HEIC image decoder built on it, the VVC decoder, the AAC decoder and encoder,
 * the AMR-NB and AMR-WB decoders and encoders, the MPEG-4 Part 2 Visual and H.263 decoders, the MPEG-1 and
 * MPEG-2 video decoder, the VC-1 decoder, the AC-3 decoder and encoder with the E-AC-3 decoder, and the DTS
 * decoder with its DTS-HD extensions.
 *
 * <p>The code is licensed under the MIT License, but H.264, HEVC, VVC, AAC and AMR are covered by
 * patents licensed separately (through the Via LA AVC and AAC pools, the Access Advance and Via LA
 * HEVC and VVC pools, and the VoiceAge AMR-WB pool), and E-AC-3 may be covered by Dolby patents (the AC-3
 * patents expired in 2017); they live in their own module so an application can leave them out. The
 * codecs register with {@code VideoDecoders}, {@code VideoEncoders}, {@code AudioDecoders} and
 * {@code AudioEncoders} through the core's service-provider interfaces; the HEIC decoder registers
 * with {@code Images} the same way.
 *
 * <p>The launch requirements (the optional {@code --add-modules jdk.incubator.vector}) and the threading
 * model are those of the core module ({@code com.github.auties00.smalt}).
 *
 * @provides com.github.auties00.smalt.spi.AudioCodecProvider AAC, AMR-NB and AMR-WB, AC-3 and E-AC-3, DTS
 * @provides com.github.auties00.smalt.spi.VideoCodecProvider H.264, HEVC, VVC, MPEG-4 Part 2 Visual, H.263,
 * MPEG-1 and MPEG-2 video, VC-1, and JPEG XS
 * @provides com.github.auties00.smalt.spi.ImageCodecProvider HEIC and JPEG XS
 */
module com.github.auties00.smalt.mpeg {
    // Smalt core: the model and the codec service-provider interfaces
    requires transitive com.github.auties00.smalt;

    // Vector API: optional, selected at runtime by the core's internal.simd.Simd
    requires static jdk.incubator.vector;

    // Video codecs
    exports com.github.auties00.smalt.video.h264;
    exports com.github.auties00.smalt.video.h264.encoder;
    exports com.github.auties00.smalt.video.hevc;
    exports com.github.auties00.smalt.video.hevc.encoder;
    exports com.github.auties00.smalt.video.vvc;
    exports com.github.auties00.smalt.video.mpeg4;
    exports com.github.auties00.smalt.video.mpeg2;
    exports com.github.auties00.smalt.video.h263;
    exports com.github.auties00.smalt.video.vc1;

    // Image codecs built on the video codecs
    exports com.github.auties00.smalt.image.heic;
    exports com.github.auties00.smalt.image.jxs;

    // Audio codecs and processing
    exports com.github.auties00.smalt.audio.aac;
    exports com.github.auties00.smalt.audio.amr;
    exports com.github.auties00.smalt.audio.ac3;
    exports com.github.auties00.smalt.audio.dts;

    // The codecs of this module
    provides com.github.auties00.smalt.spi.AudioCodecProvider with
            com.github.auties00.smalt.internal.provider.mpeg.AacCodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.AmrCodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.Ac3CodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.DtsCodecProvider;
    provides com.github.auties00.smalt.spi.VideoCodecProvider with
            com.github.auties00.smalt.internal.provider.mpeg.H264CodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.HevcCodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.VvcCodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.Mpeg4CodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.Mpeg2CodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.H263CodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.Vc1CodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.JpegXsVideoCodecProvider;
    provides com.github.auties00.smalt.spi.ImageCodecProvider with
            com.github.auties00.smalt.internal.provider.mpeg.HeicCodecProvider,
            com.github.auties00.smalt.internal.provider.mpeg.JpegXsCodecProvider;
    // The HEIC decoder, for the HEIF decoders of the artifacts that do not depend on this one
    provides com.github.auties00.smalt.internal.heif.HeifImageCodec with
            com.github.auties00.smalt.internal.provider.mpeg.HeicImageCodec;
}
