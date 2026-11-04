package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Packetizes AV1 temporal units per the AOMedia RTP payload format: OBU elements without size fields behind the
 * aggregation header, aggregated greedily and fragmented across packets, with OBUs of different layers kept in
 * different packets.
 */
final class Av1Payloader extends Payloader {
    /** The OBU type of a sequence header. */
    static final int SEQUENCE_HEADER = 1;
    /** The OBU type of a temporal delimiter. */
    static final int TEMPORAL_DELIMITER = 2;
    /** The OBU type of a frame header. */
    static final int FRAME_HEADER = 3;
    /** The OBU type of a frame. */
    static final int FRAME = 6;
    /** The OBU type of a tile list. */
    static final int TILE_LIST = 8;

    private byte[][] elements = new byte[16][];
    private int[] layers = new int[16];
    private int count;
    private final ByteSink packet = new ByteSink(Integer.MAX_VALUE);

    /** The extension identifier of the dependency descriptor, 0 for none. */
    private final int descriptorId;
    /** The structure every dependency descriptor refers to. */
    private final Av1TemplateStructure structure = Av1TemplateStructure.singleLayer(0);
    /** The payload room left after the largest header extension. */
    private final int room;
    private int frameNumber;
    private boolean structureSent;
    private boolean unitStart;
    private boolean keyUnit;

    Av1Payloader(int maxPayload, int descriptorId, int initialFrameNumber) {
        super(maxPayload);
        this.descriptorId = descriptorId;
        this.frameNumber = initialFrameNumber & 0xFFFF;
        var largest = descriptorId == 0 ? 0 : 4 + extension(new Av1DependencyDescriptor(true, true, 0, 0, structure,
                -1, null, null, null)).data().length;
        this.room = maxPayload - largest;
    }

    private RtpHeaderExtension extension(Av1DependencyDescriptor descriptor) {
        return RtpHeaderExtension.of(java.util.Map.of(descriptorId, descriptor.toByteArray(structure)));
    }

    /**
     * Reads an unsigned LEB128 value of at most 8 bytes.
     *
     * @return the value in the high 32 bits and the byte count in the low 32 bits
     * @throws MediaException.Malformed if the value is truncated, longer than 8 bytes or above {@code 2^32 - 1}
     */
    static long readLeb128(byte[] data, int position, int end) {
        long value = 0;
        for (var index = 0; index < 8; index++) {
            if (position + index >= end) {
                throw new MediaException.Malformed("LEB128 value is truncated");
            }
            var current = data[position + index] & 0xFF;
            value |= (long) (current & 0x7F) << (7 * index);
            if ((current & 0x80) == 0) {
                if (value > 0xFFFF_FFFFL) {
                    throw new MediaException.Malformed("LEB128 value " + value + " is too large");
                }
                return (value << 32) | (index + 1);
            }
        }
        throw new MediaException.Malformed("LEB128 value is longer than 8 bytes");
    }

    static int leb128Size(long value) {
        var size = 1;
        while (value >= 0x80) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        count = 0;
        var sequenceHeader = false;
        var position = 0;
        while (position < data.length) {
            var header = data[position] & 0xFF;
            if ((header & 0x80) != 0) {
                throw new MediaException.Malformed("AV1 OBU forbidden bit is set");
            }
            var type = (header >>> 3) & 0x0F;
            var extended = (header & 0x04) != 0;
            var headerSize = extended ? 2 : 1;
            if (position + headerSize > data.length) {
                throw new MediaException.Malformed("AV1 OBU header is truncated");
            }
            var payloadStart = position + headerSize;
            int payloadSize;
            if ((header & 0x02) != 0) {
                var leb = readLeb128(data, payloadStart, data.length);
                payloadStart += (int) leb;
                var size = leb >>> 32;
                if (size > data.length - payloadStart) {
                    throw new MediaException.Malformed("AV1 OBU of " + size + " bytes runs past the temporal unit");
                }
                payloadSize = (int) size;
            } else {
                payloadSize = data.length - payloadStart;
            }
            if (type != TEMPORAL_DELIMITER && type != TILE_LIST) {
                var element = new byte[headerSize + payloadSize];
                element[0] = (byte) (header & ~0x02);
                if (extended) {
                    element[1] = data[position + 1];
                }
                System.arraycopy(data, payloadStart, element, headerSize, payloadSize);
                if (count == elements.length) {
                    elements = Arrays.copyOf(elements, count * 2);
                    layers = Arrays.copyOf(layers, count * 2);
                }
                layers[count] = extended ? data[position + 1] & 0xF8 : -1;
                elements[count++] = element;
                sequenceHeader |= type == SEQUENCE_HEADER;
            }
            position = payloadStart + payloadSize;
        }
        if (count == 0) {
            return;
        }
        unitStart = true;
        keyUnit = key && sequenceHeader;
        emitPackets(timestamp, keyUnit, out);
        frameNumber = (frameNumber + 1) & 0xFFFF;
    }

    private void emitPackets(long timestamp, boolean newSequence, Output out) {
        var firstPacket = true;
        var packetLayer = -1;
        var continuation = false;
        var elementCount = 0;
        var used = 1;
        for (var index = 0; index < count; index++) {
            var element = elements[index];
            var layer = layers[index];
            if (elementCount > 0 && layer >= 0 && packetLayer >= 0 && layer != packetLayer) {
                send(out, timestamp, firstPacket && newSequence, continuation, false, false, elementCount);
                firstPacket = false;
                continuation = false;
                elementCount = 0;
                used = 1;
                packetLayer = -1;
            }
            if (layer >= 0) {
                packetLayer = layer;
            }
            var offset = 0;
            while (offset < element.length) {
                var free = room - used;
                int fit;
                if (elementCount + 1 <= 3) {
                    fit = free;
                } else {
                    fit = free - leb128Size(free);
                    while (fit > 0 && fit + leb128Size(fit) > free) {
                        fit--;
                    }
                }
                if (fit <= 0) {
                    send(out, timestamp, firstPacket && newSequence, continuation, false, false, elementCount);
                    firstPacket = false;
                    continuation = false;
                    elementCount = 0;
                    used = 1;
                    continue;
                }
                var take = Math.min(fit, element.length - offset);
                if (elementCount == planElements.length) {
                    planElements = Arrays.copyOf(planElements, elementCount * 2);
                    planOffsets = Arrays.copyOf(planOffsets, elementCount * 2);
                    planLengths = Arrays.copyOf(planLengths, elementCount * 2);
                }
                planElements[elementCount] = element;
                planOffsets[elementCount] = offset;
                planLengths[elementCount] = take;
                elementCount++;
                used += leb128Size(take) + take;
                offset += take;
                if (offset < element.length) {
                    send(out, timestamp, firstPacket && newSequence, continuation, true, false, elementCount);
                    firstPacket = false;
                    continuation = true;
                    elementCount = 0;
                    used = 1;
                }
            }
        }
        send(out, timestamp, firstPacket && newSequence, continuation, false, true, elementCount);
    }

    private byte[][] planElements = new byte[8][];
    private int[] planOffsets = new int[8];
    private int[] planLengths = new int[8];

    private void send(Output out, long timestamp, boolean newSequence, boolean continuation, boolean willContinue,
                      boolean marker, int elementCount) {
        if (elementCount == 0) {
            return;
        }
        var w = elementCount <= 3 ? elementCount : 0;
        packet.clear();
        packet.write((continuation ? 0x80 : 0) | (willContinue ? 0x40 : 0) | (w << 4) | (newSequence ? 0x08 : 0));
        for (var index = 0; index < elementCount; index++) {
            if (w == 0 || index < elementCount - 1) {
                packet.writeLeb128(planLengths[index]);
            }
            packet.write(planElements[index], planOffsets[index], planLengths[index]);
        }
        RtpHeaderExtension extension = null;
        if (descriptorId != 0) {
            var attach = unitStart && (keyUnit || !structureSent);
            structureSent |= attach;
            extension = extension(new Av1DependencyDescriptor(unitStart, marker, keyUnit ? 0 : 1, frameNumber,
                    attach ? structure : null, -1, null, null, null));
        }
        unitStart = false;
        out.add(packet.toByteArray(), marker, timestamp, extension);
    }
}
