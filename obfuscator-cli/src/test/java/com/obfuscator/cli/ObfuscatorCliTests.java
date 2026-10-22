package com.obfuscator.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.cli.CliTestSupport.Invocation;
import org.junit.jupiter.api.Test;

class ObfuscatorCliTests {

    @Test
    void printsHelpForTheRootCommand() {
        Invocation result = CliTestSupport.run("--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Obfuscates JVM class files", "obfuscate");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void printsTheVersionFromTheBuild() {
        Invocation result = CliTestSupport.run("--version");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).startsWith("obfuscator ");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void requiresASubcommand() {
        Invocation result = CliTestSupport.run();

        assertThat(result.exitCode()).isEqualTo(ExitCodes.USAGE);
        assertThat(result.err()).contains("missing required subcommand");
    }

    @Test
    void rejectsUnknownOptionsAsUsageErrors() {
        Invocation result = CliTestSupport.run(
                "obfuscate", "Some.class", "-o", "Out.class", "--not-an-option");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.USAGE);
        assertThat(result.err()).contains("Unknown option");
    }

    @Test
    void requiresTheOutputOption() {
        Invocation result = CliTestSupport.run("obfuscate", "Some.class");

        assertThat(result.exitCode()).isEqualTo(ExitCodes.USAGE);
        assertThat(result.err()).contains("--output");
    }
}
