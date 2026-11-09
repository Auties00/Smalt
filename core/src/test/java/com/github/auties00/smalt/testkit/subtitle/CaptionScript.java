package com.github.auties00.smalt.testkit.subtitle;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Writes closed caption test streams: CEA-608 byte pairs and CEA-708 service blocks scheduled on video frames, packed
 * into ATSC A/53 {@code cc_data} constructs per frame, and the SEI NAL units and MPEG-2 user data that carry them.
 *
 * <p>A CEA-608 pair goes into the first frame at or after its frame whose field has no pair yet, so a script may
 * schedule more pairs than one per frame and they follow each other; control codes are written twice, as encoders
 * send them. DTVCC packets go out at most {@code perFrame - 2} constructs per frame after the two 608 constructs.
 */
public final class CaptionScript {
    private final TreeMap<Integer, List<int[]>> field1 = new TreeMap<>();
    private final TreeMap<Integer, List<int[]>> field2 = new TreeMap<>();
    private final TreeMap<Integer, List<byte[]>> dtvcc = new TreeMap<>();
    private int sequence;

    /**
     * Returns a byte with its odd parity bit set.
     *
     * @param value the seven-bit value
     * @return the byte
     */
    public static int parity(int value) {
        return Integer.bitCount(value & 0x7F) % 2 == 1 ? value & 0x7F : value | 0x80;
    }

    /**
     * Schedules a raw byte pair of a channel's field, with parity added.
     *
     * @param frame the frame
     * @param channel the channel, 1 to 4
     * @param b1 the first byte, seven bits
     * @param b2 the second byte, seven bits
     * @return this script
     */
    public CaptionScript pair(int frame, int channel, int b1, int b2) {
        (channel <= 2 ? field1 : field2).computeIfAbsent(frame, _ -> new ArrayList<>())
                .add(new int[]{parity(b1), parity(b2)});
        return this;
    }

    /**
     * Schedules a control code, twice.
     *
     * @param frame the frame
     * @param channel the channel, 1 to 4: the channel bit of {@code b1} is set for CC2 and CC4
     * @param b1 the first byte for channel 1, {@code 0x10} to {@code 0x17}; a miscellaneous control code
     * ({@code 0x14}) becomes {@code 0x15} in field 2
     * @param b2 the second byte
     * @return this script
     */
    public CaptionScript control(int frame, int channel, int b1, int b2) {
        var first = b1 | (channel % 2 == 0 ? 0x08 : 0);
        if (channel > 2 && b1 == 0x14) {
            first = 0x15 | (channel % 2 == 0 ? 0x08 : 0);
        }
        pair(frame, channel, first, b2);
        pair(frame, channel, first, b2);
        return this;
    }

    /**
     * Schedules a miscellaneous command, twice.
     *
     * @param frame the frame
     * @param channel the channel, 1 to 4
     * @param command the second byte, {@code 0x20} (RCL) to {@code 0x2F} (EOC)
     * @return this script
     */
    public CaptionScript command(int frame, int channel, int command) {
        return control(frame, channel, 0x14, command);
    }

    /**
     * Schedules a preamble address code, twice.
     *
     * @param frame the frame
     * @param channel the channel, 1 to 4
     * @param row the row, 1 to 15
     * @param attributes the five attribute bits: {@code color << 1} for the colors and italics, {@code 0x10 |
     * indent / 4 << 1} for an indent, plus 1 for underline
     * @return this script
     */
    public CaptionScript preamble(int frame, int channel, int row, int attributes) {
        int[][] rows = {{0x11, 0x40}, {0x11, 0x60}, {0x12, 0x40}, {0x12, 0x60}, {0x15, 0x40}, {0x15, 0x60},
                {0x16, 0x40}, {0x16, 0x60}, {0x17, 0x40}, {0x17, 0x60}, {0x10, 0x40}, {0x13, 0x40}, {0x13, 0x60},
                {0x14, 0x40}, {0x14, 0x60}};
        var code = rows[row - 1];
        return control(frame, channel, code[0], code[1] | attributes);
    }

    /**
     * Schedules text of the basic character set, two characters per pair.
     *
     * @param frame the frame of the first pair
     * @param channel the channel, 1 to 4
     * @param text the characters, from 0x20 to 0x7F
     * @return this script
     */
    public CaptionScript text(int frame, int channel, String text) {
        for (var index = 0; index < text.length(); index += 2) {
            pair(frame, channel, text.charAt(index), index + 1 < text.length() ? text.charAt(index + 1) : 0);
        }
        return this;
    }

    /**
     * Schedules a CEA-708 service block in its own DTVCC caption channel packet.
     *
     * @param frame the frame
     * @param service the service number, 1 to 63
     * @param block the service block data, at most 31 bytes
     * @return this script
     */
    public CaptionScript service(int frame, int service, byte[] block) {
        var out = new ByteArrayOutputStream();
        if (service < 7) {
            out.write(service << 5 | block.length);
        } else {
            out.write(7 << 5 | block.length);
            out.write(service);
        }
        out.writeBytes(block);
        var body = out.toByteArray();
        var size = body.length + 1;
        if (size % 2 != 0) {
            size++;
        }
        var packet = new byte[size];
        packet[0] = (byte) (sequence << 6 | (size / 2 & 0x3F));
        System.arraycopy(body, 0, packet, 1, body.length);
        sequence = (sequence + 1) & 3;
        dtvcc.computeIfAbsent(frame, _ -> new ArrayList<>()).add(packet);
        return this;
    }

    /**
     * Returns the {@code cc_data} constructs of every frame.
     *
     * @param frames the number of frames
     * @param perFrame the constructs per frame, at least 2: one pair of each field, then DTVCC data, then padding
     * @return the constructs of each frame, {@code perFrame * 3} bytes
     */
    public byte[][] build(int frames, int perFrame) {
        var pairs1 = flatten(field1, frames);
        var pairs2 = flatten(field2, frames);
        var result = new byte[frames][];
        var queue = new ArrayList<byte[]>();
        for (var frame = 0; frame < frames; frame++) {
            var out = new ByteArrayOutputStream();
            var p1 = pairs1[frame];
            out.writeBytes(p1 == null ? new byte[]{(byte) 0xFC, (byte) 0x80, (byte) 0x80}
                    : new byte[]{(byte) 0xFC, (byte) p1[0], (byte) p1[1]});
            var p2 = pairs2[frame];
            out.writeBytes(p2 == null ? new byte[]{(byte) 0xFD, (byte) 0x80, (byte) 0x80}
                    : new byte[]{(byte) 0xFD, (byte) p2[0], (byte) p2[1]});
            for (var packet : dtvcc.getOrDefault(frame, List.of())) {
                for (var index = 0; index < packet.length; index += 2) {
                    queue.add(new byte[]{(byte) (index == 0 ? 0xFF : 0xFE), packet[index], packet[index + 1]});
                }
            }
            var count = 2;
            while (count < perFrame && !queue.isEmpty()) {
                out.writeBytes(queue.removeFirst());
                count++;
            }
            while (count < perFrame) {
                out.writeBytes(new byte[]{(byte) 0xFA, 0, 0});
                count++;
            }
            result[frame] = out.toByteArray();
        }
        return result;
    }

    private static int[][] flatten(TreeMap<Integer, List<int[]>> scheduled, int frames) {
        var result = new int[frames][];
        var next = 0;
        for (var entry : scheduled.entrySet()) {
            next = Math.max(next, entry.getKey());
            for (var pair : entry.getValue()) {
                if (next < frames) {
                    result[next] = pair;
                }
                next++;
            }
        }
        return result;
    }

    /**
     * Returns an ATSC {@code GA94} caption data structure, from its {@code user_identifier} on.
     *
     * @param ccData the constructs, at most 31
     * @return the structure, ending with its marker byte
     */
    public static byte[] ga94(byte[] ccData) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{'G', 'A', '9', '4', 0x03, (byte) (0xC0 | ccData.length / 3), (byte) 0xFF});
        out.writeBytes(ccData);
        out.write(0xFF);
        return out.toByteArray();
    }

    /**
     * Returns an SEI NAL unit, header included and escaped, holding a caption message.
     *
     * @param ccData the constructs, at most 31
     * @param hevc whether to write the two-byte HEVC prefix SEI header rather than the H.264 one
     * @return the NAL unit
     */
    public static byte[] seiUnit(byte[] ccData, boolean hevc) {
        var message = new ByteArrayOutputStream();
        message.writeBytes(new byte[]{(byte) 0xB5, 0x00, 0x31});
        message.writeBytes(ga94(ccData));
        var body = message.toByteArray();
        var rbsp = new ByteArrayOutputStream();
        rbsp.write(4);
        rbsp.write(body.length);
        rbsp.writeBytes(body);
        rbsp.write(0x80);
        var out = new ByteArrayOutputStream();
        if (hevc) {
            out.writeBytes(new byte[]{0x4E, 0x01});
        } else {
            out.write(0x06);
        }
        var zeros = 0;
        for (var value : rbsp.toByteArray()) {
            if (zeros >= 2 && (value & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /**
     * Inserts a caption SEI NAL unit before the first slice of a four-byte length-prefixed access unit.
     *
     * @param accessUnit the access unit
     * @param ccData the constructs
     * @param hevc whether the access unit is HEVC
     * @return the new access unit
     */
    public static byte[] withSei(byte[] accessUnit, byte[] ccData, boolean hevc) {
        var unit = seiUnit(ccData, hevc);
        var position = 0;
        while (position + 4 < accessUnit.length) {
            var length = (accessUnit[position] & 0xFF) << 24 | (accessUnit[position + 1] & 0xFF) << 16
                    | (accessUnit[position + 2] & 0xFF) << 8 | (accessUnit[position + 3] & 0xFF);
            var header = accessUnit[position + 4] & 0xFF;
            var vcl = hevc ? (header >> 1 & 0x3F) < 32 : (header & 0x1F) >= 1 && (header & 0x1F) <= 5;
            if (vcl) {
                break;
            }
            position += 4 + length;
        }
        var out = new ByteArrayOutputStream();
        out.write(accessUnit, 0, position);
        out.writeBytes(new byte[]{(byte) (unit.length >>> 24), (byte) (unit.length >>> 16),
                (byte) (unit.length >>> 8), (byte) unit.length});
        out.writeBytes(unit);
        out.write(accessUnit, position, accessUnit.length - position);
        return out.toByteArray();
    }
}
