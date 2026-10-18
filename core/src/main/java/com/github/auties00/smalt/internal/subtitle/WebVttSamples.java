package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts WebVTT cues between the packet form of {@link com.github.auties00.smalt.track.Codec#WEBVTT}
 * and the forms containers store.
 *
 * <p>The packet form holds one cue payload per packet, with the identifier and the settings in
 * the side data under {@value #IDENTIFIER} and {@value #SETTINGS}. ISO/IEC 14496-30 MP4 samples
 * hold every cue shown during the sample as a {@code vttc} box ({@code iden}, {@code sttg} and
 * {@code payl} children), or a {@code vtte} box when none is; a cue shown across several samples
 * is repeated in each. A Matroska {@code S_TEXT/WEBVTT} block holds the payload, with the settings
 * line and the identifier line in the {@code BlockAdditional} of {@code BlockAddID} 1; a
 * {@code D_WEBVTT} block (WebM, and the Matroska files of most tools) holds the identifier line,
 * the settings line and the payload.
 */
public final class WebVttSamples {
    /**
     * The side data key of the cue identifier.
     */
    public static final String IDENTIFIER = "webvtt:identifier";

    /**
     * The side data key of the cue settings list.
     */
    public static final String SETTINGS = "webvtt:settings";

    /**
     * The side data key under which the Matroska demuxer stores the first block addition.
     */
    public static final String MATROSKA_ADDITION = "matroska:BlockAdditional:1";

    /**
     * The {@code vttc} box type.
     */
    private static final int VTTC = 0x76747463;

    /**
     * The {@code vtte} box type.
     */
    private static final int VTTE = 0x76747465;

    /**
     * The {@code iden} box type.
     */
    private static final int IDEN = 0x6964656E;

    /**
     * The {@code sttg} box type.
     */
    private static final int STTG = 0x73747467;

    /**
     * The {@code payl} box type.
     */
    private static final int PAYL = 0x7061796C;

    /**
     * The {@code vttC} box type, the configuration box of the sample entry.
     */
    public static final int VTT_CONFIG = 0x76747443;

    private WebVttSamples() {
        throw new AssertionError();
    }

    /**
     * Holds one cue without its timing.
     *
     * @param identifier the identifier, empty when absent
     * @param settings the settings list, empty when absent
     * @param payload the cue text
     */
    public record Cue(String identifier, String settings, String payload) {
    }

    /**
     * Returns the cue a packet carries.
     *
     * @param packet a WebVTT packet
     * @return the cue
     */
    public static Cue cue(Packet packet) {
        return new Cue(text(packet.sideData(), IDENTIFIER), text(packet.sideData(), SETTINGS),
                TextFiles.normalizeLineBreaks(TextFiles.decodeUtf8OrLegacy(packet.data(), 0, packet.size())));
    }

    /**
     * Returns the side data that carries a cue's identifier and settings.
     *
     * @param base the side data to extend
     * @param identifier the identifier, empty when absent
     * @param settings the settings, empty when absent
     * @return the side data
     */
    public static PacketSideData sideData(PacketSideData base, String identifier, String settings) {
        var builder = base.toBuilder();
        builder.extra(IDENTIFIER, identifier.isEmpty() ? null : identifier.getBytes(StandardCharsets.UTF_8));
        builder.extra(SETTINGS, settings.isEmpty() ? null : settings.getBytes(StandardCharsets.UTF_8));
        return builder.build();
    }

    /**
     * Reads a text entry of side data.
     *
     * @param sideData the side data
     * @param key the side data entry to read
     * @return the text, empty when absent
     */
    private static String text(PacketSideData sideData, String key) {
        return sideData.extra(key).map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
    }

    /**
     * Rewrites a packet read from a Matroska {@code S_TEXT/WEBVTT} or WebM {@code D_WEBVTT}
     * track into the packet form.
     *
     * @param packet the packet as the demuxer read it
     * @param webm whether the track is a WebM {@code D_WEBVTT} track
     * @return the packet with the payload as data and the identifier and settings as side data
     */
    public static Packet fromMatroska(Packet packet, boolean webm) {
        String identifier;
        String settings;
        byte[] payload;
        var sideData = packet.sideData();
        if (webm) {
            var text = TextFiles.normalizeLineBreaks(TextFiles.decodeUtf8OrLegacy(packet.data(), 0, packet.size()));
            var first = text.indexOf('\n');
            var second = first < 0 ? -1 : text.indexOf('\n', first + 1);
            if (second < 0) {
                identifier = "";
                settings = "";
                payload = text.getBytes(StandardCharsets.UTF_8);
            } else {
                identifier = text.substring(0, first).strip();
                settings = WebVttText.normalizeSettings(text.substring(first + 1, second));
                payload = text.substring(second + 1).getBytes(StandardCharsets.UTF_8);
            }
        } else {
            var addition = sideData.extra(MATROSKA_ADDITION).map(bytes -> TextFiles.normalizeLineBreaks(
                    new String(bytes, StandardCharsets.UTF_8))).orElse("");
            var lines = addition.split("\n", 3);
            settings = WebVttText.normalizeSettings(lines[0]);
            identifier = lines.length > 1 ? lines[1].strip() : "";
            payload = packet.data();
            sideData = sideData.toBuilder().extra(MATROSKA_ADDITION, null).build();
        }
        return packet.withData(payload).withSideData(sideData(sideData, identifier, settings));
    }

    /**
     * Returns the WebM {@code D_WEBVTT} block data of a packet: the identifier line, the settings
     * line and the payload.
     *
     * @param packet a WebVTT packet
     * @return the block data
     */
    public static byte[] webmBlock(Packet packet) {
        var cue = cue(packet);
        return (cue.identifier() + "\n" + cue.settings() + "\n" + cue.payload()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encodes the cues shown during one MP4 sample.
     *
     * @param cues the cues, in the order they were written
     * @return the sample: one {@code vttc} box per cue, or a {@code vtte} box when there is none
     */
    public static byte[] encode(List<Cue> cues) {
        var out = new ByteWriter(64);
        if (cues.isEmpty()) {
            out.begin(VTTE);
            out.end();
            return out.toByteArray();
        }
        for (var cue : cues) {
            var identifier = cue.identifier().getBytes(StandardCharsets.UTF_8);
            var settings = cue.settings().getBytes(StandardCharsets.UTF_8);
            out.begin(VTTC);
            if (identifier.length > 0) {
                writeBox(out, IDEN, identifier);
            }
            if (settings.length > 0) {
                writeBox(out, STTG, settings);
            }
            writeBox(out, PAYL, cue.payload().getBytes(StandardCharsets.UTF_8));
            out.end();
        }
        return out.toByteArray();
    }

    /**
     * Decodes one MP4 sample into its cues.
     *
     * @param sample the MP4 sample, a sequence of boxes
     * @return the cues of its {@code vttc} boxes, empty for a {@code vtte} sample
     */
    public static List<Cue> decode(byte[] sample) {
        var cues = new ArrayList<Cue>();
        var position = 0;
        while (position + 8 <= sample.length) {
            var size = ByteArrays.i32be(sample, position);
            var type = ByteArrays.i32be(sample, position + 4);
            if (size < 8 || size > sample.length - position) {
                break;
            }
            if (type == VTTC) {
                var identifier = "";
                var settings = "";
                var payload = "";
                var child = position + 8;
                var end = position + size;
                while (child + 8 <= end) {
                    var childSize = ByteArrays.i32be(sample, child);
                    var childType = ByteArrays.i32be(sample, child + 4);
                    if (childSize < 8 || childSize > end - child) {
                        break;
                    }
                    var value = TextFiles.normalizeLineBreaks(TextFiles.decodeUtf8OrLegacy(sample, child + 8, childSize - 8));
                    switch (childType) {
                        case IDEN -> identifier = value.strip();
                        case STTG -> settings = WebVttText.normalizeSettings(value);
                        case PAYL -> payload = value;
                        default -> {
                        }
                    }
                    child += childSize;
                }
                cues.add(new Cue(identifier, settings, payload));
            }
            position += size;
        }
        return cues;
    }

    /**
     * Writes a box holding a byte string.
     *
     * @param out the destination
     * @param type the box type
     * @param value the box payload
     */
    private static void writeBox(ByteWriter out, int type, byte[] value) {
        out.begin(type);
        out.bytes(value);
        out.end();
    }
}
