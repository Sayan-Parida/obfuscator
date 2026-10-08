package com.obfuscator.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * An immutable binary payload plus the metadata needed to verify that it was
 * not corrupted in transit or by a transformation.
 *
 * <p>Records compare byte arrays by identity, so two Artifacts holding equal
 * bytes are not {@code equals()}. Use {@link #sha256()} as the identity.
 */
public record Artifact(ArtifactFormat format, byte[] bytes, String sha256, int size) {

    public Artifact {
        bytes = bytes.clone();
    }

    public static Artifact of(ArtifactFormat format, byte[] bytes) {
        return new Artifact(format, bytes, sha256(bytes), bytes.length);
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean hasSameContentAs(Artifact other) {
        return Arrays.equals(bytes, other.bytes);
    }
}