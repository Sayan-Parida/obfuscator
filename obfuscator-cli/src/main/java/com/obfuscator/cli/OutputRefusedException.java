package com.obfuscator.cli;

/** Writing the result was refused to avoid destroying the input or user data. */
final class OutputRefusedException extends RuntimeException {

    OutputRefusedException(String message) {
        super(message);
    }
}
