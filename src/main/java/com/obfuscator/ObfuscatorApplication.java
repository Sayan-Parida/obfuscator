package com.obfuscator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ObfuscatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObfuscatorApplication.class, args);
    }
}