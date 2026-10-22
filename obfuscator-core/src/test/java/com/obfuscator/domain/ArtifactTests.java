package com.obfuscator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArtifactTests {

    @Test
    void computesStableDigestForIdenticalBytes() {
        Artifact a = Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1, 2, 3});
        Artifact b = Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1, 2, 3});

        assertThat(a.sha256()).isEqualTo(b.sha256());
        assertThat(a.hasSameContentAs(b)).isTrue();
    }

    @Test
    void mutatingSourceArrayDoesNotAffectStoredBytes() {
        byte[] source = {1, 2, 3};
        Artifact artifact = Artifact.of(ArtifactFormat.JVM_CLASS, source);

        source[0] = 9;

        assertThat(artifact.bytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    void returnedByteArrayIsDefensivelyCopied() {
        Artifact artifact = Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1, 2, 3});

        artifact.bytes()[0] = 9;

        assertThat(artifact.bytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    void formatsDifferForDifferentBytes() {
        Artifact a = Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1});
        Artifact b = Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {2});

        assertThat(a.hasSameContentAs(b)).isFalse();
        assertThat(a.size()).isEqualTo(1);
    }

    @Test
    void emptyArtifactHasDigest() {
        assertThat(Artifact.of(ArtifactFormat.JAR, new byte[0]).sha256()).isNotBlank();
        assertThatThrownBy(() -> Artifact.of(ArtifactFormat.JAR, new byte[0]).hasSameContentAs(null))
                .isInstanceOf(NullPointerException.class);
    }
}