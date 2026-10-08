package com.obfuscator.engine.jvm;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Generates short, collision-free identifiers. Letters only, so generated names
 * are always valid method names and never collide with names containing
 * compiler or JVM punctuation.
 *
 * <p>Sequence: a, b, ... z, aa, ab, ... zz, aaa.
 */
public final class NameAllocator {

    private static final int ALPHABET = 26;

    private final Set<String> reserved;
    private final Set<String> issued = new HashSet<>();
    private int index;

    public NameAllocator(Collection<String> reserved) {
        this.reserved = new HashSet<>(reserved);
    }

    /** Returns the next identifier that is neither reserved nor already issued. */
    public String next() {
        while (true) {
            String candidate = encode(index++);
            if (!reserved.contains(candidate) && issued.add(candidate)) {
                return candidate;
            }
        }
    }

    private static String encode(int index) {
        StringBuilder name = new StringBuilder();
        int remaining = index + 1;
        while (remaining > 0) {
            name.append((char) ('a' + (remaining - 1) % ALPHABET));
            remaining = (remaining - 1) / ALPHABET;
        }
        return name.reverse().toString();
    }
}