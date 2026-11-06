package com.github.auties00.smalt.internal.provider.mpeg;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.dts.DtsDecoder;
import com.github.auties00.smalt.audio.dts.DtsDecoderOptions;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link DtsDecoder} to {@link AudioDecoders} for DTS tracks.
 *
 * @implNote This implementation maps the decoder limits onto {@link DtsDecoderOptions#limits()} and decodes every
 * extension; the preferred sample rate is ignored, the stream's rate being output.
 */
public final class DtsCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public DtsCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.DTS;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new DtsDecoder(track, DtsDecoderOptions.builder().limits(options.limits()).build());
    }
}
