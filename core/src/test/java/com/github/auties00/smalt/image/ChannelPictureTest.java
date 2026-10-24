package com.github.auties00.smalt.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the channel layout model and the three channel picture types: validation, the color model derived
 * from a layout, alpha lookup, and the sample array contracts.
 */
@DisplayName("Channel pictures")
class ChannelPictureTest {
    @Nested
    @DisplayName("layouts")
    class Layouts {
        @Test
        @DisplayName("the constants have the documented models, counts and alpha")
        void constants() {
            assertEquals(ColorModel.GRAY, PixelLayout.GRAY.colorModel());
            assertEquals(1, PixelLayout.GRAY.count());
            assertEquals(1, PixelLayout.GRAY_ALPHA.alphaIndex());
            assertEquals(ColorModel.RGB, PixelLayout.RGBA.colorModel());
            assertEquals(3, PixelLayout.RGBA.colorChannels());
            assertEquals(3, PixelLayout.RGBA.alphaIndex());
            assertFalse(PixelLayout.RGB.hasAlpha());
            assertEquals(ColorModel.CMYK, PixelLayout.CMYK.colorModel());
            assertEquals(4, PixelLayout.CMYK.colorChannels());
            assertEquals(4, PixelLayout.CMYKA.alphaIndex());
            assertFalse(PixelLayout.CMYKA.isAlphaPremultiplied());
            assertEquals(List.of(ChannelType.CYAN, ChannelType.MAGENTA, ChannelType.YELLOW, ChannelType.BLACK),
                    PixelLayout.CMYK.channels().stream().map(Channel::type).toList());
        }

        @Test
        @DisplayName("ink layouts keep their names and count any number of inks")
        void inks() {
            var layout = PixelLayout.ofInks(List.of("Cyan", "", "PANTONE 185 C"))
                    .withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA))
                    .withChannel(Channel.ofSpot("Varnish"));
            assertEquals(ColorModel.INK, layout.colorModel());
            assertEquals(3, layout.colorChannels());
            assertEquals(5, layout.count());
            assertEquals("PANTONE 185 C", layout.channel(2).name());
            assertEquals("", layout.channel(1).name());
            assertEquals(3, layout.alphaIndex());
            assertTrue(layout.isAlphaPremultiplied());
            assertEquals(4, layout.indexOf(ChannelType.SPOT));
            assertEquals(-1, layout.indexOf(ChannelType.DEPTH));
            assertEquals(PixelLayout.ofInks(List.of("Cyan", "", "PANTONE 185 C")), layout.colorOnly());
            assertSame(PixelLayout.CMYK, PixelLayout.CMYK.colorOnly());
        }

        @Test
        @DisplayName("equality covers the channel names")
        void equality() {
            assertEquals(PixelLayout.ofInks(List.of("A")), PixelLayout.of(Channel.ofInk("A")));
            assertNotEquals(PixelLayout.ofInks(List.of("A")), PixelLayout.ofInks(List.of("B")));
            assertEquals(PixelLayout.RGBA, PixelLayout.of(Channel.of(ChannelType.RED),
                    Channel.of(ChannelType.GREEN), Channel.of(ChannelType.BLUE), Channel.of(ChannelType.ALPHA)));
        }

        @Test
        @DisplayName("layouts whose color channels form no model are rejected")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> new PixelLayout(List.of()));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(Channel.of(ChannelType.ALPHA)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(Channel.of(ChannelType.RED),
                    Channel.of(ChannelType.GREEN)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(Channel.of(ChannelType.BLUE),
                    Channel.of(ChannelType.GREEN), Channel.of(ChannelType.RED)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(Channel.of(ChannelType.GRAY),
                    Channel.of(ChannelType.GRAY)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(Channel.of(ChannelType.CYAN),
                    Channel.of(ChannelType.MAGENTA), Channel.of(ChannelType.YELLOW)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.of(Channel.of(ChannelType.INK),
                    Channel.of(ChannelType.BLACK)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.GRAY_ALPHA
                    .withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.RGBA
                    .withChannel(Channel.of(ChannelType.GRAY)));
            assertThrows(IllegalArgumentException.class, () -> PixelLayout.ofInks(List.of()));
            var names = new ArrayList<String>();
            names.add(null);
            assertThrows(NullPointerException.class, () -> PixelLayout.ofInks(names));
            assertThrows(NullPointerException.class, () -> new Channel(ChannelType.INK, null));
        }

        @Test
        @DisplayName("the channel types split into color and extra channels")
        void types() {
            for (var type : ChannelType.values()) {
                var color = switch (type) {
                    case GRAY, RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW, BLACK, INK -> true;
                    default -> false;
                };
                assertEquals(color, type.isColor(), type.name());
                assertEquals(type == ChannelType.ALPHA || type == ChannelType.PREMULTIPLIED_ALPHA, type.isAlpha());
            }
        }

        @Test
        @DisplayName("the channel list is copied")
        void copied() {
            var list = new ArrayList<>(PixelLayout.RGB.channels());
            var layout = new PixelLayout(list);
            list.add(Channel.of(ChannelType.ALPHA));
            assertEquals(3, layout.count());
            assertThrows(UnsupportedOperationException.class, () -> layout.channels().add(Channel.of(ChannelType.DEPTH)));
        }
    }

    @Nested
    @DisplayName("pictures")
    class Pictures {
        @Test
        @DisplayName("allocate tight zero-filled samples of the layout's count")
        void allocate() {
            var eight = Channel8Picture.allocate(5, 3, PixelLayout.CMYKA);
            assertEquals(25, eight.stride());
            assertEquals(75, eight.pixels().length);
            assertEquals(5, eight.channelCount());
            assertEquals(8, eight.bitDepth());
            assertTrue(eight.hasAlpha());
            var sixteen = Channel16Picture.allocate(2, 2, PixelLayout.GRAY);
            assertEquals(16, sixteen.bitDepth());
            assertFalse(sixteen.hasAlpha());
            assertEquals(4, sixteen.pixels().length);
            var floating = ChannelFloatPicture.allocate(1, 4, PixelLayout.RGB);
            assertEquals(32, floating.bitDepth());
            assertEquals(12, floating.pixels().length);
            assertSame(PixelLayout.RGB, floating.layout());
        }

        @Test
        @DisplayName("wrap existing samples, sharing them, with strides past the row")
        void wrap() {
            var samples = new short[2 * 10 + 8];
            var picture = Channel16Picture.of(2, 3, 10, samples, PixelLayout.CMYK);
            assertSame(samples, picture.pixels());
            assertEquals(10, picture.stride());
            assertThrows(IllegalArgumentException.class, () -> Channel16Picture.of(2, 3, 7, samples, PixelLayout.CMYK));
            assertThrows(IllegalArgumentException.class,
                    () -> Channel16Picture.of(2, 3, 10, new short[27], PixelLayout.CMYK));
            assertThrows(IllegalArgumentException.class, () -> Channel8Picture.of(0, 3, 4, new byte[12], PixelLayout.GRAY));
            assertThrows(IllegalArgumentException.class, () -> ChannelFloatPicture.allocate(-1, 3, PixelLayout.GRAY));
            assertThrows(IllegalArgumentException.class,
                    () -> Channel8Picture.allocate(1 << 15, 1 << 15, PixelLayout.CMYKA));
            assertThrows(NullPointerException.class, () -> Channel8Picture.of(1, 1, 1, null, PixelLayout.GRAY));
            assertThrows(NullPointerException.class, () -> Channel8Picture.of(1, 1, 1, new byte[1], null));
        }

        @Test
        @DisplayName("are pictures a pattern switch can tell apart")
        void sealed() {
            Picture[] pictures = {Channel8Picture.allocate(1, 1, PixelLayout.GRAY),
                    Channel16Picture.allocate(1, 1, PixelLayout.GRAY),
                    ChannelFloatPicture.allocate(1, 1, PixelLayout.GRAY), RgbaPicture.allocate(1, 1, false)};
            var names = new ArrayList<String>();
            for (var picture : pictures) {
                names.add(switch (picture) {
                    case Channel8Picture _ -> "8";
                    case Channel16Picture _ -> "16";
                    case ChannelFloatPicture _ -> "float";
                    case RgbaPicture _, Rgba16Picture _, YuvPicture _, Yuv16Picture _ -> "other";
                });
            }
            assertEquals(List.of("8", "16", "float", "other"), names);
        }
    }

    @Test
    @DisplayName("the decoder options carry the keep-channels setting")
    void options() {
        assertFalse(ImageDecoderOptions.defaults().keepChannels());
        var keep = ImageDecoderOptions.builder().keepChannels(true).build();
        assertTrue(keep.keepChannels());
        assertTrue(keep.toBuilder().build().keepChannels());
        assertNotEquals(ImageDecoderOptions.defaults(), keep);
        assertNotEquals(ImageDecoderOptions.defaults().hashCode(), keep.hashCode());
        assertTrue(keep.toString().contains("keepChannels=true"));
        assertEquals(ImageDecoderOptions.defaults(), keep.toBuilder().keepChannels(false).build());
    }
}
