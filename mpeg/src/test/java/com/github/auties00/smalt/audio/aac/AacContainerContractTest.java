package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.ChannelMapping;
import com.github.auties00.smalt.audio.aac.internal.ProgramConfig;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the contract between the AAC decoder and the audio container readers: multi-block
 * ADTS packets, program configurations copied from ADTS into the {@code AudioSpecificConfig},
 * WAVE-ordered layouts, and untrimmed output.
 */
class AacContainerContractTest {
    static List<AudioBuffer> decodePacket(AacDecoder decoder, byte[] packet, List<float[][]> sink) {
        var buffers = new ArrayList<AudioBuffer>();
        decoder.decode(new Packet(1, packet, 0, 0, 0, true), buffer -> {
            buffers.add(buffer);
            sink.add(AacTestSupport.copy(buffer));
        });
        return buffers;
    }

    static AacTestSupport.Stream stereo() {
        return AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("clicks", 2, 48000, 2, "aac", "-b:a", "96000")));
    }

    @ParameterizedTest(name = "{0} blocks per packet")
    @ValueSource(ints = {2, 3, 4})
    @DisplayName("decodes every raw data block of a multi-block ADTS packet, one buffer each")
    void multipleBlocks(int blocks) {
        var stream = stereo();
        var separate = new ArrayList<float[][]>();
        var reference = new AacDecoder(stream.track());
        for (var frame : stream.frames()) {
            decodePacket(reference, frame, separate);
        }
        var grouped = new ArrayList<float[][]>();
        var decoder = new AacDecoder(stream.track());
        for (var start = 0; start + blocks <= stream.frames().size(); start += blocks) {
            var packet = new ByteArrayOutputStream();
            for (var i = start; i < start + blocks; i++) {
                packet.writeBytes(stream.frames().get(i));
            }
            assertEquals(blocks, decodePacket(decoder, packet.toByteArray(), grouped).size());
        }
        for (var i = 0; i < grouped.size(); i++) {
            for (var c = 0; c < 2; c++) {
                assertArrayEquals(separate.get(i)[c], grouped.get(i)[c], "block " + i);
                assertEquals(1024, grouped.get(i)[c].length);
            }
        }
    }

    @Test
    @DisplayName("ignores zero padding after a block and rejects packets with too many blocks")
    void paddingAndLimit() {
        var stream = stereo();
        var decoder = new AacDecoder(stream.track());
        var padded = Arrays.copyOf(stream.frames().getFirst(), stream.frames().getFirst().length + 5);
        assertEquals(1, decodePacket(decoder, padded, new ArrayList<>()).size());
        var endOnly = new byte[9];
        Arrays.fill(endOnly, (byte) 0xE0);
        assertThrows(MediaException.Malformed.class, () -> decodePacket(new AacDecoder(stream.track()), endOnly, new ArrayList<>()));
        var four = new byte[4];
        Arrays.fill(four, (byte) 0xE0);
        assertEquals(4, decodePacket(new AacDecoder(stream.track()), four, new ArrayList<>()).size());
    }

    @Test
    @DisplayName("takes the layout of ADTS channel configuration 0 from the PCE copied into the AudioSpecificConfig")
    void adtsProgramConfiguration() {
        var file = AacDecoderDifferentialTest.fixture("tones", 6, 48000, 2, "aac", "-b:a", "384000", "-aac_pce", "1");
        var stream = AacTestSupport.parseAdts(Fixtures.read(file));
        var first = stream.frames().getFirst();
        var reader = new AacBitReader(first);
        assertEquals(5, reader.read(3), "the first element of the frame is the in-band PCE");
        var program = ProgramConfig.parse(reader);
        var writer = new MsbBitWriter();
        writer.write(2, 5);
        writer.write(3, 4);
        writer.write(0, 4);
        writer.write(0, 3);
        writer.write(program.instanceTag(), 4);
        writer.write(program.objectType(), 2);
        writer.write(program.samplingIndex(), 4);
        writer.write(program.front().size(), 4);
        writer.write(program.side().size(), 4);
        writer.write(program.back().size(), 4);
        writer.write(program.lfe().size(), 2);
        writer.write(0, 3);
        writer.write(program.couplingElements().size(), 4);
        writer.write(0, 3);
        for (var list : List.of(program.front(), program.side(), program.back())) {
            for (var element : list) {
                writer.write(element.pair() ? 1 : 0, 1);
                writer.write(element.tag(), 4);
            }
        }
        for (var tag : program.lfe()) {
            writer.write(tag, 4);
        }
        for (var element : program.couplingElements()) {
            writer.write(element.pair() ? 1 : 0, 1);
            writer.write(element.tag(), 4);
        }
        writer.alignWithZeros();
        writer.write(0, 8);
        var config = writer.toByteArray();
        var decoder = new AacDecoder(new AacTestSupport.Stream(config, stream.frames(), 48000, 6).track());
        var expected = ChannelMapping.ofProgram(program, MediaLimits.DEFAULT).layout();
        assertEquals(expected, decoder.layout(), "the layout is known before the first packet");
        var decoded = AacTestSupport.decodeAll(decoder, stream.frames());
        assertEquals(expected, decoded.layout());
        Oracle.assume(Oracle.Tool.FFMPEG);
        AacDecoderDifferentialTest.assertMatchesFfmpegUnordered(file);
    }

    @Test
    @DisplayName("outputs the WAVE-ordered layouts a container declares (mono, stereo, 5.0, 5.1, and 2.1 from a PCE)")
    void waveOrder() {
        for (var channels : new int[]{1, 2, 5, 6}) {
            var stream = AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("tones", channels, 48000, 1, "aac",
                    "-b:a", Integer.toString(64000 * channels))));
            var decoder = new AacDecoder(stream.track());
            assertEquals(ChannelLayout.defaultFor(channels), decoder.layout(), channels + " channels");
            assertEquals(ChannelLayout.defaultFor(channels), AacTestSupport.decodeAll(decoder, stream.frames()).layout());
        }
        var twoOne = AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("tones", 3, 48000, 1, "aac",
                "-b:a", "192000")));
        assertEquals(new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.LOW_FREQUENCY)),
                AacTestSupport.decodeAll(twoOne).layout(), "ffmpeg's 2.1 layout, signaled by an in-band PCE");
    }

    @Test
    @DisplayName("outputs every sample, including the first frame's priming, leaving trimming to the reader")
    void untrimmed() {
        var input = TestSignals.generate(TestSignals.Kind.MUSIC, 48000, 2, 1);
        var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 128000));
        var frames = new ArrayList<byte[]>();
        encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, input, input[0].length), p -> frames.add(p.data()));
        encoder.flush(p -> frames.add(p.data()));
        var decoded = AacTestSupport.decodeAll(new AacDecoder(AacDecoderRobustnessTest.track(encoder.codecConfig().orElseThrow())), frames);
        assertEquals(frames.size() * 1024L, decoded.frames(), "every packet yields a whole frame, the first included");
        var aligned = new float[][]{Arrays.copyOfRange(decoded.planes()[0], encoder.delay(), encoder.delay() + input[0].length)};
        assertTrue(TestSignals.segmentalSnr(new float[][]{input[0]}, aligned) > 20,
                "skipping delay() samples aligns the output with the input");
    }
}
