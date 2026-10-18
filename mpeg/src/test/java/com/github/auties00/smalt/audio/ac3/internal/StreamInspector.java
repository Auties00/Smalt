package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an AC-3 or E-AC-3 stream frame by frame with the decoder's parser and reports the coding
 * tools each frame uses, read from the parser's state, so that encoder tests can check which tools
 * the encoder chose. Every frame must parse with valid CRCs.
 */
public final class StreamInspector {
    private StreamInspector() {
    }

    /**
     * What one frame codes.
     *
     * @param streamType  the substream type: 0 independent, 1 dependent
     * @param channelMap  the custom channel map, or -1
     * @param acmod       the audio coding mode
     * @param blocks      the blocks of the frame
     * @param spxBegin    the first coefficient of the extension region of the last block, or -1
     *                    without spectral extension
     * @param spxEnd      one past the last coefficient of the extension region, or -1
     * @param hybrid      the number of exponent sets coded with the adaptive hybrid transform
     * @param locations   the transient pre-noise location of each channel, -1 without data
     * @param lengths     the transient pre-noise processing length of each channel
     * @param shortBlocks the number of channel blocks with short transforms
     */
    public record Frame(int streamType, int channelMap, int acmod, int blocks, int spxBegin, int spxEnd, int hybrid,
                        int[] locations, int[] lengths, int shortBlocks) {
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    public static List<Frame> frames(byte[] stream) {
        var frames = new ArrayList<Frame>();
        var decoders = new FrameDecoder[16];
        var header = new FrameHeader();
        var position = 0;
        try {
            while (position + 8 <= stream.length) {
                var reader = new MsbBitReader(stream, position, stream.length - position);
                if (!header.parse(reader, stream.length - position)) {
                    throw new IllegalStateException("no frame at byte " + position);
                }
                var key = header.streamType * 8 + header.substreamId;
                if (decoders[key] == null) {
                    decoders[key] = new FrameDecoder(Ac3Kernels.scalar());
                }
                var decoder = decoders[key];
                if (!decoder.parse(stream, position, header.frameBytes, false, true)) {
                    throw new IllegalStateException("invalid frame at byte " + position);
                }
                var parsed = decoder.header();
                var spx = header.enhanced && (boolean) field(decoder, "spxInUse");
                var hybrid = 0;
                if (header.enhanced) {
                    for (var value : (int[]) field(decoder, "channelAht")) {
                        hybrid += value == -1 ? 1 : 0;
                    }
                    hybrid += (int) field(decoder, "couplingAht") == -1 ? 1 : 0;
                    hybrid += (int) field(decoder, "lfeAht") == -1 ? 1 : 0;
                }
                var locations = ((int[]) field(decoder, "transientLocation")).clone();
                var lengths = ((int[]) field(decoder, "transientLength")).clone();
                var shortBlocks = 0;
                var states = (BlockState[]) field(decoder, "blocks");
                for (var b = 0; b < parsed.blocks; b++) {
                    for (var ch = 0; ch < parsed.channels; ch++) {
                        shortBlocks += states[b].shortBlocks[ch] ? 1 : 0;
                    }
                }
                frames.add(new Frame(parsed.streamType, parsed.channelMap, parsed.acmod, parsed.blocks,
                        spx ? 25 + 12 * (int) field(decoder, "spxBegin") : -1,
                        spx ? 25 + 12 * (int) field(decoder, "spxEnd") : -1, hybrid, locations, lengths, shortBlocks));
                position += header.frameBytes;
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return frames;
    }
}
