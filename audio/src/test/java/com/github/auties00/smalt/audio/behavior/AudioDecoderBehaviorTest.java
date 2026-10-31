package com.github.auties00.smalt.audio.behavior;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes the Opus, Vorbis, FLAC, ALAC, MP3, MP2, ADPCM, telephony, lossless and DSD inputs of the container samples (core's
 * {@code src/test/resources/samples}) and of the smoke tier through the I/O behavior harness
 * ({@link ReaderAsserts}), comparing each with its per-block CRC dump under {@code src/test/resources/dumps}.
 */
@DisplayName("audio decoder I/O behaviors")
class AudioDecoderBehaviorTest {
    private static final Path SAMPLES = Path.of("..", "core", "src", "test", "resources", "samples");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"flv/flv1-mp3.flv", "flv/opus-flac-multitrack.flv", "ogg/opus.opus", "ogg/vorbis.ogg",
            "ogg/flac.oga", "ogg/chained.ogg", "flac/flac.flac",
            "matroska/flac.mka", "matroska/h264-opus.mkv", "matroska/vp9-vorbis.webm", "mp4/alac.m4a",
            "mp3/vbr-xing.mp3", "mp3/cbr.mp3", "mp3/mp2-id3v1.mp2", "avi/mpeg4-mp3.avi", "wav/mp3.wav", "wav/flac.wav",
            "wav/opus.wav", "ogg/opus-ambisonic.opus", "wav/ima-adpcm.wav", "wav/ms-adpcm.wav", "wav/gsm.wav",
            "wav/g722.wav", "wav/g726.wav", "aiff/ima4.aifc", "mp4/ima4.mov", "mp4/gsm.mov", "mp4/ms-adpcm.mov",
            "avi/ima-adpcm.avi", "avi/ms-adpcm.avi", "avi/g726.avi", "matroska/ms-adpcm.mka", "matroska/g722.mka",
            "matroska/ima-adpcm.mka", "wavpack/wavpack.wv", "matroska/wavpack.mka", "ogg/speex.spx", "ape/ape.ape",
            "musepack/musepack.mpc", "dsd/dsd.dsf", "dsd/dsd.dff"})
    @DisplayName("samples: identical samples under every source behavior and clean faults")
    void sample(String sample) throws IOException {
        ReaderAsserts.audio(sample, Files.readAllBytes(SAMPLES.resolve(sample))).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vorbis-test-short.ogg", "vorbis-chain-test3.ogg", "flac-cellar-subset-38.flac",
            "flac-cellar-subset-60.flac", "fate-luckynight-mac388-c2000.ape", "fate-inside-mp7.mpc",
            "fate-dst-64fs44-2ch.dff"})
    @DisplayName("smoke vectors: identical samples under every source behavior and clean faults")
    void vector(String name) throws IOException {
        ReaderAsserts.audio("vectors/" + name, Files.readAllBytes(Conformance.fetchListed(name))).run();
    }
}
