package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.vorbis.VorbisDecoder;
import com.github.auties00.smalt.audio.vorbis.VorbisEncoder;
import com.github.auties00.smalt.audio.vorbis.VorbisEncoderOptions;
import com.github.auties00.smalt.audio.vorbis.internal.CodecSetup;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link VorbisDecoder} to {@link AudioDecoders} and {@link VorbisEncoder} to
 * {@link AudioEncoders} for Vorbis tracks.
 *
 * @implNote This implementation passes the codec-independent decoder options on unchanged; the
 * decoder applies their limits and outputs the stream's own rate, so the preferred sample rate is
 * ignored. The encoder takes the requested sampling rate and layout as they are; a requested
 * bitrate becomes {@link VorbisEncoderOptions#nominalBitrate()}, clamped into the range the
 * settings reach for the format, and without one the quality is
 * {@link VorbisEncoderOptions#DEFAULT_QUALITY}. Real-time requests need no change: every Vorbis
 * packet decodes on its own once the headers are known.
 */
public final class VorbisCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public VorbisCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.VORBIS;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new VorbisDecoder(track, options);
    }

    @Override
    public boolean encodes(Codec codec) {
        return codec == Codec.VORBIS;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if no Vorbis setting covers the sampling rate and channel
     * count, or a bitrate is requested for a format that only the quality settings cover
     */
    @Override
    public AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        var builder = VorbisEncoderOptions.builder().format(options.sampleRate(), options.layout());
        if (options.bitrate().isPresent()) {
            var channels = options.layout().channels();
            var bitrate = CodecSetup.clampBitrate(channels, options.sampleRate(), options.bitrate().getAsInt());
            if (bitrate < 0) {
                throw new IllegalArgumentException("no Vorbis bitrate setting covers " + channels + " channels at "
                        + options.sampleRate() + " Hz, so " + options.bitrate().getAsInt()
                        + " bit/s cannot be requested; leave the bitrate unset");
            }
            builder.nominalBitrate(bitrate);
        }
        return new VorbisEncoder(builder.build());
    }

}
