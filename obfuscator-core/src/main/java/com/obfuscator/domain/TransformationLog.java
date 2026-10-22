package com.obfuscator.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only record of what each transformation did, surfaced in the API
 * response so a caller can tell which passes actually ran.
 */
public final class TransformationLog {

    private final List<Entry> entries = new ArrayList<>();

    public void record(String stepId, String detail) {
        entries.add(new Entry(stepId, detail));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<String> stepIds() {
        return entries.stream().map(Entry::stepId).toList();
    }

    public record Entry(String stepId, String detail) {
    }
}