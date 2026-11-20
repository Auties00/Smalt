package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ProgramConfig;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the streams of ISO/IEC 14496-26 that signal 5.1 with a top front pair twice, through channel configuration
 * 14 and through a program config element with a height extension ({@code al22_chCfg0PCE}, and the same stream with
 * spectral band replication, {@code al_sbr_gen_*_7.1TopPCE}). Each reference waveform names its channel ({@code fh01}
 * and {@code fh02} are the front height pair), so a decoder that ignored the extension would miss the top front
 * loudspeakers. The accuracy criterion is the 16-bit one of {@link AacConformanceTest}.
 */
class AacHeightConformanceTest {
    private static final List<String> CHANNELS = List.of("f00:FRONT_CENTER", "f01:FRONT_LEFT", "f02:FRONT_RIGHT",
            "b00:BACK_LEFT", "b01:BACK_RIGHT", "l00:LOW_FREQUENCY", "fh01:TOP_FRONT_LEFT", "fh02:TOP_FRONT_RIGHT");

    private static Path member(Conformance.Archive archive, String name) {
        return archive.fetch(archive.members().stream().filter(path -> path.endsWith("/" + name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not in " + archive.name())));
    }

    private static AacTestSupport.Stream stream(String name) {
        return AacTestSupport.parseMp4(Fixtures.read(member(Conformance.archive("mpeg4audio-aac-height-streams"), name + ".mp4")));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "al22_chCfg0PCE_32, al22_chCfg14_32, al22_32, 1024",
            "al22_chCfg0PCE_44, al22_chCfg14_44, al22_44, 1024",
            "al22_chCfg0PCE_48, al22_chCfg14_48, al22_48, 1024",
            "al_sbr_gen_32_7.1TopPCE, al_sbr_gen_32_7.1Top, al_sbr_gen_32_7.1Top, 2048",
            "al_sbr_gen_44_7.1TopPCE, al_sbr_gen_44_7.1Top, al_sbr_gen_44_7.1Top, 2048",
            "al_sbr_gen_48_7.1TopPCE, al_sbr_gen_48_7.1Top, al_sbr_gen_48_7.1Top, 2048",
    })
    @DisplayName("places the height extension's channels as channel configuration 14 does, on the reference waveforms")
    void conformant(String withExtension, String withConfiguration, String references, int frameLength) {
        var source = stream(withExtension);
        var program = AudioSpecificConfig.parse(source.config()).programConfig();
        assertEquals(List.of(ProgramConfig.Height.NORMAL, ProgramConfig.Height.NORMAL, ProgramConfig.Height.TOP),
                program.front().stream().map(ProgramConfig.Element::height).toList());
        var decoded = AacTestSupport.decodeAll(source);
        var configured = AacTestSupport.decodeAll(stream(withConfiguration));
        assertEquals(configured.layout(), decoded.layout());
        var archive = Conformance.archive("mpeg4audio-aac-height-references");
        for (var channel : CHANNELS) {
            var parts = channel.split(":");
            var speaker = Speaker.valueOf(parts[1]);
            var index = decoded.layout().indexOf(speaker);
            assertTrue(index >= 0, withExtension + " lacks " + speaker);
            Tolerance.exact().assertPcm(withExtension + " against " + withConfiguration + " " + speaker,
                    configured.planes()[index], decoded.planes()[index]);
            var reference = AacTestSupport.readWav(Fixtures.read(member(archive, references + "_" + parts[0] + ".wav")))[0];
            AacConformanceTest.assertConformant(reference, decoded.planes()[index], frameLength, withExtension + " " + speaker);
        }
    }
}
