package com.obfuscator.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Reads the version stamped into the shaded JAR manifest. Running from classes
 * (tests, an IDE) has no manifest version, so it degrades to {@code dev}.
 */
public final class BuildVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = ObfuscatorCli.class.getPackage().getImplementationVersion();
        return new String[] {"obfuscator " + (version == null ? "dev" : version)};
    }
}
