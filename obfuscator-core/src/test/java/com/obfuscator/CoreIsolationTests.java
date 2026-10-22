package com.obfuscator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The core must stay usable from a plain {@code main}: no Spring on its
 * classpath. If a stereotype or Spring type creeps into this module, either the
 * build fails or this guard does.
 */
class CoreIsolationTests {

    @Test
    void springFrameworkIsNotOnTheCoreClasspath() {
        assertThatThrownBy(() -> Class.forName("org.springframework.context.ApplicationContext"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
