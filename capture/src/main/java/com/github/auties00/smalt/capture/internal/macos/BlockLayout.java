package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.util.Objects;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Lays out the Objective-C block literals this backend passes as completion handlers.
 *
 * <p>A block literal is the object behind an Objective-C {@code ^(...)} value. It starts with an
 * {@code isa} pointer, a flags word, a reserved word, the invoke function and a pointer to a
 * descriptor, followed by the captured variables. The literals built here capture exactly one
 * 64-bit word, the key under which the Java completion waiting for the call is registered, so a
 * single invoke function per block signature serves every literal of that signature:
 * {@snippet :
 * struct Block_literal {            // 40 bytes
 *     void *isa;                    //  0: &_NSConcreteStackBlock
 *     int32_t flags;                //  8: BLOCK_HAS_SIGNATURE
 *     int32_t reserved;             // 12: 0
 *     void (*invoke)(void *, ...);  // 16: the upcall stub of the signature
 *     struct Block_descriptor *descriptor; // 24
 *     int64_t key;                  // 32: the captured completion key
 * };
 * struct Block_descriptor {         // 32 bytes
 *     uintptr_t reserved;           //  0: 0, no in-descriptor flags
 *     uintptr_t size;               //  8: sizeof(struct Block_literal)
 *     const char *signature;        // 16: the block type encoding
 *     const char *layout;           // 24: NULL
 * };
 * }
 * The invoke function receives the literal (or the heap copy the callee made of it) as its first
 * argument and reads the key with {@link #key(MemorySegment)}.
 *
 * @implNote This implementation follows the Apple block ABI documented by Clang
 *           ({@code Block-ABI-Apple}) and implemented by libclosure ({@code Block_private.h},
 *           {@code runtime.cpp}). A literal is a stack block whose flags carry only
 *           {@link #HAS_SIGNATURE}: {@code _Block_copy} copies it with a plain {@code memmove} of
 *           the descriptor's size and runs no copy or dispose helper, because
 *           {@code BLOCK_HAS_COPY_DISPOSE} is clear and the descriptor's reserved word is zero,
 *           which libclosure reads as "no in-descriptor flags". The heap copy keeps pointing at
 *           the descriptor, so descriptors must outlive every literal that uses them.
 */
final class BlockLayout {
    /**
     * The {@code BLOCK_HAS_SIGNATURE} flag: the descriptor carries a type encoding.
     */
    static final int HAS_SIGNATURE = 1 << 30;

    /**
     * The layout of a block literal with its single captured key.
     */
    static final StructLayout LITERAL = MemoryLayout.structLayout(
            ADDRESS.withName("isa"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("reserved"),
            ADDRESS.withName("invoke"),
            ADDRESS.withName("descriptor"),
            JAVA_LONG.withName("key"));

    /**
     * The layout of a block descriptor with a signature and no copy or dispose helper.
     */
    static final StructLayout DESCRIPTOR = MemoryLayout.structLayout(
            JAVA_LONG.withName("reserved"),
            JAVA_LONG.withName("size"),
            ADDRESS.withName("signature"),
            ADDRESS.withName("layout"));

    /**
     * The offset of the {@code isa} pointer in a literal.
     */
    static final long ISA_OFFSET = LITERAL.byteOffset(groupElement("isa"));

    /**
     * The offset of the flags word in a literal.
     */
    static final long FLAGS_OFFSET = LITERAL.byteOffset(groupElement("flags"));

    /**
     * The offset of the reserved word in a literal.
     */
    static final long RESERVED_OFFSET = LITERAL.byteOffset(groupElement("reserved"));

    /**
     * The offset of the invoke function pointer in a literal.
     */
    static final long INVOKE_OFFSET = LITERAL.byteOffset(groupElement("invoke"));

    /**
     * The offset of the descriptor pointer in a literal.
     */
    static final long DESCRIPTOR_OFFSET = LITERAL.byteOffset(groupElement("descriptor"));

    /**
     * The offset of the captured key in a literal.
     */
    static final long KEY_OFFSET = LITERAL.byteOffset(groupElement("key"));

    /**
     * The offset of the reserved word in a descriptor.
     */
    static final long DESCRIPTOR_RESERVED_OFFSET = DESCRIPTOR.byteOffset(groupElement("reserved"));

    /**
     * The offset of the literal size in a descriptor.
     */
    static final long DESCRIPTOR_SIZE_OFFSET = DESCRIPTOR.byteOffset(groupElement("size"));

    /**
     * The offset of the signature pointer in a descriptor.
     */
    static final long DESCRIPTOR_SIGNATURE_OFFSET = DESCRIPTOR.byteOffset(groupElement("signature"));

    /**
     * The offset of the layout string pointer in a descriptor.
     */
    static final long DESCRIPTOR_LAYOUT_OFFSET = DESCRIPTOR.byteOffset(groupElement("layout"));

    /**
     * Prevents instantiation.
     */
    private BlockLayout() {
        throw new AssertionError();
    }

    /**
     * Allocates a descriptor for literals of one block signature.
     *
     * <p>The descriptor and its signature string are allocated from {@code allocator}, which must
     * keep them alive for as long as any literal, or any copy the Objective-C runtime made of one,
     * may still exist.
     *
     * @param allocator the allocator of the descriptor and the signature string
     * @param signature the block type encoding, for example {@code "v16@?0@8"}
     * @return the descriptor
     * @throws NullPointerException if an argument is {@code null}
     */
    static MemorySegment descriptor(SegmentAllocator allocator, String signature) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(signature, "signature");
        var descriptor = allocator.allocate(DESCRIPTOR);
        var text = allocator.allocateFrom(signature);
        descriptor.set(JAVA_LONG, DESCRIPTOR_RESERVED_OFFSET, 0L);
        descriptor.set(JAVA_LONG, DESCRIPTOR_SIZE_OFFSET, LITERAL.byteSize());
        descriptor.set(ADDRESS, DESCRIPTOR_SIGNATURE_OFFSET, text);
        descriptor.set(ADDRESS, DESCRIPTOR_LAYOUT_OFFSET, MemorySegment.NULL);
        return descriptor;
    }

    /**
     * Allocates and fills a stack block literal.
     *
     * <p>The literal may be freed once the Objective-C call it is passed to returns: a callee that
     * keeps the block copies it to the heap with {@code _Block_copy} before returning.
     *
     * @param allocator  the allocator of the literal
     * @param isa        the address of {@code _NSConcreteStackBlock}
     * @param invoke     the invoke function, an upcall stub taking the literal first
     * @param descriptor the descriptor from {@link #descriptor(SegmentAllocator, String)}
     * @param key        the key of the completion to resolve when the block is invoked
     * @return the literal
     * @throws NullPointerException if an argument is {@code null}
     */
    static MemorySegment literal(SegmentAllocator allocator, MemorySegment isa, MemorySegment invoke,
                                 MemorySegment descriptor, long key) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(isa, "isa");
        Objects.requireNonNull(invoke, "invoke");
        Objects.requireNonNull(descriptor, "descriptor");
        var literal = allocator.allocate(LITERAL);
        literal.set(ADDRESS, ISA_OFFSET, isa);
        literal.set(JAVA_INT, FLAGS_OFFSET, HAS_SIGNATURE);
        literal.set(JAVA_INT, RESERVED_OFFSET, 0);
        literal.set(ADDRESS, INVOKE_OFFSET, invoke);
        literal.set(ADDRESS, DESCRIPTOR_OFFSET, descriptor);
        literal.set(JAVA_LONG, KEY_OFFSET, key);
        return literal;
    }

    /**
     * Reads the captured completion key of a literal.
     *
     * @param literal the literal, at least {@link #LITERAL}'s size long
     * @return the key
     * @throws IndexOutOfBoundsException if {@code literal} is too short
     */
    static long key(MemorySegment literal) {
        return literal.get(JAVA_LONG, KEY_OFFSET);
    }
}
