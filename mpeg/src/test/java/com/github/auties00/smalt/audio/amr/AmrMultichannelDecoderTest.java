package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the multichannel decoding of {@link AmrDecoder}: storage files interleaved from single-channel Smalt encodes
 * (different modes, with and without discontinuous transmission, on different signals) must decode, channel by
 * channel, exactly as the single-channel files do, through the decoder and through {@link AudioReader}; a packet
 * ending inside a frame-block conceals exactly the missing channels.
 */
class AmrMultichannelDecoderTest {
    // the per-channel encoder settings and signal offsets of the interleaved files
    private static final List<AmrEncoderOptions> NB_CHANNELS = List.of(AmrEncoderOptions.of(AmrMode.NB_12_2),
            AmrEncoderOptions.builder().mode(AmrMode.NB_5_90).dtx(true).build(),
            AmrEncoderOptions.builder().mode(AmrMode.NB_7_95).dtx(true).vad(AmrVad.OPTION_2).build());

    private static final List<AmrEncoderOptions> WB_CHANNELS = List.of(AmrEncoderOptions.of(AmrMode.WB_23_85),
            AmrEncoderOptions.builder().mode(AmrMode.WB_8_85).dtx(true).build(),
            AmrEncoderOptions.of(AmrMode.WB_15_85));

    // channel c is the test signal rotated by c fifths, so the channels differ and stay speech-like
    static short[] signal(int rate, int channel) {
        var signal = AmrTestSupport.signal(rate);
        var shift = channel * signal.length / 5;
        var rotated = new short[signal.length];
        System.arraycopy(signal, shift, rotated, 0, signal.length - shift);
        System.arraycopy(signal, 0, rotated, signal.length - shift, shift);
        return rotated;
    }

    static List<List<Packet>> encodes(Codec codec, int channels) {
        var settings = codec == Codec.AMR_WB ? WB_CHANNELS : NB_CHANNELS;
        var result = new ArrayList<List<Packet>>();
        for (var c = 0; c < channels; c++) {
            result.add(AmrTestSupport.encode(settings.get(c % settings.size()), signal(AmrTestSupport.rate(codec), c)));
        }
        return result;
    }

    static byte[] multichannel(Codec codec, List<List<Packet>> channels) {
        var out = new ByteArrayOutputStream();
        out.writeBytes((codec == Codec.AMR_WB ? "#!AMR-WB_MC1.0\n" : "#!AMR_MC1.0\n").getBytes());
        out.writeBytes(new byte[]{0, 0, 0, (byte) channels.size()});
        var count = channels.getFirst().size();
        for (var k = 0; k < count; k++) {
            for (var channel : channels) {
                out.writeBytes(channel.get(k).data());
            }
        }
        return out.toByteArray();
    }

    // decodes every packet of a file's track, returning the samples of each channel
    static short[][] decodeFile(byte[] file) {
        try (var demuxer = new AmrDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return decode(track, packets);
        }
    }

    static short[][] decode(AudioTrack track, List<Packet> packets) {
        try (var decoder = new AmrDecoder(track)) {
            var channels = decoder.layout().channels();
            assertEquals(track.layout().orElseThrow(), decoder.layout());
            var output = new ArrayList<short[][]>();
            for (var packet : packets) {
                decoder.decode(packet, buffer -> {
                    assertEquals(channels, buffer.channels());
                    var block = new short[channels][buffer.frames()];
                    for (var c = 0; c < channels; c++) {
                        var plane = buffer.plane(c);
                        for (var i = 0; i < buffer.frames(); i++) {
                            block[c][i] = (short) Math.round(plane[i] * 32768f);
                        }
                    }
                    output.add(block);
                });
            }
            var total = output.stream().mapToInt(block -> block[0].length).sum();
            var result = new short[channels][total];
            var position = 0;
            for (var block : output) {
                for (var c = 0; c < channels; c++) {
                    System.arraycopy(block[c], 0, result[c], position, block[c].length);
                }
                position += block[0].length;
            }
            return result;
        }
    }

    static AudioTrack track(Codec codec, int channels) {
        var rate = AmrTestSupport.rate(codec);
        return AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(rate)).duration(-1)
                .sampleRate(rate).layout(ChannelLayout.defaultFor(channels)).build();
    }

    @Test
    @DisplayName("re-encodes a multichannel file for a container that carries single-channel AMR only")
    void transcode() {
        var file = multichannel(Codec.AMR_NB, encodes(Codec.AMR_NB, 2));
        var output = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(file), output, TranscodeOptions.of(OutputContainer.MP4));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(output.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertNotEquals(Codec.AMR_NB, track.codec());
            assertNotNull(demuxer.read());
        }
    }

    @ParameterizedTest(name = "{0} channels")
    @ValueSource(ints = {2, 3, 6})
    @DisplayName("decodes every channel of an interleaved AMR-NB or AMR-WB file as its single-channel file")
    void perChannel(int channels) {
        for (var codec : List.of(Codec.AMR_NB, Codec.AMR_WB)) {
            var encodes = encodes(codec, channels);
            var decoded = decodeFile(multichannel(codec, encodes));
            assertEquals(channels, decoded.length);
            for (var c = 0; c < channels; c++) {
                var mono = AmrTestSupport.decode(codec, encodes.get(c));
                assertArrayEquals(mono, decoded[c], codec + " channel " + c);
                var reference = decodeFile(AmrTestSupport.storage(codec, encodes.get(c)));
                assertArrayEquals(reference[0], decoded[c], codec + " channel " + c + " from its .amr file");
            }
        }
    }

    @Test
    @DisplayName("reads an interleaved file through AudioReader with one plane per channel")
    void reader() {
        var encodes = encodes(Codec.AMR_NB, 2);
        var file = multichannel(Codec.AMR_NB, encodes);
        var left = AmrTestSupport.decode(Codec.AMR_NB, encodes.get(0));
        var right = AmrTestSupport.decode(Codec.AMR_NB, encodes.get(1));
        try (var reader = AudioReader.open(MediaSource.of(file))) {
            assertEquals(ChannelLayout.STEREO, reader.layout());
            assertEquals(8000, reader.sampleRate());
            var position = 0;
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                for (var i = 0; i < buffer.frames(); i++, position++) {
                    assertEquals(left[position], (short) Math.round(buffer.plane(0)[i] * 32768f), "left " + position);
                    assertEquals(right[position], (short) Math.round(buffer.plane(1)[i] * 32768f),
                            "right " + position);
                }
            }
            assertEquals(left.length, position);
        }
    }

    @Test
    @DisplayName("decodes several frame-blocks of one packet, and conceals the channels a cut packet lacks")
    void packing() {
        var codec = Codec.AMR_WB;
        var encodes = encodes(codec, 3);
        var count = encodes.getFirst().size();
        var track = track(codec, 3);
        var packets = new ArrayList<Packet>();
        var expected = new ArrayList<List<Packet>>();
        for (var c = 0; c < 3; c++) {
            expected.add(new ArrayList<>());
        }
        // packets of 1 to 4 frame-blocks; every fifth packet is cut after the frames of its first channels
        var k = 0;
        var index = 0;
        while (k < count) {
            var blocks = Math.min(1 + index % 4, count - k);
            var out = new ByteArrayOutputStream();
            for (var b = 0; b < blocks; b++, k++) {
                for (var c = 0; c < 3; c++) {
                    out.writeBytes(encodes.get(c).get(k).data());
                }
            }
            var data = out.toByteArray();
            for (var c = 0; c < 3; c++) {
                var channelPackets = expected.get(c);
                for (var b = k - blocks; b < k; b++) {
                    channelPackets.add(encodes.get(c).get(b));
                }
            }
            if (index % 5 == 4) {
                // drop the last frame of the last block and half of the frame before it
                var lastBlock = k - 1;
                var dropped = encodes.get(2).get(lastBlock).size() + encodes.get(1).get(lastBlock).size() / 2;
                data = Arrays.copyOf(data, data.length - dropped);
                var middle = encodes.get(1).get(lastBlock).data();
                var cutFrame = Arrays.copyOf(middle, middle.length - middle.length / 2);
                expected.get(1).set(expected.get(1).size() - 1, new Packet(0, cutFrame, 0, 0, 320, true));
                expected.get(2).set(expected.get(2).size() - 1, new Packet(0, new byte[0], 0, 0, 320, true));
            }
            packets.add(new Packet(0, data, 0, 0, blocks * 320, true));
            index++;
        }
        var decoded = decode(track, packets);
        for (var c = 0; c < 3; c++) {
            assertArrayEquals(AmrTestSupport.decode(codec, expected.get(c)), decoded[c], "channel " + c);
        }
    }

    @Test
    @DisplayName("conceals every channel of an empty packet and of a packet marked corrupt")
    void concealment() {
        var codec = Codec.AMR_NB;
        var encodes = encodes(codec, 2);
        var track = track(codec, 2);
        var packets = new ArrayList<Packet>();
        var expected = List.of(new ArrayList<Packet>(), new ArrayList<Packet>());
        for (var k = 0; k < encodes.getFirst().size(); k++) {
            var left = encodes.get(0).get(k);
            var right = encodes.get(1).get(k);
            if (k % 9 == 3) {
                packets.add(new Packet(0, new byte[0], 0, 0, 160, true));
                expected.get(0).add(new Packet(0, new byte[0], 0, 0, 160, true));
                expected.get(1).add(new Packet(0, new byte[0], 0, 0, 160, true));
                continue;
            }
            var data = new byte[left.size() + right.size()];
            System.arraycopy(left.data(), 0, data, 0, left.size());
            System.arraycopy(right.data(), 0, data, left.size(), right.size());
            var flags = k % 9 == 6 ? EnumSet.of(PacketFlag.CORRUPT) : EnumSet.noneOf(PacketFlag.class);
            packets.add(new Packet(0, data, 0, 0, 160, true).withFlags(flags));
            expected.get(0).add(left.withFlags(flags));
            expected.get(1).add(right.withFlags(flags));
        }
        var decoded = decode(track, packets);
        for (var c = 0; c < 2; c++) {
            assertArrayEquals(AmrTestSupport.decode(codec, expected.get(c)), decoded[c], "channel " + c);
        }
    }

    @Test
    @DisplayName("resets every channel, and rejects tracks of more than 15 channels")
    void resetAndBounds() {
        var codec = Codec.AMR_NB;
        var encodes = encodes(codec, 2);
        var file = multichannel(codec, encodes);
        var packets = new ArrayList<Packet>();
        try (var demuxer = new AmrDemuxer(MediaSource.of(file))) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        var first = decode(track(codec, 2), packets);
        try (var decoder = new AmrDecoder(track(codec, 2))) {
            for (var packet : packets.subList(0, 40)) {
                decoder.decode(packet, buffer -> {
                });
            }
            decoder.reset();
            var position = new int[1];
            for (var packet : packets) {
                decoder.decode(packet, buffer -> {
                    for (var i = 0; i < buffer.frames(); i++, position[0]++) {
                        assertEquals(first[0][position[0]], (short) Math.round(buffer.plane(0)[i] * 32768f));
                        assertEquals(first[1][position[0]], (short) Math.round(buffer.plane(1)[i] * 32768f));
                    }
                });
            }
        }
        assertThrows(MediaException.Unsupported.class, () -> new AmrDecoder(track(codec, 16)));
        new AmrDecoder(track(codec, 15)).close();
    }
}
