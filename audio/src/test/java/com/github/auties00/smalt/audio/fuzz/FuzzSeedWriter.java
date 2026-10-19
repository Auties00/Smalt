package com.github.auties00.smalt.audio.fuzz;

import com.github.auties00.smalt.audio.opus.OpusPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;

import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.ffmpeg;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.tone;
import static com.github.auties00.smalt.testkit.fuzz.FuzzSeeds.write;

/**
 * Writes the committed seeds of the audio fuzz targets (tools/fuzz/README.md): short tones encoded by ffmpeg and
 * converted to the packet format through Smalt's demuxers, and single Opus packets taken from such streams or
 * joined by {@link OpusPackets#merge}. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true")
class FuzzSeedWriter {
    @Test
    @DisplayName("writes the seeds of the audio decoder targets")
    void seeds() {
        seed("opus", "valid-mono.bin", Codec.OPUS, ffmpeg("opus", tone(48000, 0.1), "-c:a", "libopus", "-b:a", "12k"));
        seed("opus", "valid-surround.bin", Codec.OPUS, ffmpeg("opus", tone(48000, 0.06), "-ac", "6", "-c:a",
                "libopus", "-b:a", "48k", "-mapping_family", "1"));
        seed("opus", "valid-celt-fec.bin", Codec.OPUS, ffmpeg("opus", tone(48000, 0.1), "-c:a", "libopus",
                "-b:a", "64k", "-application", "lowdelay", "-frame_duration", "5"));
        seed("vorbis", "valid-mono.bin", Codec.VORBIS, ffmpeg("ogg", tone(8000, 0.2), "-c:a", "libvorbis", "-q:a", "0"));
        seed("vorbis", "valid-stereo.bin", Codec.VORBIS, ffmpeg("ogg", tone(44100, 0.05), "-ac", "2", "-c:a",
                "libvorbis"));
        seed("flac", "valid-mono.bin", Codec.FLAC, ffmpeg("flac", tone(8000, 0.1), "-c:a", "flac"));
        seed("flac", "valid-stereo-24.bin", Codec.FLAC, ffmpeg("flac", tone(48000, 0.02), "-ac", "2", "-c:a", "flac",
                "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-lpc_type", "cholesky"));
        seed("alac", "valid-mono.bin", Codec.ALAC, ffmpeg("m4a", tone(8000, 0.2), "-c:a", "alac"));
        seed("alac", "valid-stereo-24.bin", Codec.ALAC, ffmpeg("m4a", tone(44100, 0.05), "-ac", "2", "-c:a", "alac",
                "-sample_fmt", "s32p"));
        seed("mp3", "valid-mono.bin", Codec.MP3, ffmpeg("mp3", tone(8000, 0.3), "-c:a", "libmp3lame", "-b:a", "8k"));
        seed("mp3", "valid-joint-stereo.bin", Codec.MP3, ffmpeg("mp3", tone(44100, 0.1), "-ac", "2", "-c:a",
                "libmp3lame", "-b:a", "64k"));
        seed("mp2", "valid-stereo.bin", Codec.MP2, ffmpeg("mp2", tone(32000, 0.1), "-ac", "2", "-c:a", "mp2",
                "-b:a", "64k"));
    }

    @Test
    @DisplayName("writes the seeds of the Opus packet target")
    void opusPackets() {
        var silk = packets(ffmpeg("opus", tone(16000, 0.1), "-c:a", "libopus", "-b:a", "8k", "-application", "voip"));
        var celt = packets(ffmpeg("opus", tone(48000, 0.1), "-c:a", "libopus", "-b:a", "64k", "-application",
                "lowdelay", "-frame_duration", "5"));
        var hybrid = packets(ffmpeg("opus", tone(48000, 0.1), "-ac", "2", "-c:a", "libopus", "-b:a", "24k",
                "-application", "voip", "-frame_duration", "60"));
        write(AudioFuzzTest.class, "opusPackets", "valid-silk.bin", silk.get(2));
        write(AudioFuzzTest.class, "opusPackets", "valid-celt.bin", celt.get(2));
        write(AudioFuzzTest.class, "opusPackets", "valid-hybrid-stereo.bin", hybrid.get(1));
        // two frames of one size join into a code 1 packet, frames of several sizes into a code 2 or 3 packet
        write(AudioFuzzTest.class, "opusPackets", "valid-code1.bin", OpusPackets.merge(List.of(celt.get(3),
                celt.get(3))));
        write(AudioFuzzTest.class, "opusPackets", "valid-code2.bin", OpusPackets.merge(List.of(celt.get(3),
                celt.get(4))));
        write(AudioFuzzTest.class, "opusPackets", "valid-code3.bin", OpusPackets.merge(celt.subList(2, 8)));
    }

    // the packets of the Opus track of a file, as the packet format stores them
    private static List<byte[]> packets(Path file) {
        return FuzzPackets.parse(FuzzSeeds.packets(file, Codec.OPUS, 0, 12), FuzzPackets.AUDIO_FIELDS).packets()
                .stream().map(FuzzPackets.Part::data).toList();
    }

    private static void seed(String method, String name, Codec codec, Path file) {
        write(AudioFuzzTest.class, method, name, FuzzSeeds.packets(file, codec, 0, 12));
    }
}
