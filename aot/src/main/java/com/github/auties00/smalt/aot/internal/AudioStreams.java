package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingWorkload;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the audio encoders and decoders of the training workloads on synthetic speech and music:
 * an encoder fed in blocks that keeps its state across rounds as a long-running stream does, and a
 * decoder that decodes the same stream every round.
 */
final class AudioStreams {
    /**
     * The frames of the blocks the encoders are fed.
     */
    static final int BLOCK_FRAMES = AudioReaderOptions.DEFAULT_BLOCK_FRAMES;

    /**
     * Prevents instantiation.
     */
    private AudioStreams() {
        throw new AssertionError();
    }

    /**
     * Returns a workload that encodes the same audio with an encoder every round, in blocks.
     *
     * @param name the name of the workload
     * @param planes the samples, one plane per channel
     * @param layout the channel layout of the samples
     * @param rate the sample rate of the samples
     * @param encoder the encoder that codes the samples in every round, kept across rounds
     * @return the workload
     */
    static TrainingWorkload encode(String name, float[][] planes, ChannelLayout layout, int rate,
                                   AudioEncoder encoder) {
        var block = new AudioBuffer(layout, rate, BLOCK_FRAMES);
        Consumer<Packet> output = packet -> Blackhole.consume(packet.size());
        return TrainingWorkload.of(name, () -> SyntheticAudio.feed(planes, block, buffer -> encoder.encode(buffer, output)));
    }

    /**
     * Encodes audio in blocks, flushes the encoder and returns the packets; the encoder is not
     * closed, so that its final configuration can still be read.
     *
     * @param encoder the encoder that codes the samples, flushed but left open
     * @param planes the samples, one plane per channel
     * @param layout the channel layout of the samples
     * @param rate the sample rate of the samples
     * @return the packets
     */
    static List<Packet> encodeAll(AudioEncoder encoder, float[][] planes, ChannelLayout layout, int rate) {
        var packets = new ArrayList<Packet>();
        var block = new AudioBuffer(layout, rate, BLOCK_FRAMES);
        SyntheticAudio.feed(planes, block, buffer -> encoder.encode(buffer, packets::add));
        encoder.flush(packets::add);
        return packets;
    }

    /**
     * Returns the track of an encoder's stream.
     *
     * @param codec the codec the encoder produced
     * @param rate the sample rate
     * @param layout the channel layout
     * @param codecConfig the decoder configuration, or {@code null} for none
     * @return the track
     */
    static AudioTrack track(Codec codec, int rate, ChannelLayout layout, byte[] codecConfig) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(rate)).sampleRate(rate)
                .layout(layout).codecConfig(codecConfig).build();
    }

    /**
     * Returns a workload that decodes the same packets every round with one decoder.
     *
     * @param name the name of the workload
     * @param packets the packets decoded in every round, in decoding order
     * @param decoder the decoder, kept across rounds
     * @return the workload
     */
    static TrainingWorkload decode(String name, List<Packet> packets, AudioDecoder decoder) {
        Consumer<AudioBuffer> output = AudioStreams::consume;
        return TrainingWorkload.of(name, () -> {
            for (var packet : packets) {
                decoder.decode(packet, output);
            }
        });
    }

    /**
     * Returns a workload that decodes packets as a real-time receiver that loses some: every seventh packet is
     * concealed for its duration instead of decoded, one decoder across rounds.
     *
     * @param name the name of the workload
     * @param packets the packets, whose durations count output samples
     * @param decoder the decoder, kept across rounds
     * @return the workload
     */
    static TrainingWorkload decodeLossy(String name, List<Packet> packets, AudioDecoder decoder) {
        Consumer<AudioBuffer> output = AudioStreams::consume;
        return TrainingWorkload.of(name, () -> {
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (i % 7 == 3 && packet.duration() > 0) {
                    Blackhole.consume(decoder.conceal((int) packet.duration(), output));
                } else {
                    decoder.decode(packet, output);
                }
            }
        });
    }

    /**
     * Returns a workload that encodes planes as a real-time sender following a congestion controller: the target
     * bitrate cycles through the given values, one change per second of input, one encoder across rounds.
     *
     * @param name the name of the workload
     * @param planes the input samples
     * @param layout the layout of the input
     * @param rate the sampling rate of the input
     * @param encoder the encoder, kept across rounds
     * @param targets the targets, in bits per second
     * @return the workload
     */
    static TrainingWorkload encodeRetargeted(String name, float[][] planes, ChannelLayout layout, int rate,
                                             AudioEncoder encoder, long... targets) {
        var block = new AudioBuffer(layout, rate, rate / 50);
        Consumer<Packet> output = packet -> Blackhole.consume(packet.size());
        return TrainingWorkload.of(name, () -> {
            var blocks = 0;
            var changes = 0;
            for (var offset = 0; offset + block.capacity() <= planes[0].length; offset += block.capacity()) {
                if (blocks++ % 50 == 0) {
                    encoder.setTargetBitrate(targets[changes++ % targets.length]);
                }
                for (var c = 0; c < planes.length; c++) {
                    System.arraycopy(planes[c], offset, block.plane(c), 0, block.capacity());
                }
                block.setFrames(block.capacity());
                encoder.encode(block, output);
            }
        });
    }

    /**
     * Consumes a decoded block.
     *
     * @param buffer the block
     */
    static void consume(AudioBuffer buffer) {
        Blackhole.consume(Float.floatToRawIntBits(buffer.plane(0)[0]) + buffer.frames());
    }
}
