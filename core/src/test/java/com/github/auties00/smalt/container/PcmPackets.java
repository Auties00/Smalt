package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** A PCM track and its packets, encoded from a tone with {@link PcmEncoder}. */
public record PcmPackets(AudioTrack track, List<Packet> packets) {
    /** Encodes {@code frames} frames of a sine tone per channel. */
    public static PcmPackets tone(Codec codec, int sampleRate, ChannelLayout layout, int frames) {
        var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, sampleRate, layout).toBuilder().framesPerPacket(1000)
                .build());
        var planes = new float[layout.channels()][frames];
        for (var channel = 0; channel < planes.length; channel++) {
            for (var index = 0; index < frames; index++) {
                planes[channel][index] = (float) (0.5 * Math.sin(2 * Math.PI * (440 + 110 * channel) * index / sampleRate));
            }
        }
        var packets = new ArrayList<Packet>();
        encoder.encode(new AudioBuffer(layout, sampleRate, planes, frames), packets::add);
        encoder.flush(packets::add);
        var track = AudioTrack.builder()
                .id(0)
                .codec(codec)
                .timeBase(Rational.timeBaseOf(sampleRate))
                .duration(frames)
                .sampleRate(sampleRate)
                .layout(layout)
                .bitsPerSample(encoder.bitsPerSample())
                .skipSamples(0)
                .build();
        return new PcmPackets(track, packets);
    }

    /** Returns every packet's bytes, concatenated. */
    public byte[] bytes() {
        return concatenate(packets);
    }

    /** Concatenates the payloads of packets. */
    public static byte[] concatenate(List<Packet> packets) {
        var output = new ByteArrayOutputStream();
        packets.forEach(packet -> output.writeBytes(packet.data()));
        return output.toByteArray();
    }
}
