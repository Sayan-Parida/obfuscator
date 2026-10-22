package com.obfuscator.cli;

/** The input path exists but is not a format this tool can process. */
final class UnsupportedInputException extends RuntimeException {

    UnsupportedInputException(String message) {
        super(message);
    }
}
