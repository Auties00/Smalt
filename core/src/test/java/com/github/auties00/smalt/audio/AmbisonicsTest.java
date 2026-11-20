package com.github.auties00.smalt.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Ambisonics and ambisonic channel layouts")
class AmbisonicsTest {
    @Nested
    @DisplayName("Ambisonics")
    class Descriptions {
        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 3, 7, 14})
        @DisplayName("of(order) lists every ACN component in order with SN3D")
        void acn(int order) {
            var ambisonics = Ambisonics.of(order);
            assertEquals(Ambisonics.Normalization.SN3D, ambisonics.normalization());
            assertEquals((order + 1) * (order + 1), ambisonics.channels());
            assertEquals(order, ambisonics.order());
            assertTrue(ambisonics.isFull());
            assertTrue(ambisonics.isSequential());
            for (var k = 0; k < ambisonics.channels(); k++) {
                assertEquals(k, ambisonics.components().get(k));
                assertEquals(k, ambisonics.indexOf(k));
            }
            assertEquals(-1, ambisonics.indexOf(ambisonics.channels()));
        }

        @Test
        @DisplayName("lists the Furse-Malham channel sets of the B-format sub-format")
        void furseMalham() {
            // W X Y Z R S T U V K L M N O P Q in ACN
            assertEquals(List.of(0, 3, 1), Ambisonics.ofFurseMalham(3).components());
            assertEquals(List.of(0, 3, 1, 2), Ambisonics.ofFurseMalham(4).components());
            assertEquals(List.of(0, 3, 1, 8, 4), Ambisonics.ofFurseMalham(5).components());
            assertEquals(List.of(0, 3, 1, 2, 8, 4), Ambisonics.ofFurseMalham(6).components());
            assertEquals(List.of(0, 3, 1, 8, 4, 15, 9), Ambisonics.ofFurseMalham(7).components());
            assertEquals(List.of(0, 3, 1, 2, 8, 4, 15, 9), Ambisonics.ofFurseMalham(8).components());
            assertEquals(List.of(0, 3, 1, 2, 6, 7, 5, 8, 4), Ambisonics.ofFurseMalham(9).components());
            assertEquals(List.of(0, 3, 1, 2, 6, 7, 5, 8, 4, 15, 9), Ambisonics.ofFurseMalham(11).components());
            assertEquals(List.of(0, 3, 1, 2, 6, 7, 5, 8, 4, 12, 13, 11, 14, 10, 15, 9),
                    Ambisonics.ofFurseMalham(16).components());
            assertEquals(Ambisonics.Normalization.FUMA, Ambisonics.ofFurseMalham(4).normalization());
            assertEquals(1, Ambisonics.ofFurseMalham(4).order());
            assertTrue(Ambisonics.ofFurseMalham(16).isFull());
            assertFalse(Ambisonics.ofFurseMalham(16).isSequential());
            assertFalse(Ambisonics.ofFurseMalham(5).isFull());
            assertEquals(2, Ambisonics.ofFurseMalham(5).order());
            for (var count : new int[]{1, 2, 10, 12, 15, 17, 25}) {
                assertThrows(IllegalArgumentException.class, () -> Ambisonics.ofFurseMalham(count));
            }
        }

        @Test
        @DisplayName("rejects empty, duplicate and out-of-range components")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> new Ambisonics(Ambisonics.Normalization.SN3D, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new Ambisonics(Ambisonics.Normalization.SN3D,
                    List.of(0, 1, 1)));
            assertThrows(IllegalArgumentException.class, () -> new Ambisonics(Ambisonics.Normalization.SN3D,
                    List.of(-1)));
            assertThrows(IllegalArgumentException.class, () -> new Ambisonics(Ambisonics.Normalization.N3D,
                    List.of(Ambisonics.MAX_COMPONENT + 1)));
            assertThrows(IllegalArgumentException.class, () -> new Ambisonics(Ambisonics.Normalization.FUMA,
                    List.of(0, 16)));
            assertThrows(IllegalArgumentException.class, () -> Ambisonics.of(15));
            assertThrows(IllegalArgumentException.class, () -> Ambisonics.of(-1));
            assertThrows(IllegalArgumentException.class, () -> Ambisonics.of(4, Ambisonics.Normalization.FUMA));
            assertThrows(IllegalArgumentException.class,
                    () -> Ambisonics.of(4).withNormalization(Ambisonics.Normalization.FUMA));
            assertEquals(Ambisonics.of(3, Ambisonics.Normalization.FUMA),
                    Ambisonics.of(3).withNormalization(Ambisonics.Normalization.FUMA));
            assertEquals(224, Ambisonics.MAX_COMPONENT);
        }

        @Test
        @DisplayName("computes the degree of every component")
        void degree() {
            for (var l = 0; l <= 20; l++) {
                for (var m = -l; m <= l; m++) {
                    assertEquals(l, Ambisonics.degree(l * (l + 1) + m));
                }
            }
        }
    }

    @Nested
    @DisplayName("ChannelLayout")
    class Layouts {
        @Test
        @DisplayName("ambisonic layouts give the components channels of unknown position")
        void factories() {
            var foa = ChannelLayout.ofAmbisonics(1);
            assertEquals(4, foa.channels());
            assertEquals(Collections.nCopies(4, Speaker.UNKNOWN), foa.speakers());
            assertEquals(Optional.of(Ambisonics.of(1)), foa.ambisonics());
            var headLocked = ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT));
            assertEquals(6, headLocked.channels());
            assertEquals(Speaker.FRONT_LEFT, headLocked.speakers().get(4));
            assertEquals(5, headLocked.indexOf(Speaker.FRONT_RIGHT));
            for (var component = 0; component < 4; component++) {
                assertEquals(component, headLocked.indexOfComponent(component));
            }
            assertEquals(-1, headLocked.indexOfComponent(4));
            assertEquals(-1, ChannelLayout.STEREO.indexOfComponent(0));
            assertThrows(IllegalArgumentException.class,
                    () -> ChannelLayout.ofAmbisonics(Ambisonics.of(1), List.of(Speaker.UNKNOWN)));
        }

        @Test
        @DisplayName("the components follow the channels of unknown position wherever they are")
        void interleaved() {
            var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.UNKNOWN,
                    Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN), Optional.of(Ambisonics.ofFurseMalham(4)));
            assertEquals(2, layout.indexOfComponent(0));
            assertEquals(3, layout.indexOfComponent(3));
            assertEquals(4, layout.indexOfComponent(1));
            assertEquals(5, layout.indexOfComponent(2));
        }

        @Test
        @DisplayName("requires as many channels of unknown position as components")
        void mismatch() {
            assertThrows(IllegalArgumentException.class, () -> new ChannelLayout(List.of(Speaker.UNKNOWN,
                    Speaker.UNKNOWN, Speaker.UNKNOWN), Optional.of(Ambisonics.of(1))));
            assertThrows(IllegalArgumentException.class, () -> new ChannelLayout(List.of(Speaker.UNKNOWN,
                    Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.UNKNOWN, Speaker.FRONT_CENTER, Speaker.UNKNOWN),
                    Optional.of(Ambisonics.of(1))));
            assertThrows(NullPointerException.class, () -> new ChannelLayout(List.of(Speaker.UNKNOWN), null));
        }

        @Test
        @DisplayName("an ambisonic layout differs from the same channels without ambisonics")
        void equality() {
            var plain = new ChannelLayout(Collections.nCopies(4, Speaker.UNKNOWN));
            assertNotEquals(plain, ChannelLayout.ofAmbisonics(1));
            assertTrue(ChannelLayout.defaultFor(25).ambisonics().isEmpty());
            assertEquals(ChannelLayout.ofAmbisonics(1), ChannelLayout.ofAmbisonics(Ambisonics.of(1)));
            assertNotEquals(ChannelLayout.ofAmbisonics(1),
                    ChannelLayout.ofAmbisonics(Ambisonics.of(1, Ambisonics.Normalization.N3D)));
            assertTrue(ChannelLayout.STEREO.ambisonics().isEmpty());
        }
    }
}
