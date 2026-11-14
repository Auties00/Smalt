package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.AmrConfigs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Packetizes AMR and AMR-WB storage-format frames per RFC 4867 in the bandwidth-efficient or octet-aligned mode,
 * with frame CRCs, robust sorting and frame-block interleaving as the format signals.
 */
final class AmrPayloader extends Payloader {
    /** The no-request value of the codec mode request field. */
    private static final int NO_REQUEST = 15;

    /** One frame-block: a storage-format frame per channel. */
    private record Block(byte[][] frames, long timestamp) {
    }

    private final AmrFormat format;
    private final int framesPerPacket;
    private final int interleavingLength;
    private final ArrayDeque<Block> queue = new ArrayDeque<>();
    private boolean first = true;
    private long nextTimestamp = Long.MIN_VALUE;

    AmrPayloader(int maxPayload, AmrFormat format, int framesPerPacket, int interleavingLength) {
        super(maxPayload);
        this.format = format;
        this.framesPerPacket = framesPerPacket;
        this.interleavingLength = format.interleaving > 0 ? interleavingLength : 1;
        if (format.interleaving > 0 && framesPerPacket * this.interleavingLength > format.interleaving) {
            throw new IllegalArgumentException("interleaving group of " + framesPerPacket * this.interleavingLength
                    + " frame-blocks exceeds the signaled interleaving=" + format.interleaving);
        }
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        var frames = new ArrayList<byte[]>();
        var position = 0;
        while (position < data.length) {
            var header = data[position] & 0xFF;
            var type = (header >>> 3) & 0x0F;
            if (!format.validType(type)) {
                throw new MediaException.Malformed("AMR frame type " + type + " cannot be sent over RTP");
            }
            var size = 1 + AmrConfigs.payloadSize(format.wideband, type);
            if (type == AmrFormat.NO_DATA || type == AmrFormat.SPEECH_LOST) {
                size = 1;
            }
            if (position + size > data.length) {
                throw new MediaException.Malformed("AMR frame of type " + type + " is truncated");
            }
            frames.add(java.util.Arrays.copyOfRange(data, position, position + size));
            position += size;
        }
        if (frames.size() % format.channels != 0) {
            throw new MediaException.Malformed(frames.size() + " AMR frames are not a whole number of "
                    + format.channels + "-channel frame-blocks");
        }
        var duration = format.frameDuration();
        for (var index = 0; index < frames.size(); index += format.channels) {
            var block = new Block(frames.subList(index, index + format.channels).toArray(byte[][]::new),
                    timestamp + (long) (index / format.channels) * duration);
            if (nextTimestamp != Long.MIN_VALUE && block.timestamp() != nextTimestamp) {
                if (format.interleaving > 0) {
                    padGroup(block.timestamp());
                } else {
                    sendQueued(out);
                }
            }
            queue.add(block);
            nextTimestamp = block.timestamp() + duration;
            sendReady(out);
        }
    }

    private void padGroup(long until) {
        var group = framesPerPacket * interleavingLength;
        var duration = format.frameDuration();
        while (queue.size() % group != 0 && nextTimestamp < until) {
            queue.add(noData(nextTimestamp));
            nextTimestamp += duration;
        }
    }

    private Block noData(long timestamp) {
        var frames = new byte[format.channels][];
        for (var channel = 0; channel < frames.length; channel++) {
            frames[channel] = new byte[]{(byte) ((AmrFormat.NO_DATA << 3) | 0x04)};
        }
        return new Block(frames, timestamp);
    }

    private void sendReady(Output out) {
        var group = framesPerPacket * interleavingLength;
        while (queue.size() >= group) {
            sendGroup(out, group);
        }
    }

    private void sendQueued(Output out) {
        if (format.interleaving > 0) {
            var group = framesPerPacket * interleavingLength;
            while (queue.size() % group != 0) {
                queue.add(noData(nextTimestamp));
                nextTimestamp += format.frameDuration();
            }
            while (!queue.isEmpty()) {
                sendGroup(out, group);
            }
        } else {
            while (!queue.isEmpty()) {
                sendGroup(out, Math.min(queue.size(), framesPerPacket));
            }
        }
    }

    private void sendGroup(Output out, int group) {
        var blocks = new ArrayList<Block>(group);
        for (var index = 0; index < group; index++) {
            blocks.add(queue.poll());
        }
        if (format.interleaving == 0) {
            send(blocks, -1, -1, out);
            return;
        }
        for (var ilp = 0; ilp < interleavingLength; ilp++) {
            var picked = new ArrayList<Block>(framesPerPacket);
            for (var index = ilp; index < group; index += interleavingLength) {
                picked.add(blocks.get(index));
            }
            send(picked, interleavingLength - 1, ilp, out);
        }
    }

    private void send(List<Block> blocks, int ill, int ilp, Output out) {
        var frames = new ArrayList<byte[]>();
        for (var block : blocks) {
            frames.addAll(List.of(block.frames()));
        }
        var payload = format.octetAligned ? octetAligned(frames, ill, ilp) : bandwidthEfficient(frames);
        if (payload.length > maxPayload) {
            throw new MediaException.LimitExceeded("AMR payload of " + payload.length
                    + " bytes does not fit a packet");
        }
        out.add(payload, first, blocks.getFirst().timestamp());
        first = false;
    }

    private static int type(byte[] frame) {
        return (frame[0] >>> 3) & 0x0F;
    }

    private static int quality(byte[] frame) {
        return (frame[0] >>> 2) & 0x01;
    }

    private byte[] bandwidthEfficient(List<byte[]> frames) {
        var writer = new MsbBitWriter(64);
        writer.write(NO_REQUEST, 4);
        for (var index = 0; index < frames.size(); index++) {
            var frame = frames.get(index);
            writer.write(index < frames.size() - 1 ? 1 : 0, 1);
            writer.write(type(frame), 4);
            writer.write(quality(frame), 1);
        }
        for (var frame : frames) {
            var bits = format.bits(type(frame));
            for (var bit = 0; bit < bits; bit++) {
                writer.write((frame[1 + (bit >>> 3)] >>> (7 - (bit & 7))) & 1, 1);
            }
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private byte[] octetAligned(List<byte[]> frames, int ill, int ilp) {
        var size = 1 + (ill >= 0 ? 1 : 0) + frames.size();
        var dataFrames = 0;
        for (var frame : frames) {
            size += frame.length - 1;
            if (frame.length > 1) {
                dataFrames++;
            }
        }
        if (format.crc) {
            size += dataFrames;
        }
        var payload = new byte[size];
        var position = 0;
        payload[position++] = (byte) (NO_REQUEST << 4);
        if (ill >= 0) {
            payload[position++] = (byte) ((ill << 4) | ilp);
        }
        for (var index = 0; index < frames.size(); index++) {
            var frame = frames.get(index);
            payload[position++] = (byte) ((index < frames.size() - 1 ? 0x80 : 0) | (frame[0] & 0x7C));
        }
        if (format.crc) {
            for (var frame : frames) {
                if (frame.length > 1) {
                    payload[position++] = (byte) AmrFormat.crc(frame, 1, format.classA(type(frame)));
                }
            }
        }
        if (!format.robustSorting) {
            for (var frame : frames) {
                System.arraycopy(frame, 1, payload, position, frame.length - 1);
                position += frame.length - 1;
            }
        } else {
            var longest = 0;
            for (var frame : frames) {
                longest = Math.max(longest, frame.length - 1);
            }
            for (var octet = 0; octet < longest; octet++) {
                for (var frame : frames) {
                    if (octet < frame.length - 1) {
                        payload[position++] = frame[1 + octet];
                    }
                }
            }
        }
        return payload;
    }

    @Override
    void flush(Output out) {
        sendQueued(out);
        nextTimestamp = Long.MIN_VALUE;
    }
}
