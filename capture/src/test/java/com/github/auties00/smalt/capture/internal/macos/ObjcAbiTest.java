package com.github.auties00.smalt.capture.internal.macos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, on any operating system, the binary contracts the macOS backend relies on: block literal
 * and descriptor layouts (Apple block ABI, libclosure), Objective-C type encodings (Clang's
 * encoder), the C structure layouts of the SDK headers, four-character codes, the struct-return
 * rule of {@code objc_msgSend}, and the Java signatures of the upcall targets.
 */
@DisplayName("Objective-C ABI")
class ObjcAbiTest {
    @Nested
    @DisplayName("block literals")
    class Blocks {
        @Test
        @DisplayName("lay out isa, flags, reserved, invoke, descriptor and the captured key like Block_layout")
        void literalLayout() {
            assertEquals(0, BlockLayout.ISA_OFFSET);
            assertEquals(8, BlockLayout.FLAGS_OFFSET);
            assertEquals(12, BlockLayout.RESERVED_OFFSET);
            assertEquals(16, BlockLayout.INVOKE_OFFSET);
            assertEquals(24, BlockLayout.DESCRIPTOR_OFFSET);
            assertEquals(32, BlockLayout.KEY_OFFSET);
            assertEquals(40, BlockLayout.LITERAL.byteSize());
            assertEquals(8, BlockLayout.LITERAL.byteAlignment());
        }

        @Test
        @DisplayName("lay out the descriptor as Block_descriptor_1 followed by Block_descriptor_3")
        void descriptorLayout() {
            assertEquals(0, BlockLayout.DESCRIPTOR_RESERVED_OFFSET);
            assertEquals(8, BlockLayout.DESCRIPTOR_SIZE_OFFSET);
            assertEquals(16, BlockLayout.DESCRIPTOR_SIGNATURE_OFFSET);
            assertEquals(24, BlockLayout.DESCRIPTOR_LAYOUT_OFFSET);
            assertEquals(32, BlockLayout.DESCRIPTOR.byteSize());
        }

        @Test
        @DisplayName("set only BLOCK_HAS_SIGNATURE: no copy helpers, not global, no stret, no extended layout")
        void flags() {
            assertEquals(0x4000_0000, BlockLayout.HAS_SIGNATURE);
            var forbidden = (1 << 24) | (1 << 25) | (1 << 26) | (1 << 28) | (1 << 29) | (1 << 31) | 0xFFFF;
            assertEquals(0, BlockLayout.HAS_SIGNATURE & forbidden);
        }

        @Test
        @DisplayName("write a descriptor whose size is the literal size and whose signature is a C string")
        void writesDescriptor() {
            try (var arena = Arena.ofConfined()) {
                // records every allocation so the signature can be read without a restricted reinterpret
                var allocations = new ArrayList<MemorySegment>();
                SegmentAllocator recording = (size, alignment) -> {
                    var segment = arena.allocate(size, alignment);
                    allocations.add(segment);
                    return segment;
                };
                var descriptor = BlockLayout.descriptor(recording, "v16@?0@8");
                assertEquals(0L, descriptor.get(JAVA_LONG, 0));
                assertEquals(40L, descriptor.get(JAVA_LONG, 8));
                var address = descriptor.get(ADDRESS, 16).address();
                var signature = allocations.stream().filter(segment -> segment.address() == address).findFirst().orElseThrow();
                assertEquals("v16@?0@8", signature.getString(0));
                assertEquals(0L, descriptor.get(ADDRESS, 24).address());
            }
        }

        @Test
        @DisplayName("write a literal whose fields point where they were told and whose key reads back")
        void writesLiteral() {
            try (var arena = Arena.ofConfined()) {
                var isa = arena.allocate(256);
                var invoke = arena.allocate(16);
                var descriptor = BlockLayout.descriptor(arena, "v12@?0B8");
                var literal = BlockLayout.literal(arena, isa, invoke, descriptor, 0x1234_5678_9ABCL);
                assertEquals(isa.address(), literal.get(ADDRESS, 0).address());
                assertEquals(BlockLayout.HAS_SIGNATURE, literal.get(JAVA_INT, 8));
                assertEquals(0, literal.get(JAVA_INT, 12));
                assertEquals(invoke.address(), literal.get(ADDRESS, 16).address());
                assertEquals(descriptor.address(), literal.get(ADDRESS, 24).address());
                assertEquals(0x1234_5678_9ABCL, BlockLayout.key(literal));
            }
        }

        @Test
        @DisplayName("keep the key readable from a bitwise copy, as _Block_copy makes")
        void survivesCopy() {
            try (var arena = Arena.ofConfined()) {
                var literal = BlockLayout.literal(arena, arena.allocate(8), arena.allocate(8),
                        BlockLayout.descriptor(arena, "v16@?0@8"), 77);
                var copy = arena.allocate(BlockLayout.LITERAL);
                copy.copyFrom(literal.asSlice(0, BlockLayout.LITERAL.byteSize()));
                assertEquals(77, BlockLayout.key(copy));
            }
        }
    }

    @Nested
    @DisplayName("type encodings")
    class Encodings {
        @Test
        @DisplayName("encode the three delegate methods as Clang does")
        void delegateMethods() {
            assertEquals("v40@0:8@16^{opaqueCMSampleBuffer=}24@32", TypeEncoding.CAPTURE_OUTPUT);
            assertEquals("v40@0:8@16^{opaqueCMSampleBuffer=}24q32", TypeEncoding.STREAM_OUTPUT);
            assertEquals("v32@0:8@16@24", TypeEncoding.STREAM_STOPPED);
        }

        @Test
        @DisplayName("encode the completion handler blocks as Clang does")
        void blocks() {
            assertEquals("v24@?0@8@16", TypeEncoding.OBJECT_ERROR_BLOCK);
            assertEquals("v16@?0@8", TypeEncoding.ERROR_BLOCK);
            assertEquals("v12@?0B8", TypeEncoding.boolBlock(true));
            assertEquals("v12@?0c8", TypeEncoding.boolBlock(false));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "c24@0:8@16|c|@",
                "@24@0:8Q16|@|Q",
                "v20@0:8c16|v|c",
                "v20@0:8B16|v|B",
                "d16@0:8|d|",
                "v24@0:8:16|v|:"
        })
        @DisplayName("match encodings the runtime reports for Foundation methods")
        void knownMethods(String expected, String result, String argument) {
            var encoding = argument == null ? TypeEncoding.method(result) : TypeEncoding.method(result, argument);
            assertEquals(expected, encoding);
        }

        @Test
        @DisplayName("widen narrow integers to four bytes of the frame")
        void widensNarrowIntegers() {
            assertEquals(4, TypeEncoding.size("c"));
            assertEquals(4, TypeEncoding.size("B"));
            assertEquals(4, TypeEncoding.size("S"));
            assertEquals(8, TypeEncoding.size("q"));
            assertEquals(8, TypeEncoding.size("^{opaqueCMSampleBuffer=}"));
            assertEquals(8, TypeEncoding.size("@?"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "{CGRect=dddd}", "?"})
        @DisplayName("reject encodings it cannot size")
        void rejectsUnknown(String type) {
            assertThrows(IllegalArgumentException.class, () -> TypeEncoding.size(type));
        }
    }

    @Nested
    @DisplayName("C structures")
    class Structures {
        @Test
        @DisplayName("lay out CMTime as value, timescale, flags, epoch in 24 bytes")
        void cmTime() {
            assertEquals(0, CmTime.VALUE_OFFSET);
            assertEquals(8, CmTime.TIMESCALE_OFFSET);
            assertEquals(12, CmTime.FLAGS_OFFSET);
            assertEquals(16, CmTime.EPOCH_OFFSET);
            assertEquals(24, CmTime.LAYOUT.byteSize());
        }

        @Test
        @DisplayName("lay out CMVideoDimensions, CGRect and CFRange as in the SDK headers")
        void others() {
            assertEquals(8, NativeLayouts.CM_VIDEO_DIMENSIONS.byteSize());
            assertEquals(4, NativeLayouts.CM_VIDEO_DIMENSIONS.byteOffset(groupElement("height")));
            assertEquals(32, NativeLayouts.CG_RECT.byteSize());
            assertEquals(16, NativeLayouts.CG_RECT.byteOffset(groupElement("size")));
            assertEquals(24, NativeLayouts.CG_RECT.byteOffset(groupElement("size"), groupElement("height")));
            assertEquals(16, NativeLayouts.CF_RANGE.byteSize());
            assertEquals(8, NativeLayouts.CF_RANGE.byteOffset(groupElement("length")));
        }

        @Test
        @DisplayName("use only naturally aligned members, as the native linker requires")
        void naturallyAligned() {
            for (var layout : List.of(CmTime.LAYOUT, NativeLayouts.CM_VIDEO_DIMENSIONS, NativeLayouts.CG_RECT,
                    NativeLayouts.CF_RANGE, BlockLayout.LITERAL, BlockLayout.DESCRIPTOR)) {
                assertNatural(layout);
            }
        }

        private static void assertNatural(StructLayout layout) {
            var offset = 0L;
            var alignment = 1L;
            for (var member : layout.memberLayouts()) {
                assertEquals(0, offset % member.byteAlignment(), layout + " misaligns " + member);
                if (member instanceof StructLayout nested) {
                    assertNatural(nested);
                } else {
                    assertEquals(member.byteSize(), member.byteAlignment(), member + " is not naturally aligned");
                }
                offset += member.byteSize();
                alignment = Math.max(alignment, member.byteAlignment());
            }
            assertEquals(alignment, layout.byteAlignment());
            assertEquals(offset, layout.byteSize(), layout + " has padding");
        }
    }

    @Nested
    @DisplayName("four-character codes")
    class Codes {
        @Test
        @DisplayName("pack the CoreVideo pixel formats big-endian")
        void pixelFormats() {
            assertEquals(0x34323076, FourCc.NV12_VIDEO_RANGE);
            assertEquals(0x34323066, FourCc.NV12_FULL_RANGE);
            assertEquals(0x42475241, FourCc.BGRA);
        }

        @Test
        @DisplayName("render printable codes as text and others in hexadecimal")
        void render() {
            assertEquals("'420v'", FourCc.toString(FourCc.NV12_VIDEO_RANGE));
            assertEquals("0x00000020", FourCc.toString(32));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "abc", "abcde", "abéd"})
        @DisplayName("reject text that is not four ASCII characters")
        void rejects(String text) {
            assertThrows(IllegalArgumentException.class, () -> FourCc.of(text));
        }
    }

    @Nested
    @DisplayName("message sending")
    class Sending {
        @ParameterizedTest
        @CsvSource({"aarch64,true", "arm64,true", "AARCH64,true", "x86_64,false", "amd64,false", "'',false"})
        @DisplayName("recognize arm64 from os.arch")
        void architecture(String arch, boolean arm64) {
            assertEquals(arm64, MacPlatform.isArm64(arch));
        }

        @Test
        @DisplayName("use objc_msgSend_stret only for structures above 16 bytes on x86_64")
        void structReturn() {
            assertTrue(MacPlatform.needsStructReturn(false, CmTime.LAYOUT));
            assertTrue(MacPlatform.needsStructReturn(false, NativeLayouts.CG_RECT));
            assertFalse(MacPlatform.needsStructReturn(false, NativeLayouts.CF_RANGE));
            assertFalse(MacPlatform.needsStructReturn(false, NativeLayouts.CM_VIDEO_DIMENSIONS));
            assertFalse(MacPlatform.needsStructReturn(false, JAVA_LONG));
            assertFalse(MacPlatform.needsStructReturn(false, null));
            assertFalse(MacPlatform.needsStructReturn(true, CmTime.LAYOUT));
            assertFalse(MacPlatform.needsStructReturn(true, NativeLayouts.CG_RECT));
        }
    }

    @Nested
    @DisplayName("upcall targets")
    class Upcalls {
        @Test
        @DisplayName("exist with the Java form of every C signature the bridge exports")
        void resolve() {
            assertTarget("captureOutput", ObjcBridge.CAPTURE_OUTPUT);
            assertTarget("streamOutput", ObjcBridge.STREAM_OUTPUT);
            assertTarget("streamStopped", ObjcBridge.STREAM_STOPPED);
            assertTarget("flagInvoked", ObjcBridge.FLAG_INVOKE);
            assertTarget("objectErrorInvoked", ObjcBridge.OBJECT_ERROR_INVOKE);
            assertTarget("errorInvoked", ObjcBridge.ERROR_INVOKE);
            assertTarget("drained", ObjcBridge.DISPATCH_FUNCTION);
        }

        @Test
        @DisplayName("take self, _cmd and the declared arguments for each delegate method")
        void arities() {
            assertEquals(TypeEncoding.size("@") * 5L, argumentBytes(ObjcBridge.CAPTURE_OUTPUT));
            assertEquals(JAVA_LONG, ObjcBridge.STREAM_OUTPUT.argumentLayouts().get(4));
            assertEquals(JAVA_BYTE, ObjcBridge.FLAG_INVOKE.argumentLayouts().get(1));
            assertEquals(2, ObjcBridge.ERROR_INVOKE.argumentLayouts().size());
        }

        private static long argumentBytes(FunctionDescriptor descriptor) {
            return descriptor.argumentLayouts().stream().mapToLong(MemoryLayout::byteSize).sum();
        }

        private static void assertTarget(String name, FunctionDescriptor descriptor) {
            var handle = ObjcBridge.target(name, descriptor);
            var expected = descriptor.toMethodType().insertParameterTypes(0, ObjcBridge.class);
            assertEquals(expected, handle.type());
            assertEquals(void.class, handle.type().returnType());
            for (var parameter : handle.type().dropParameterTypes(0, 1).parameterList()) {
                assertTrue(parameter == MemorySegment.class || parameter == long.class || parameter == byte.class,
                        () -> name + " takes " + parameter);
            }
        }
    }

    @Test
    @DisplayName("describe CGRect with double coordinates, CGFloat on 64-bit Darwin")
    void cgFloatIsDouble() {
        for (var member : ((GroupLayout) NativeLayouts.CG_RECT.memberLayouts().getFirst()).memberLayouts()) {
            assertEquals(JAVA_DOUBLE.withName(member.name().orElseThrow()), member);
        }
        assertEquals(MethodType.methodType(void.class, MemorySegment.class), ObjcBridge.DISPATCH_FUNCTION.toMethodType());
    }
}
