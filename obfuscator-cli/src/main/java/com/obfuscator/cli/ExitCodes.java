package com.obfuscator.cli;

/** Process exit codes. Stable, because scripts depend on them. */
public final class ExitCodes {

    /** The artifact was produced. */
    public static final int OK = 0;

    /** Input, obfuscation, verification or I/O failure; no output was written. */
    public static final int FAILURE = 1;

    /** Command-line usage error, reported by Picocli. */
    public static final int USAGE = 2;

    /** The input is not a JVM class file or JAR archive. */
    public static final int UNSUPPORTED_INPUT = 3;

    /** Writing was refused to protect the input or an existing file. */
    public static final int OUTPUT_REFUSED = 4;

    private ExitCodes() {
    }
}
