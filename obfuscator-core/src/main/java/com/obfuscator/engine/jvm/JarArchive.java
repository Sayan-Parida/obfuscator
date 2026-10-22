package com.obfuscator.engine.jvm;

import com.obfuscator.domain.ObfuscationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Rewrites a JAR archive entry by entry, leaving everything that is not a class
 * file byte-for-byte identical.
 *
 * <p>The transform is applied to each {@code .class} entry in isolation. A
 * class that cannot be transformed keeps its original bytes and is reported as
 * skipped, so one bad entry never corrupts the whole archive.
 *
 * <p>Signed JARs are rejected outright: rewriting any entry invalidates the
 * signature, which would produce an archive that fails verification at load
 * time. Duplicate entry names are rejected for the same reason.
 *
 * <p>Entry contents are read under a per-entry and a total uncompressed-size
 * cap, so a small, highly compressible archive cannot expand into unbounded
 * memory. The caps are enforced on the bytes actually inflated, never on the
 * size metadata in the ZIP headers, which a hostile archive can misstate.
 *
 * <p>Free of Spring.
 */
public final class JarArchive {

    /** Default uncompressed cap for a single entry (32 MiB). */
    public static final long DEFAULT_MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    /** Default uncompressed cap for the whole archive (256 MiB). */
    public static final long DEFAULT_MAX_EXPANDED_BYTES = 256L * 1024 * 1024;

    /**
     * Entry bytes are materialised in a {@code byte[]}, so caps must stay
     * representable as an array length. This also keeps the running totals far
     * from any {@code long} overflow.
     */
    private static final long MAX_MATERIALISED_BYTES = Integer.MAX_VALUE - 8L;

    private static final byte[] LOCAL_FILE_HEADER = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] END_OF_CENTRAL_DIRECTORY = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] ZIP64_END_OF_CENTRAL_DIRECTORY = {0x50, 0x4B, 0x06, 0x06};
    private static final int MAX_ARCHIVE_COMMENT = 65535;
    private static final int READ_BUFFER_BYTES = 8192;
    private static final Set<String> SIGNATURE_EXTENSIONS = Set.of(".sf", ".rsa", ".dsa", ".ec");

    private final long maxEntryBytes;
    private final long maxExpandedBytes;

    /** Uses the default expansion limits. */
    public JarArchive() {
        this(DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_EXPANDED_BYTES);
    }

    /**
     * @param maxEntryBytes    most bytes one entry may inflate to
     * @param maxExpandedBytes most bytes all entries together may inflate to
     * @throws IllegalArgumentException if a limit is not positive or is too
     *                                  large to be held in a Java array
     */
    public JarArchive(long maxEntryBytes, long maxExpandedBytes) {
        if (maxEntryBytes <= 0 || maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("archive expansion limits must be positive");
        }
        if (maxEntryBytes > MAX_MATERIALISED_BYTES || maxExpandedBytes > MAX_MATERIALISED_BYTES) {
            throw new IllegalArgumentException(
                    "archive expansion limits must not exceed " + MAX_MATERIALISED_BYTES + " bytes");
        }
        this.maxEntryBytes = maxEntryBytes;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    /** Rewrites one class entry.
     *
     * @param bytes   the entry content to write back
     * @param changed whether the transform actually modified the class
     * @param skipped whether the class could not be transformed at all
     * @param note    human-readable outcome, surfaced in the API response
     */
    @FunctionalInterface
    public interface ClassTransform {
        Outcome transform(String entryName, byte[] classBytes);
    }

    public record Outcome(byte[] bytes, boolean changed, boolean skipped, String note) {
    }

    public record EntryResult(String name, boolean classFile, boolean changed, boolean skipped, String note) {
    }

    public record Result(byte[] jarBytes, List<EntryResult> entries) {
    }

    /** Copies the archive unchanged; used to assert preservation. */
    public Result rewrite(byte[] jarBytes) {
        return rewrite(jarBytes, (name, bytes) -> new Outcome(bytes, false, false, "copied"));
    }

    public Result rewrite(byte[] jarBytes, ClassTransform transform) {
        requireReadableZip(jarBytes);
        List<SourceEntry> source = readEntries(jarBytes);
        rejectIfSigned(source);

        List<EntryResult> results = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream archive = new JarOutputStream(output)) {
            for (SourceEntry entry : source) {
                results.add(writeEntry(archive, entry, transform));
            }
        } catch (IOException e) {
            throw new ObfuscationException("could not write the obfuscated JAR: " + e.getMessage());
        }
        return new Result(output.toByteArray(), List.copyOf(results));
    }

    private EntryResult writeEntry(JarOutputStream archive, SourceEntry entry, ClassTransform transform)
            throws IOException {
        if (entry.name().endsWith("/")) {
            write(archive, entry, entry.bytes());
            return new EntryResult(entry.name(), false, false, false, "directory");
        }
        if (!entry.name().endsWith(".class")) {
            write(archive, entry, entry.bytes());
            return new EntryResult(entry.name(), false, false, false, "resource");
        }

        Outcome outcome = transformSafely(entry, transform);
        write(archive, entry, outcome.bytes());
        return new EntryResult(
                entry.name(), true, outcome.changed(), outcome.skipped(), outcome.note());
    }

    private Outcome transformSafely(SourceEntry entry, ClassTransform transform) {
        try {
            return transform.transform(entry.name(), entry.bytes());
        } catch (RuntimeException e) {
            return new Outcome(entry.bytes(), false, true, "skipped: " + e.getMessage());
        }
    }

    /**
     * Carries the entry metadata across. {@code JarOutputStream} requires the
     * size and CRC to be populated up front for {@code STORED} entries.
     */
    private void write(JarOutputStream archive, SourceEntry entry, byte[] bytes) throws IOException {
        ZipEntry out = new ZipEntry(entry.name());
        out.setTime(entry.time());
        if (entry.method() == ZipEntry.STORED) {
            out.setMethod(ZipEntry.STORED);
            out.setSize(bytes.length);
            out.setCompressedSize(bytes.length);
            out.setCrc(crc32(bytes));
        } else {
            out.setMethod(ZipEntry.DEFLATED);
        }
        archive.putNextEntry(out);
        archive.write(bytes);
        archive.closeEntry();
    }

    private List<SourceEntry> readEntries(byte[] jarBytes) {
        List<SourceEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long expanded = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!seen.add(entry.getName())) {
                    throw new ObfuscationException("duplicate entry in JAR: " + entry.getName());
                }
                byte[] bytes = readEntry(in, entry.getName(), expanded);
                expanded += bytes.length;
                entries.add(new SourceEntry(entry.getName(), bytes, entry.getTime(), entry.getMethod()));
            }
        } catch (ObfuscationException e) {
            throw e;
        } catch (IOException e) {
            throw new ObfuscationException("input is not a readable JAR: " + e.getMessage());
        }
        return entries;
    }

    /**
     * Reads one entry incrementally, failing as soon as either cap is crossed.
     * The advertised entry size is deliberately not used to size the buffer or
     * to decide when to stop: {@code getSize()} is -1 for streamed entries and
     * can be falsified by a crafted local header.
     */
    private byte[] readEntry(ZipInputStream in, String name, long alreadyExpanded) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(READ_BUFFER_BYTES);
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        long entryBytes = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            entryBytes += read;
            if (entryBytes > maxEntryBytes) {
                throw new ObfuscationException("archive entry exceeds the maximum uncompressed size of "
                        + maxEntryBytes + " bytes: " + name);
            }
            if (alreadyExpanded + entryBytes > maxExpandedBytes) {
                throw new ObfuscationException("archive expands beyond the maximum total uncompressed size of "
                        + maxExpandedBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * A ZIP signature alone does not mean the archive is intact. The central
     * directory is what makes an archive readable, so its absence means the
     * file was truncated and is rejected rather than silently mangled.
     */
    private static void requireReadableZip(byte[] jarBytes) {
        if (jarBytes.length < LOCAL_FILE_HEADER.length || !startsWithAt(jarBytes, 0, LOCAL_FILE_HEADER)) {
            throw new ObfuscationException("input is not a valid JAR file");
        }
        if (!hasEndOfCentralDirectory(jarBytes)) {
            throw new ObfuscationException("input is not a readable JAR: missing central directory");
        }
    }

    private static boolean hasEndOfCentralDirectory(byte[] data) {
        int searchStart = Math.max(0, data.length - 22 - MAX_ARCHIVE_COMMENT);
        for (int i = data.length - 22; i >= searchStart; i--) {
            if (startsWithAt(data, i, END_OF_CENTRAL_DIRECTORY)
                    || startsWithAt(data, i, ZIP64_END_OF_CENTRAL_DIRECTORY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAt(byte[] data, int offset, byte[] signature) {
        if (offset < 0 || offset + signature.length > data.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static void rejectIfSigned(List<SourceEntry> entries) {
        for (SourceEntry entry : entries) {
            if (isSignatureFile(entry.name())) {
                throw new ObfuscationException("signed JARs cannot be obfuscated: " + entry.name());
            }
        }
    }

    /** Signature blocks live under {@code META-INF} with a {@code .SF}/{@code .RSA} style suffix. */
    private static boolean isSignatureFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("meta-inf/")) {
            return false;
        }
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot >= 0 && SIGNATURE_EXTENSIONS.contains(base.substring(dot));
    }

    private static long crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private record SourceEntry(String name, byte[] bytes, long time, int method) {
    }
}