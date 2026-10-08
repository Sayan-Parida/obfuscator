package com.obfuscator.engine.jvm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Caller-supplied names that must survive renaming.
 *
 * <p>This is the single escape hatch for names that must be preserved. Anything
 * resolved reflectively at runtime, or bound from outside the class such as JNI
 * or Java serialization, has to be listed here. There is deliberately no
 * built-in knowledge of specific frameworks or of {@code Serializable}: the
 * caller decides, and the response reports what was kept.
 *
 * <p>Entries are either a bare method name, matched in any owner, or
 * {@code com.example.Owner#method} to scope the rule to one class.
 */
public final class KeepNames {

    private final List<String> bare;
    private final List<Qualified> qualified;

    private KeepNames(List<String> bare, List<Qualified> qualified) {
        this.bare = List.copyOf(bare);
        this.qualified = List.copyOf(qualified);
    }

    public static KeepNames none() {
        return new KeepNames(List.of(), List.of());
    }

    /** Parses comma or newline separated entries, ignoring blank ones. */
    public static KeepNames parse(Collection<String> entries) {
        List<String> bare = new ArrayList<>();
        List<Qualified> qualified = new ArrayList<>();
        for (String entry : entries) {
            for (String token : entry.split("[,\\r\\n]")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('#');
                if (separator < 0) {
                    bare.add(trimmed);
                } else {
                    qualified.add(new Qualified(
                            internalName(trimmed.substring(0, separator).trim()),
                            trimmed.substring(separator + 1).trim()));
                }
            }
        }
        return new KeepNames(bare, qualified);
    }

    /** Parses a single option value, which may itself contain separators. */
    public static KeepNames parse(String optionValue) {
        return parse(List.of(optionValue == null ? "" : optionValue));
    }

    public boolean keeps(String owner, String methodName) {
        if (bare.contains(methodName)) {
            return true;
        }
        for (Qualified rule : qualified) {
            if (rule.name().equals(methodName) && rule.owner().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /** Callers may write the owner dotted, but ASM uses the internal name. */
    private static String internalName(String owner) {
        return owner.replace('.', '/');
    }

    public boolean isEmpty() {
        return bare.isEmpty() && qualified.isEmpty();
    }

    private record Qualified(String owner, String name) {
    }
}