package com.github.auties00.smalt.internal.provider.audio;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.alac.AlacDecoder;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

/**
 * Provides {@link AlacDecoder} to {@link AudioDecoders} for ALAC tracks, as the MP4 and Matroska
 * demuxers deliver them.
 *
 * @implNote This implementation passes the codec-independent options on unchanged; the decoder
 *           applies their limits and outputs the stream's own rate, so the preferred sample rate is
 *           ignored.
 */
public final class AlacCodecProvider implements AudioCodecProvider {
    /**
     * Constructs the provider; {@link java.util.ServiceLoader} calls this constructor.
     */
    public AlacCodecProvider() {
    }

    @Override
    public boolean decodes(Codec codec) {
        return codec == Codec.ALAC;
    }

    @Override
    public AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        return new AlacDecoder(track, options);
    }
}
