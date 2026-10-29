/**
 * Audio codecs of Smalt: the Opus, Vorbis, FLAC, ALAC and MP3 (and MP2) decoders, the Opus, FLAC,
 * ALAC, MP3, MP2 and Vorbis encoders, the ADPCM and telephony codecs (IMA and Microsoft ADPCM, G.722, G.726 and
 * GSM 06.10, decoders and encoders), and voice processing (echo cancellation, noise suppression, automatic gain
 * control and the capture high-pass filter).
 *
 * <p>The codecs register with {@code AudioDecoders} and {@code AudioEncoders} through the core's
 * service-provider interfaces, so {@code AudioReader} decodes these codecs, and
 * {@code AudioEncoders} encodes Opus, FLAC, ALAC, MP3, MP2, Vorbis and the ADPCM and telephony codecs, whenever
 * this module is present.
 *
 * <p>The launch requirements (the optional {@code --add-modules jdk.incubator.vector}) and the threading
 * model are those of the core module ({@code com.github.auties00.smalt}).
 *
 * @provides com.github.auties00.smalt.spi.AudioCodecProvider ALAC, FLAC, MP3 (and MP2), Opus, Vorbis, IMA and
 * Microsoft ADPCM, G.722, G.726 and GSM 06.10
 */
module com.github.auties00.smalt.audio {
    // Smalt core: the model and the codec service-provider interfaces
    requires transitive com.github.auties00.smalt;

    // Vector API: optional, selected at runtime by the core's internal.simd.Simd
    requires static jdk.incubator.vector;

    // Audio codecs and processing
    exports com.github.auties00.smalt.audio.adpcm;
    exports com.github.auties00.smalt.audio.alac;
    exports com.github.auties00.smalt.audio.flac;
    exports com.github.auties00.smalt.audio.g722;
    exports com.github.auties00.smalt.audio.g726;
    exports com.github.auties00.smalt.audio.gsm;
    exports com.github.auties00.smalt.audio.mp3;
    exports com.github.auties00.smalt.audio.opus;
    exports com.github.auties00.smalt.audio.voice;
    exports com.github.auties00.smalt.audio.vorbis;

    // The codecs of this module
    provides com.github.auties00.smalt.spi.AudioCodecProvider with
            com.github.auties00.smalt.internal.provider.audio.AdpcmCodecProvider,
            com.github.auties00.smalt.internal.provider.audio.AlacCodecProvider,
            com.github.auties00.smalt.internal.provider.audio.FlacCodecProvider,
            com.github.auties00.smalt.internal.provider.audio.Mp3CodecProvider,
            com.github.auties00.smalt.internal.provider.audio.OpusCodecProvider,
            com.github.auties00.smalt.internal.provider.audio.VorbisCodecProvider;
}
