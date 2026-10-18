package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds the chunks a {@link StreamMediaSource} keeps beyond its memory budget, in an encrypted
 * temporary file that only the current user can open and that does not outlive the process.
 *
 * <p>The file is divided into slots of one chunk each. A chunk is written once, encrypted with
 * AES in counter mode under a key and a nonce drawn at random for this file and kept only in
 * memory, so a file left on disk or recovered from the free blocks of the disk cannot be read.
 * The counter of every block is derived from the chunk's position in the stream, which is written
 * at most once, so no key stream is ever reused even when a slot is. Reads are positional and safe
 * for concurrent calls with each other and with writes to other slots.
 *
 * <p>The file gets a random name and owner-only permissions as it is created: {@code rw-------}
 * on a POSIX file system, an access-control list granting only its owner on Windows. On POSIX the
 * name is unlinked right after the file is opened, so the file is gone from the directory while it
 * is still in use; on Windows it is opened with {@link StandardOpenOption#DELETE_ON_CLOSE}, which
 * the operating system honors when the process ends in any way.
 */
final class SpillFile implements AutoCloseable {
    /**
     * The cipher transformation: AES in counter mode, which encrypts any length in place.
     */
    private static final String TRANSFORMATION = "AES/CTR/NoPadding";

    /**
     * The AES block size, the unit of the counter.
     */
    private static final int BLOCK = 16;

    /**
     * The source of names, keys and nonces.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The permissions of a spill file on a POSIX file system.
     */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /**
     * The owner of the files this process creates on Windows, found once with an empty probe
     * file, or {@code null} until then.
     */
    private static volatile UserPrincipal windowsOwner;

    /**
     * The file channel; the only handle on the file.
     */
    private final FileChannel channel;

    /**
     * The path the file was created at; the name no longer exists on POSIX file systems.
     */
    private final Path path;

    /**
     * The size of one slot in bytes, a multiple of {@link #BLOCK}.
     */
    private final int slotSize;

    /**
     * The key of this file.
     */
    private final SecretKeySpec key;

    /**
     * The nonce of this file: the high eight bytes of every counter block.
     */
    private final byte[] nonce;

    /**
     * The ciphers not in use, reused across reads and writes.
     */
    private final ConcurrentLinkedQueue<Cipher> ciphers;

    /**
     * The buffer a chunk is encrypted into before it is written.
     */
    private final byte[] scratch;

    /**
     * Constructs a spill file over an open channel and draws its key and nonce.
     *
     * @param channel  the open channel
     * @param path     the path the file was created at
     * @param slotSize the size of one slot in bytes
     */
    private SpillFile(FileChannel channel, Path path, int slotSize) {
        this.channel = channel;
        this.path = path;
        this.slotSize = slotSize;
        var keyBytes = new byte[32];
        RANDOM.nextBytes(keyBytes);
        this.key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        this.nonce = new byte[8];
        RANDOM.nextBytes(nonce);
        this.ciphers = new ConcurrentLinkedQueue<>();
        this.scratch = new byte[slotSize];
    }

    /**
     * Creates a spill file in a directory.
     *
     * @param directory the directory the file is created in
     * @param slotSize  the size of one slot in bytes; a positive multiple of 16
     * @return the spill file
     * @throws MediaException.Io if the file cannot be created or protected
     */
    static SpillFile create(Path directory, int slotSize) {
        return create(directory, slotSize, true);
    }

    /**
     * Creates a spill file in a directory, optionally keeping its name on a POSIX file system so
     * that a test can inspect its permissions and content.
     *
     * @param directory the directory the file is created in
     * @param slotSize  the size of one slot in bytes; a positive multiple of 16
     * @param unlink    whether the name is removed right after the file is opened on a POSIX file
     *                  system; ignored on Windows, where the file is deleted on close
     * @return the spill file
     * @throws MediaException.Io if the file cannot be created or protected
     */
    static SpillFile create(Path directory, int slotSize, boolean unlink) {
        var path = directory.resolve("smalt-" + HexFormat.of().formatHex(randomBytes(16)) + ".spill");
        var views = FileSystems.getDefault().supportedFileAttributeViews();
        FileChannel channel = null;
        try {
            if (views.contains("posix")) {
                channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE), PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                if (unlink) {
                    Files.delete(path);
                }
            } else if (views.contains("acl")) {
                channel = openOwnerOnlyAcl(directory, path);
            } else {
                throw new IOException("the file system of " + directory + " supports neither POSIX permissions nor"
                        + " access-control lists, so a spill file could not be protected");
            }
            return new SpillFile(channel, path, slotSize);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException deleteFailure) {
                exception.addSuppressed(deleteFailure);
            }
            throw switch (exception) {
                case IOException io -> new MediaException.Io("cannot create a spill file in " + directory, io);
                case RuntimeException runtime -> runtime;
                default -> new IllegalStateException(exception);
            };
        }
    }

    /**
     * Creates a file that is deleted when its handle closes, with an access-control list that
     * grants only the owner, set atomically as the file is created.
     *
     * @param directory the directory of the file
     * @param path      the path of the file
     * @return the open channel
     * @throws IOException if the file cannot be created
     */
    private static FileChannel openOwnerOnlyAcl(Path directory, Path path) throws IOException {
        var owner = windowsOwner(directory);
        var entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        var acl = new FileAttribute<List<AclEntry>>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return List.of(entry);
            }
        };
        var channel = FileChannel.open(path, Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE), acl);
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view != null && !view.getAcl().equals(List.of(entry))) {
            view.setAcl(List.of(entry));
        }
        return channel;
    }

    /**
     * Returns the owner of the files this process creates, found once from an empty file that is
     * deleted on close.
     *
     * @param directory the directory the probe file is created in
     * @return the owner
     * @throws IOException if the probe file cannot be created
     */
    private static UserPrincipal windowsOwner(Path directory) throws IOException {
        var owner = windowsOwner;
        if (owner == null) {
            var probe = directory.resolve("smalt-" + HexFormat.of().formatHex(randomBytes(16)) + ".probe");
            try (var _ = FileChannel.open(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE)) {
                owner = Files.getOwner(probe);
            }
            windowsOwner = owner;
        }
        return owner;
    }

    /**
     * Returns fresh random bytes.
     *
     * @param length the number of bytes
     * @return the bytes
     */
    private static byte[] randomBytes(int length) {
        var bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * Returns the path the file was created at; on a POSIX file system the name is normally
     * already unlinked.
     *
     * @return the path
     */
    Path path() {
        return path;
    }

    /**
     * Encrypts a chunk and writes it to a slot.
     *
     * <p>The chunk is encrypted into a buffer of this file, so {@code data} is left unchanged and
     * may be read concurrently. Writes must not run concurrently with each other.
     *
     * @param slot   the slot index
     * @param chunk  the index of the chunk in the stream, from which the counter is derived
     * @param data   the chunk bytes
     * @param length the number of bytes to write, at most the slot size
     * @throws MediaException.Io if the write fails
     */
    void write(long slot, long chunk, byte[] data, int length) {
        crypt(chunk, 0, data, 0, scratch, length);
        var buffer = ByteBuffer.wrap(scratch, 0, length);
        var position = slot * slotSize;
        try {
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot write the spill file", exception);
        }
    }

    /**
     * Reads and decrypts part of a chunk.
     *
     * @param slot   the slot index the chunk was written to
     * @param chunk  the index of the chunk in the stream
     * @param within the offset of the first byte in the chunk
     * @param target the destination array
     * @param offset the index in {@code target} of the first byte written
     * @param length the number of bytes to read; the range lies inside the written chunk
     * @throws MediaException.Io if the read fails or the file is shorter than the slot
     */
    void read(long slot, long chunk, int within, byte[] target, int offset, int length) {
        var buffer = ByteBuffer.wrap(target, offset, length);
        var position = slot * slotSize + within;
        try {
            while (buffer.hasRemaining()) {
                var read = channel.read(buffer, position);
                if (read < 0) {
                    throw new IOException("the spill file ends inside slot " + slot);
                }
                position += read;
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot read the spill file", exception);
        }
        crypt(chunk, within, target, offset, target, length);
    }

    /**
     * Applies the key stream of a chunk range to bytes, which both encrypts and decrypts in
     * counter mode.
     *
     * @param chunk  the index of the chunk in the stream
     * @param within the offset of the first byte in the chunk
     * @param data   the input bytes
     * @param offset the index of the first input byte, and of the first output byte when
     *               {@code output} is {@code data}; output to another array starts at index zero
     * @param output the array that receives the result, which may be {@code data}
     * @param length the number of bytes
     */
    private void crypt(long chunk, int within, byte[] data, int offset, byte[] output, int length) {
        var cipher = ciphers.poll();
        try {
            if (cipher == null) {
                cipher = Cipher.getInstance(TRANSFORMATION);
            }
            var block = chunk * (slotSize / BLOCK) + within / BLOCK;
            var iv = new byte[BLOCK];
            System.arraycopy(nonce, 0, iv, 0, nonce.length);
            for (var index = 0; index < 8; index++) {
                iv[BLOCK - 1 - index] = (byte) (block >>> (8 * index));
            }
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            var skip = within % BLOCK;
            if (skip > 0) {
                cipher.update(new byte[skip]);
            }
            cipher.update(data, offset, length, output, output == data ? offset : 0);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES/CTR is unavailable", exception);
        }
        ciphers.offer(cipher);
    }

    /**
     * Closes the file, which deletes it on Windows; on POSIX its name is already gone.
     *
     * @throws MediaException.Io if closing or deleting fails
     */
    @Override
    public void close() {
        try {
            channel.close();
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot close the spill file", exception);
        }
    }
}
