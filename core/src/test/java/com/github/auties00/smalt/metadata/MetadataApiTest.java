package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the additions to the metadata package: {@link TagEditor#read(Path, TagReadOptions)},
 * {@link TagWriteOptions.Builder#sourceId3Version()} and the nullable-language form of
 * {@link XmpValue.Text}.
 */
@DisplayName("Metadata additions")
class MetadataApiTest {
    @Test
    @DisplayName("TagEditor.read(Path, TagReadOptions) reads as the source overload does")
    void readPath(@TempDir Path directory) throws IOException {
        var pcm = PcmPackets.tone(Codec.PCM_S16LE, 8000, ChannelLayout.MONO, 800);
        var wav = MuxSupport.toMemory(List.of(pcm.track()), pcm.packets(), WavMuxer::new);
        var tags = MediaTags.builder().set(StandardTag.TITLE, "A tone").set(StandardTag.ARTIST, "Smalt").build();
        var data = TagEditor.write(wav, tags, TagWriteOptions.defaults());
        var file = Files.write(directory.resolve("tagged.wav"), data);
        var expected = TagEditor.read(MediaSource.of(data), TagReadOptions.defaults());
        assertEquals(expected, TagEditor.read(file, TagReadOptions.defaults()));
        assertEquals(Optional.of("A tone"), TagEditor.read(file).get(StandardTag.TITLE));
        Files.delete(file);
    }

    @Test
    @DisplayName("TagWriteOptions.Builder.sourceId3Version keeps the version of the file")
    void sourceId3Version() {
        var options = TagWriteOptions.builder().id3Version(TagWriteOptions.Id3Version.V2_4).sourceId3Version().build();
        assertEquals(Optional.empty(), options.id3Version());
        assertEquals(TagWriteOptions.defaults(), options);
    }

    @Test
    @DisplayName("XmpValue.Text takes a nullable language")
    void xmpText() {
        assertEquals(Optional.of("it-IT"), new XmpValue.Text("Ciao", "it-IT").language());
        assertEquals(Optional.empty(), new XmpValue.Text("Ciao", null).language());
        assertEquals(new XmpValue.Text("Ciao", "it-IT"), new XmpValue.Text("Ciao", "it-IT"));
        assertEquals(XmpValue.text("Ciao"), XmpValue.text("Ciao", null));
        assertEquals(Optional.of("x-default"), XmpValue.text("Ciao", "x-default").language());
    }
}
