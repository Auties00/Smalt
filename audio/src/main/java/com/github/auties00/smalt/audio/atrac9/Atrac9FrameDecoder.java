/* Ports LibAtrac9 (C/src/decoder.c, C/src/decinit.c, C/src/unpack.c) at commit efca2e3. Copyright 2018 Alex
   Barney; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the superframes of an ATRAC9 stream into 16-bit samples.
 *
 * <p>Instances are not thread-safe.
 */
final class Atrac9FrameDecoder {
    private final Atrac9Config config;

    private final Atrac9Block[] blocks;

    /**
     * The channels in output order: the channels of each block, block by block.
     */
    private final Atrac9Channel[] channels;

    private final Atrac9BitReader reader = new Atrac9BitReader();

    private final Atrac9Unpacker unpacker = new Atrac9Unpacker();

    private final Atrac9Synthesis synthesis = new Atrac9Synthesis();

    Atrac9FrameDecoder(Atrac9Config config) {
        this.config = config;
        var types = Atrac9Tables.CHANNEL_CONFIGS[config.channelConfigIndex()];
        this.blocks = new Atrac9Block[types.length];
        this.channels = new Atrac9Channel[config.channelCount()];
        var next = 0;
        for (var i = 0; i < types.length; i++) {
            blocks[i] = new Atrac9Block(config, types[i]);
            for (var channel : blocks[i].channels) {
                channels[next++] = channel;
            }
        }
    }

    Atrac9Config config() {
        return config;
    }

    /**
     * Decodes one superframe; bits past the end of the range read as zero.
     *
     * @param data the bytes
     * @param offset the index of the superframe's first byte
     * @param length the bytes of the superframe, normally {@link Atrac9Config#superframeBytes()}
     * @param output receives the samples, one plane per channel
     * @param at the index in each plane of the first sample written; {@link Atrac9Config#superframeSamples()} samples
     * are written
     * @throws MediaException.Malformed if a frame codes invalid parameters
     */
    void decodeSuperframe(byte[] data, int offset, int length, int[][] output, int at) {
        reader.reset(data, offset, length);
        var frameSamples = config.frameSamples();
        for (var frame = 0; frame < config.framesPerSuperframe(); frame++) {
            for (var block : blocks) {
                unpacker.unpackBlock(block, reader);
                if (block.firstInSuperframe && frame != 0) {
                    throw Atrac9Unpacker.malformed("starts a superframe inside one, in frame " + frame);
                }
            }
            for (var block : blocks) {
                synthesis.synthesize(block);
            }
            var start = at + frame * frameSamples;
            for (var c = 0; c < channels.length; c++) {
                var pcm = channels[c].pcm;
                var plane = output[c];
                for (var i = 0; i < frameSamples; i++) {
                    var rounded = (int) Math.floor(pcm[i] + 0.5);
                    plane[start + i] = Math.clamp(rounded, Short.MIN_VALUE, Short.MAX_VALUE);
                }
            }
        }
    }
}
