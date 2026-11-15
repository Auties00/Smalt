package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.flac.FlacDemuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the 32-bit ALAC streams of Apple's reference encoder (alacconvert, made by
 * {@code tools/testdata/alac-oracle/make-vectors.py}) through the integer, double and float outputs: the integers must
 * hash to the MD5 of the source samples the reference decoder reproduces, the doubles must scale them exactly, and
 * the floats must round them. The streams also go through an M4A, the integer {@link AudioReader} and a transcode
 * to FLAC, which must keep every bit and carry the source's MD5 in its {@code STREAMINFO}.
 */
@DisplayName("ALAC integer output")
class AlacIntegerOutputTest {
    /** One vector of {@code apple-md5.csv}. */
    record Vector(String file, int channels, int rate, int frames, String md5) {
        @Override
        public String toString() {
            return file;
        }
    }

    /** The packets and magic cookie of a CAF file. */
    record Caf(byte[] cookie, List<byte[]> packets, long validFrames) {
    }

    static Stream<Vector> vectors() throws IOException {
        var rows = new ArrayList<Vector>();
        for (var line : new String(resource("apple/apple-md5.csv"), StandardCharsets.US_ASCII).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split(";");
            rows.add(new Vector(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), fields[4].trim()));
        }
        return rows.stream();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(AlacIntegerOutputTest.class.getResourceAsStream(name), name)) {
            return input.readAllBytes();
        }
    }

    /** Reads the {@code kuki}, {@code pakt} and {@code data} chunks of a CAF file (Apple's Core Audio Format). */
    static Caf readCaf(byte[] file) {
        var buffer = ByteBuffer.wrap(file);
        assertEquals("caff", new String(file, 0, 4, StandardCharsets.US_ASCII));
        buffer.position(8);
        byte[] cookie = null;
        long[] sizes = null;
        long validFrames = 0;
        byte[] data = null;
        while (buffer.remaining() >= 12) {
            var type = new String(file, buffer.position(), 4, StandardCharsets.US_ASCII);
            buffer.position(buffer.position() + 4);
            var size = (int) buffer.getLong();
            var start = buffer.position();
            switch (type) {
                case "kuki" -> cookie = Arrays.copyOfRange(file, start, start + size);
                case "pakt" -> {
                    var packets = (int) buffer.getLong();
                    validFrames = buffer.getLong();
                    buffer.getInt();
                    buffer.getInt();
                    sizes = new long[packets];
                    for (var i = 0; i < packets; i++) {
                        var value = 0L;
                        int b;
                        do {
                            b = buffer.get() & 0xFF;
                            value = value << 7 | (b & 0x7F);
                        } while ((b & 0x80) != 0);
                        sizes[i] = value;
                    }
                }
                // the data chunk starts with a four-byte edit count
                case "data" -> data = Arrays.copyOfRange(file, start + 4, start + size);
                default -> {
                }
            }
            buffer.position(start + size);
        }
        var packets = new ArrayList<byte[]>();
        var offset = 0;
        for (var size : Objects.requireNonNull(sizes, "pakt")) {
            packets.add(Arrays.copyOfRange(Objects.requireNonNull(data, "data"), offset, offset + (int) size));
            offset += (int) size;
        }
        return new Caf(Objects.requireNonNull(cookie, "kuki"), packets, validFrames);
    }

    private static Caf caf(Vector vector) {
        try {
            return readCaf(resource("apple/" + vector.file()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static AudioTrack track(Vector vector, byte[] cookie) {
        return AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(vector.rate()))
                .sampleRate(vector.rate()).layout(ChannelLayout.defaultFor(vector.channels())).codecConfig(cookie)
                .build();
    }

    /** Hashes planar 32-bit samples as {@code apple-md5.csv} does: interleaved, little-endian. */
    static String md5(int[][] planes, int frames) throws Exception {
        var digest = MessageDigest.getInstance("MD5");
        var bytes = ByteBuffer.allocate(planes.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < frames; i++) {
            bytes.clear();
            for (var plane : planes) {
                bytes.putInt(plane[i]);
            }
            digest.update(bytes.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int[][] append(int[][] all, int filled, int[][] block, int frames) {
        var grown = all;
        if (filled + frames > all[0].length) {
            grown = new int[all.length][];
            for (var c = 0; c < all.length; c++) {
                grown[c] = Arrays.copyOf(all[c], Math.max(2 * all[c].length, filled + frames));
            }
        }
        for (var c = 0; c < all.length; c++) {
            System.arraycopy(block[c], 0, grown[c], filled, frames);
        }
        return grown;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("integers equal the source, doubles scale them exactly, floats round them")
    void outputs(Vector vector) throws Exception {
        var caf = caf(vector);
        assertEquals(vector.frames(), caf.validFrames());
        var track = track(vector, caf.cookie());
        try (var integers = new AlacDecoder(track); var doubles = new AlacDecoder(track); var floats = new AlacDecoder(track)) {
            assertTrue(integers.supportsIntegerOutput());
            assertEquals(32, integers.bitsPerSample());
            var all = new int[vector.channels()][vector.frames()];
            var filled = 0;
            var rounded = false;
            for (var data : caf.packets()) {
                var packet = new Packet(0, data, 0, 0, 4096, true);
                var block = new IntegerAudioBuffer[1];
                var doubleBlock = new DoubleAudioBuffer[1];
                var floatBlock = new AudioBuffer[1];
                integers.decodeIntegers(packet, buffer -> block[0] = buffer.copy());
                doubles.decodeDoubles(packet, buffer -> doubleBlock[0] = buffer.copy());
                floats.decode(packet, buffer -> floatBlock[0] = buffer.copy());
                var frames = block[0].frames();
                assertEquals(32, block[0].bitsPerSample());
                assertEquals(frames, doubleBlock[0].frames());
                assertEquals(frames, floatBlock[0].frames());
                for (var c = 0; c < vector.channels(); c++) {
                    for (var i = 0; i < frames; i++) {
                        var exact = block[0].plane(c)[i] * 0x1p-31;
                        assertEquals(exact, doubleBlock[0].plane(c)[i], "double sample " + i);
                        assertEquals((float) exact, floatBlock[0].plane(c)[i], "float sample " + i);
                        rounded |= (double) floatBlock[0].plane(c)[i] != exact;
                    }
                }
                all = append(all, filled, block[0].planes(), frames);
                filled += frames;
            }
            assertEquals(vector.frames(), filled);
            assertEquals(vector.md5(), md5(all, filled), "MD5 of the integer samples");
            assertTrue(rounded, "the 32-bit samples need more precision than a float holds");
        }
    }

    /** Muxes the CAF packets into an M4A with exact packet durations. */
    static byte[] m4a(Vector vector, Caf caf) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(track(vector, caf.cookie()).toBuilder().bitsPerSample(32).build());
            var pts = 0L;
            for (var data : caf.packets()) {
                var duration = Math.min(4096, vector.frames() - pts);
                muxer.writePacket(new Packet(0, data, pts, pts, duration, true));
                pts += duration;
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("an M4A of the stream reads back exactly through the integer reader")
    void reader(Vector vector) throws Exception {
        var file = m4a(vector, caf(vector));
        var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).blockFrames(1000).build();
        try (var reader = AudioReader.open(MediaSource.of(file), options)) {
            assertEquals(32, reader.bitsPerSample());
            var all = new int[vector.channels()][vector.frames()];
            var filled = 0;
            IntegerAudioBuffer block;
            while ((block = reader.readIntegers()) != null) {
                all = append(all, filled, block.planes(), block.frames());
                filled += block.frames();
            }
            assertEquals(vector.frames(), filled);
            assertEquals(vector.md5(), md5(all, filled));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("a transcode to FLAC keeps every bit and carries the source's MD5")
    void toFlac(Vector vector) throws Exception {
        assertTranscodesToFlac(vector, m4a(vector, caf(vector)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("a transcode from Matroska, whose track declares no sample size, to FLAC keeps every bit")
    void matroskaToFlac(Vector vector) throws Exception {
        var sink = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(m4a(vector, caf(vector))), sink, TranscodeOptions.of(OutputContainer.MATROSKA)
                .toBuilder().audio(AudioTranscodeOptions.copy()).build());
        var mkv = sink.toByteArray();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(mkv))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.ALAC, track.codec());
            assertEquals(0, track.bitsPerSample(), "the Matroska track leaves the sample size to the codec configuration");
        }
        assertTranscodesToFlac(vector, mkv);
    }

    /** Transcodes a file to FLAC and checks the output's sample size, MD5 and samples. */
    private static void assertTranscodesToFlac(Vector vector, byte[] file) throws Exception {
        var sink = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(file), sink, TranscodeOptions.of(OutputContainer.FLAC).toBuilder()
                .audio(AudioTranscodeOptions.encode(Codec.FLAC)).build());
        var flac = sink.toByteArray();
        assertEquals(vector.md5(), HexFormat.of().formatHex(Arrays.copyOfRange(flac, 26, 42)), "STREAMINFO MD5");
        try (var demuxer = new FlacDemuxer(MediaSource.of(flac));
             var decoder = new com.github.auties00.smalt.audio.flac.FlacDecoder((AudioTrack) demuxer.tracks().getFirst(),
                     com.github.auties00.smalt.audio.AudioDecoderOptions.builder().verifyIntegrity(true).build())) {
            assertEquals(32, decoder.bitsPerSample());
            var all = new int[vector.channels()][vector.frames()];
            var filled = new int[1];
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                decoder.decodeIntegers(packet, block -> {
                    for (var c = 0; c < all.length; c++) {
                        System.arraycopy(block.plane(c), 0, all[c], filled[0], block.frames());
                    }
                    filled[0] += block.frames();
                });
            }
            decoder.flushIntegers(block -> {
            });
            assertTrue(decoder.md5Verified());
            assertEquals(vector.frames(), filled[0]);
            assertEquals(vector.md5(), md5(all, filled[0]));
        }
    }
}
