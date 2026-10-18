package com.github.auties00.smalt.jit;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.AacEncoder;
import com.github.auties00.smalt.audio.aac.AacEncoderOptions;
import com.github.auties00.smalt.audio.opus.OpusEncoder;
import com.github.auties00.smalt.audio.opus.OpusEncoderOptions;
import com.github.auties00.smalt.audio.opus.internal.OpusDefines;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamDecoder;
import com.github.auties00.smalt.audio.opus.internal.OpusStreamEncoder;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.ogg.OggMuxerOptions;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Times the first operation of a fresh JVM on the paths a messaging application exercises first: a voice note
 * (a 60 s speech MP3 decoded, resampled to 16 kHz and encoded as Opus in Ogg), the first frames of
 * a call (48 kHz Opus with the call settings, 20 ms frames encoded and decoded), an image send (a
 * 2560x1440 JPEG decoded at reduced size, fitted within 1200 pixels, re-encoded with a thumbnail)
 * and the AAC track of a video send (30 s of stereo music at 128 kb/s).
 *
 * <p>Every fork runs exactly one operation, so the score is the first-use latency, compiler
 * warm-up included; read the percentiles over the forks. The {@code mode} parameter selects how
 * the kernels are dispatched, set before any kernel class initializes: {@code VECTOR} (the
 * Vector API kernels, the default) and {@code SCALAR} ({@code smalt.simd=false}). Inputs come from ffmpeg and a cached LibriVox
 * recording and are prepared outside the measured operation, without running any codec of the
 * module. Run with {@code -bm ss} defaults as annotated; more forks narrow the percentiles.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 20, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class ColdStartBenchmark {
    @Param({"VECTOR", "SCALAR"})
    public String mode;

    // call:N runs the first N 20 ms frames of a call.
    @Param({"voicenote", "call:50", "call:1000", "jpegsend", "aacenc"})
    public String workload;

    private byte[] input;
    private float[] speech;
    private float[][] music;

    @Setup
    public void setup() {
        if (mode.equals("SCALAR")) {
            System.setProperty("smalt.simd", "false");
        }
        var recording = Conformance.fetch("librivox-art_of_war_03-04_64kb.mp3",
                "https://archive.org/download/art_of_war_librivox/art_of_war_03-04_sun_tzu_64kb.mp3",
                "57e879fe7822578fd22010f1280185799a119a3482e025d9a71f49878ff45ba7");
        switch (workload.split(":")[0]) {
            case "voicenote" -> input = Fixtures.read(Fixtures.ffmpeg("mp3",
                    List.of("-t", "60", "-i", recording.toString()),
                    List.of("-ac", "1", "-ar", "44100", "-c:a", "libmp3lame", "-b:a", "64k")));
            case "call" -> {
                var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-t", "20",
                        "-i", recording.toString(), "-ac", "1", "-ar", "48000", "-f", "f32le", "-"));
                speech = new float[raw.length / 4];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(speech);
            }
            case "jpegsend" -> input = Fixtures.read(Fixtures.ffmpeg("jpg",
                    List.of("-f", "lavfi", "-i", "testsrc2=size=2560x1440:rate=1,noise=alls=20:allf=u"),
                    List.of("-frames:v", "1", "-update", "1", "-q:v", "3")));
            case "aacenc" -> music = AudioJitWorkloads.music(44100, 30);
            default -> throw new IllegalArgumentException(workload);
        }
    }

    @Benchmark
    public int run() {
        return switch (workload.split(":")[0]) {
            case "voicenote" -> voiceNote(input);
            case "call" -> call(speech, Integer.parseInt(workload.split(":")[1]));
            case "jpegsend" -> jpegSend(input);
            default -> aacEncode(music);
        };
    }

    // The lib's voice-note pipeline without the waveform, with a fixed Ogg serial.
    private static int voiceNote(byte[] mp3) {
        var output = new ByteArrayOutputStream();
        var options = OggMuxerOptions.builder().vendor("Smalt").comments(Map.of()).maxPacketsPerPage(40).maxPageDurationMicros(1_000_000).serial(0x2468ACE1).build();
        try (var reader = AudioReader.open(MediaSource.of(mp3), AudioReaderOptions.of(16000, ChannelLayout.MONO));
             var encoder = new OpusEncoder(OpusEncoderOptions.forSpeech(16000));
             var muxer = new OggMuxer(MediaSink.of(output), options)) {
            muxer.addTrack(AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(16000))
                    .codecConfig(encoder.codecConfig().orElse(null))
                    .sampleRate(16000)
                    .layout(ChannelLayout.MONO)
                    .skipSamples(encoder.delay())
                    .build());
            Consumer<Packet> writer = packet -> muxer.writePacket(new Packet(1, packet.data(), packet.pts(),
                    packet.dts(), packet.duration(), packet.keyframe()));
            AudioBuffer block;
            while ((block = reader.read()) != null) {
                encoder.encode(block, writer);
            }
            encoder.flush(writer);
            muxer.finish();
        }
        return output.size();
    }

    // The call encoder settings, as AudioJitWorkloads.opusCall, at 48 kHz with 20 ms frames.
    private static int call(float[] pcm, int frames) {
        var encoder = new OpusStreamEncoder(48000, 1, OpusDefines.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(25000);
        encoder.setVbr(true);
        encoder.setComplexity(9);
        encoder.setInbandFec(1);
        encoder.setPacketLossPerc(10);
        encoder.setDtx(true);
        encoder.setSignal(OpusDefines.OPUS_SIGNAL_VOICE);
        encoder.setLsbDepth(16);
        encoder.setMaxBandwidth(OpusDefines.OPUS_BANDWIDTH_WIDEBAND);
        var decoder = new OpusStreamDecoder(48000, 1);
        var packet = new byte[1500];
        var out = new float[5760];
        var available = pcm.length / 960;
        var sum = 0;
        for (var i = 0; i < frames; i++) {
            var length = encoder.encodeFloat(pcm, (i % available) * 960, 960, packet, 0, packet.length);
            sum += length + decoder.decodeNative(packet, 0, length, out, 0, 960, false, false, null, false);
        }
        return sum + Float.floatToRawIntBits(out[17]);
    }

    // The lib's image send path: the main image and its thumbnail.
    private static int jpegSend(byte[] jpeg) {
        var info = JpegDecoder.probe(MediaSource.of(jpeg));
        var hint = ImageDecoderOptions.builder().minSize(1200, 1200 * info.height() / info.width()).build();
        var decoded = JpegDecoder.decode(MediaSource.of(jpeg), hint).picture();
        var fitted = ImageOps.fitWithin(decoded, 1200, ResizeFilter.LANCZOS3);
        var main = JpegEncoder.encode(fitted, JpegEncoderOptions.ofQuality(80));
        var thumbnail = JpegEncoder.encode(ImageOps.fitWithin(fitted, 100, ResizeFilter.LANCZOS3),
                JpegEncoderOptions.ofQuality(70));
        return main.length + thumbnail.length;
    }

    private static int aacEncode(float[][] planes) {
        var size = new int[1];
        try (var encoder = new AacEncoder(AacEncoderOptions.of(44100, ChannelLayout.STEREO, 128000))) {
            Consumer<Packet> writer = packet -> size[0] += packet.size();
            var frames = planes[0].length;
            var block = new float[2][4096];
            for (var position = 0; position < frames; position += 4096) {
                var count = Math.min(4096, frames - position);
                System.arraycopy(planes[0], position, block[0], 0, count);
                System.arraycopy(planes[1], position, block[1], 0, count);
                encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 44100, block, count), writer);
            }
            encoder.flush(writer);
        }
        return size[0];
    }
}
