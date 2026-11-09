package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ambisonic layouts the Audio Definition Model metadata of BW64 files declares: files the EBU ADM Renderer
 * wrote (tools/testdata/behavior/make-adm-samples.py) and chunks built here for the cases the renderer does not write.
 */
@DisplayName("BW64 ADM ambisonic layouts")
class WavAdmLayoutTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "wav");

    @Nested
    @DisplayName("files of the EBU ADM Renderer")
    class Renderer {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "adm-hoa-order1-sn3d.wav, 1, SN3D",
                "adm-hoa-order1-fuma.wav, 1, FUMA",
                "adm-hoa-order2-n3d-chna.wav, 2, N3D",
                "adm-hoa-order3-sn3d-chna.wav, 3, SN3D"})
        @DisplayName("read with the declared order and normalization, the channels in ACN order")
        void declared(String name, int order, Ambisonics.Normalization normalization) throws IOException {
            var bytes = Files.readAllBytes(SAMPLES.resolve(name));
            var expected = ChannelLayout.ofAmbisonics(Ambisonics.of(order, normalization));
            try (var demuxer = new WavDemuxer(MediaSource.of(bytes))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(expected, track.layout().orElseThrow());
                try (var decoder = AudioStreamDecoder.create(track)) {
                    assertEquals(expected, decoder.layout());
                    Packet packet;
                    while ((packet = demuxer.read()) != null) {
                        decoder.decode(packet);
                    }
                    decoder.finish();
                    var channels = expected.channels();
                    var frames = decoder.available();
                    assertEquals(2400, frames);
                    var planes = new float[channels][frames];
                    decoder.read(planes, 0, frames);
                    for (var channel = 0; channel < channels; channel++) {
                        for (var i = 0; i < frames; i++) {
                            // channel k of the AmbiX input holds a sine of 250 * (k + 1) Hz at amplitude 6000
                            var value = Math.rint(6000 * StrictMath.sin(2 * StrictMath.PI * 250 * (channel + 1) * i
                                    / 48000.0));
                            assertEquals(value, planes[channel][i] * 32768.0, 1.0, "channel " + channel + " at " + i);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("built chunks")
    class Built {
        @Test
        @DisplayName("an HOA pack's normalization applies to channel formats that name none")
        void packNormalization() {
            var axml = axml("""
                    <audioPackFormat audioPackFormatID="AP_00041001" typeDefinition="HOA">
                      <normalization>N3D</normalization>
                    </audioPackFormat>
                    """ + hoaChannels(1, null));
            var layout = read(4, chna(4, "AP_00041001"), axml);
            assertEquals(ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D)), layout);
        }

        @Test
        @DisplayName("a channel format without a normalization defaults to SN3D")
        void defaultNormalization() {
            var layout = read(4, chna(4, "AP_00041001"), axml(hoaChannels(1, null)));
            assertEquals(ChannelLayout.ofAmbisonics(1), layout);
        }

        @Test
        @DisplayName("tracks in another order carry their components in that order")
        void trackOrder() {
            var entries = new ArrayList<String[]>();
            int[] acn = {3, 1, 2, 0};
            for (var track = 1; track <= 4; track++) {
                entries.add(new String[]{"AT_0004%04x_01".formatted(0x0001 + acn[track - 1]), "AP_00040001"});
            }
            var layout = read(4, chna(entries), null);
            assertEquals(ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.SN3D,
                    List.of(3, 1, 2, 0))), layout);
        }

        @Test
        @DisplayName("common front left and right speakers become the head-locked pair beside the sound field")
        void headLocked() {
            var entries = new ArrayList<String[]>();
            for (var acn = 0; acn < 4; acn++) {
                entries.add(new String[]{"AT_0004%04x_01".formatted(0x0001 + acn), "AP_00040001"});
            }
            entries.add(new String[]{"AT_00010001_01", "AP_00010002"});
            entries.add(new String[]{"AT_00010002_01", "AP_00010002"});
            var layout = read(6, chna(entries), null);
            assertEquals(ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT)),
                    layout);
        }

        @Test
        @DisplayName("direct speakers defined in axml by their BS.2051 labels become the head-locked pair")
        void headLockedInAxml() {
            var axml = axml(hoaChannels(1, "SN3D") + """
                    <audioChannelFormat audioChannelFormatID="AC_00011001" typeDefinition="DirectSpeakers">
                      <audioBlockFormat audioBlockFormatID="AB_00011001_00000001">
                        <speakerLabel>urn:itu:bs:2051:0:speaker:M+030</speakerLabel>
                      </audioBlockFormat>
                    </audioChannelFormat>
                    <audioChannelFormat audioChannelFormatID="AC_00011002" typeDefinition="DirectSpeakers">
                      <audioBlockFormat audioBlockFormatID="AB_00011002_00000001">
                        <speakerLabel>M-030</speakerLabel>
                      </audioBlockFormat>
                    </audioChannelFormat>
                    """ + streams(List.of("1001", "1002", "1003", "1004"), "0004")
                    + streams(List.of("1001", "1002"), "0001"));
            var entries = new ArrayList<String[]>();
            entries.add(new String[]{"AT_00011001_01", "AP_00011002"});
            for (var k = 1; k <= 4; k++) {
                entries.add(new String[]{"AT_0004100%d_01".formatted(k), "AP_00041001"});
            }
            entries.add(new String[]{"AT_00011002_01", "AP_00011002"});
            var layout = read(6, chna(entries), axml);
            var speakers = new ArrayList<Speaker>(List.of(Speaker.FRONT_LEFT));
            speakers.addAll(Collections.nCopies(4, Speaker.UNKNOWN));
            speakers.add(Speaker.FRONT_RIGHT);
            assertEquals(new ChannelLayout(speakers, Optional.of(Ambisonics.of(1))), layout);
        }

        @Test
        @DisplayName("a chna entry may reference the channel format itself")
        void channelFormatReference() {
            var entries = new ArrayList<String[]>();
            for (var acn = 0; acn < 4; acn++) {
                entries.add(new String[]{"AC_0004%04x".formatted(0x0201 + acn), "AP_00040021"});
            }
            assertEquals(ChannelLayout.ofAmbisonics(new Ambisonics(Ambisonics.Normalization.FUMA, List.of(0, 1, 2, 3))),
                    read(4, chna(entries), null));
        }

        @Test
        @DisplayName("metadata that is not a sound field of one normalization leaves the format's layout")
        void rejected() {
            var plain = read(4, null, null);
            assertTrue(plain.ambisonics().isEmpty());
            var mixed = new ArrayList<String[]>();
            for (var acn = 0; acn < 4; acn++) {
                mixed.add(new String[]{"AT_0004%04x_01".formatted((acn < 2 ? 0x0001 : 0x0101) + acn), "AP_00040001"});
            }
            assertEquals(plain, read(4, chna(mixed), null), "mixed normalizations");
            var duplicate = new ArrayList<String[]>();
            for (var acn = 0; acn < 4; acn++) {
                duplicate.add(new String[]{"AT_00040001_01", "AP_00040001"});
            }
            assertEquals(plain, read(4, chna(duplicate), null), "a component twice");
            assertEquals(plain, read(4, chna(3, "AP_00040001"), null), "a track without a reference");
            var object = new ArrayList<String[]>();
            for (var acn = 0; acn < 4; acn++) {
                object.add(new String[]{"AT_00031001_01", "AP_00031001"});
            }
            assertEquals(plain, read(4, chna(object), null), "an object");
            var beyond = new ArrayList<>(mixed.subList(0, 1));
            beyond.add(new String[]{"AT_00040079_01", "AP_00040001"});
            beyond.add(new String[]{"AT_0004007a_01", "AP_00040001"});
            beyond.add(new String[]{"AT_00040002_01", "AP_00040001"});
            assertEquals(plain, read(4, chna(beyond), null), "past the common SN3D components");
            var conflicting = chna(4, "AP_00040001");
            var extra = ByteBuffer.allocate(conflicting.length + 40).order(ByteOrder.LITTLE_ENDIAN).put(conflicting);
            extra.putShort(2, (short) 5);
            extra.position(conflicting.length);
            extra.putShort((short) 1).put(ascii("ATU_00000005", 12)).put(ascii("AT_00040002_01", 14))
                    .put(ascii("AP_00040001", 11)).put((byte) 0);
            assertEquals(plain, read(4, extra.array(), null), "one track with two references");
        }

        @Test
        @DisplayName("an axml chunk that is not XML falls back on the common definitions")
        void malformedXml() {
            var layout = read(4, chna(4, "AP_00040001"), "<ebuCoreMain><unclosed>".getBytes(StandardCharsets.UTF_8));
            assertEquals(ChannelLayout.ofAmbisonics(1), layout);
        }

        @Test
        @DisplayName("truncated chna chunks leave the format's layout")
        void truncated() {
            var whole = chna(4, "AP_00040001");
            for (var length = 0; length < whole.length; length += 7) {
                var cut = java.util.Arrays.copyOf(whole, length);
                var layout = read(4, cut, null);
                assertTrue(layout.ambisonics().isEmpty(), "chna cut at " + length);
            }
        }
    }

    /**
     * Returns the layout a 16-bit PCM WAVE file with these ADM chunks reads with.
     */
    private static ChannelLayout read(int channels, byte[] chna, byte[] axml) {
        try (var demuxer = new WavDemuxer(MediaSource.of(wav(channels, chna, axml)))) {
            return ((AudioTrack) demuxer.tracks().getFirst()).layout().orElseThrow();
        }
    }

    /**
     * Returns a {@code chna} chunk whose first tracks reference the common SN3D track formats in ACN order.
     */
    private static byte[] chna(int tracks, String pack) {
        var entries = new ArrayList<String[]>();
        for (var acn = 0; acn < tracks; acn++) {
            entries.add(new String[]{pack.startsWith("AP_0004100") ? "AT_0004100%d_01".formatted(acn + 1)
                    : "AT_0004%04x_01".formatted(0x0001 + acn), pack});
        }
        return chna(entries);
    }

    /**
     * Returns a {@code chna} chunk with one entry per track, each a track format or channel format reference and a
     * pack format reference.
     */
    private static byte[] chna(List<String[]> entries) {
        var data = ByteBuffer.allocate(4 + 40 * entries.size()).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) entries.size()).putShort((short) entries.size());
        for (var index = 0; index < entries.size(); index++) {
            data.putShort((short) (index + 1)).put(ascii("ATU_%08d".formatted(index + 1), 12))
                    .put(ascii(entries.get(index)[0], 14)).put(ascii(entries.get(index)[1], 11)).put((byte) 0);
        }
        return data.array();
    }

    /**
     * Returns the {@code axml} chunk of a document whose {@code audioFormatExtended} holds the given elements, with
     * the stream and track formats of channel formats {@code AC_00041001} to {@code AC_00041004}.
     */
    private static byte[] axml(String formats) {
        var linked = formats.contains("audioStreamFormat") ? formats
                : formats + streams(List.of("1001", "1002", "1003", "1004"), "0004");
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ebuCoreMain xmlns="urn:ebu:metadata-schema:ebuCore_2016"><coreMetadata><format>
                <audioFormatExtended>
                """ + linked + "</audioFormatExtended></format></coreMetadata></ebuCoreMain>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the stream and track formats linking track format {@code AT_<type><id>_01} to channel format
     * {@code AC_<type><id>}.
     */
    private static String streams(List<String> ids, String type) {
        var text = new StringBuilder();
        for (var id : ids) {
            text.append("""
                    <audioStreamFormat audioStreamFormatID="AS_%2$s%1$s" formatDefinition="PCM">
                      <audioChannelFormatIDRef>AC_%2$s%1$s</audioChannelFormatIDRef>
                      <audioTrackFormatIDRef>AT_%2$s%1$s_01</audioTrackFormatIDRef>
                    </audioStreamFormat>
                    <audioTrackFormat audioTrackFormatID="AT_%2$s%1$s_01" formatDefinition="PCM">
                      <audioStreamFormatIDRef>AS_%2$s%1$s</audioStreamFormatIDRef>
                    </audioTrackFormat>
                    """.formatted(id, type));
        }
        return text.toString();
    }

    /**
     * Returns the HOA channel formats {@code AC_00041001} onwards of every component of an order.
     */
    private static String hoaChannels(int order, String normalization) {
        var text = new StringBuilder();
        var id = 0x1001;
        for (var degree = 0; degree <= order; degree++) {
            for (var index = -degree; index <= degree; index++) {
                text.append("""
                        <audioChannelFormat audioChannelFormatID="AC_0004%1$04x" typeDefinition="HOA" typeLabel="0004">
                          <audioBlockFormat audioBlockFormatID="AB_0004%1$04x_00000001">
                            <order>%2$d</order><degree>%3$d</degree>%4$s
                          </audioBlockFormat>
                        </audioChannelFormat>
                        """.formatted(id++, degree, index,
                        normalization == null ? "" : "<normalization>" + normalization + "</normalization>"));
            }
        }
        return text.toString();
    }

    /**
     * Returns a 16-bit PCM WAVE file of silent frames with the given ADM chunks before its data.
     */
    private static byte[] wav(int channels, byte[] chna, byte[] axml) {
        var body = new ByteArrayOutputStream();
        body.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        chunk(body, "fmt ", ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1)
                .putShort((short) channels).putInt(48000).putInt(48000 * 2 * channels).putShort((short) (2 * channels))
                .putShort((short) 16).array());
        if (chna != null) {
            chunk(body, "chna", chna);
        }
        if (axml != null) {
            chunk(body, "axml", axml);
        }
        chunk(body, "data", new byte[2 * channels * 480]);
        var file = new ByteArrayOutputStream();
        file.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        file.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size()).array());
        file.writeBytes(body.toByteArray());
        return file.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] payload) {
        out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        out.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            out.write(0);
        }
    }

    private static byte[] ascii(String text, int length) {
        return java.util.Arrays.copyOf(text.getBytes(StandardCharsets.US_ASCII), length);
    }
}
