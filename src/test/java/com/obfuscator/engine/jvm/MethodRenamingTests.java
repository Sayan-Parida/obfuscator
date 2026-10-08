package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import com.obfuscator.domain.ObfuscationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MethodRenamingTests {

    private static final String SAMPLE = "com.example.Sample";
    private static final String SAMPLE_SOURCE = """
            package com.example;

            public class Sample {

                private int counter;

                public String buildWelcomeMessage(String locale) {
                    return applyGreeting("Welcome, ") + locale;
                }

                public int getCounter() {
                    return counter;
                }

                private String applyGreeting(String prefix) {
                    return prefix + "customer";
                }

                private int bumpCounter(int amount) {
                    counter = counter + amount;
                    return counter;
                }

                public int total(int[] values) {
                    int sum = 0;
                    for (int value : values) {
                        sum = sum + value;
                    }
                    return sum;
                }
            }
            """;

    private final MethodRenaming renaming = new MethodRenaming();

    @Test
    void renamesPrivateMethodsAndProvesItChanged() {
        List<String> names = TestClasses.methodNames(run(SAMPLE, SAMPLE_SOURCE, null));

        assertThat(names).doesNotContain("applyGreeting", "bumpCounter");
        assertThat(names).containsExactlyInAnyOrder("<init>", "a", "b",
                "buildWelcomeMessage", "getCounter", "total");
    }

    @Test
    void reportsHowManyMethodsItRenamed() {
        MethodRenaming.RenamingResult result = result(SAMPLE, SAMPLE_SOURCE, null);

        assertThat(result.renamedCount()).isEqualTo(2);
        assertThat(result.mapping()).hasSize(2);
        assertThat(result.keptMethodNames())
                .contains("buildWelcomeMessage", "getCounter", "total", "<init>");
    }

    @Test
    void leavesNonPrivateMethodsAlone() {
        assertThat(TestClasses.methodNames(run(SAMPLE, SAMPLE_SOURCE, null)))
                .contains("buildWelcomeMessage", "getCounter", "total", "<init>");
    }

    @Test
    void neverRenamesTheClass() {
        byte[] original = TestClasses.compile(SAMPLE, SAMPLE_SOURCE);

        assertThat(TestClasses.className(run(SAMPLE, SAMPLE_SOURCE, null)))
                .isEqualTo(TestClasses.className(original));
    }

    @Test
    void leavesInterfaceMethodsAlone() {
        String name = "com.example.Greeter";
        String source = """
                package com.example;

                public interface Greeter {
                    String greet(String name);
                }
                """;

        assertThat(TestClasses.methodNames(run(name, source, null)))
                .containsExactlyInAnyOrder("greet");
    }

    @Test
    void leavesCompilerGeneratedLambdaMethodsAlone() throws Exception {
        String name = "com.example.WithLambda";
        String source = """
                package com.example;

                import java.util.function.Function;

                public class WithLambda {
                    public Function<String, Integer> length() {
                        return text -> text.length();
                    }

                    private int helper(int value) {
                        return value + 1;
                    }
                }
                """;

        List<String> names = TestClasses.methodNames(run(name, source, null));
        assertThat(names).anyMatch(methodName -> methodName.startsWith("lambda$"));
        assertThat(names).contains("a");
        assertThat(names).doesNotContain("helper");
    }

    @Test
    void honoursTheKeepList() {
        List<String> names = TestClasses.methodNames(run(SAMPLE, SAMPLE_SOURCE, "applyGreeting"));

        assertThat(names).contains("applyGreeting");
        assertThat(names).doesNotContain("bumpCounter");
    }

    @Test
    void honoursAQualifiedKeepListEntry() {
        List<String> names = TestClasses.methodNames(
                run(SAMPLE, SAMPLE_SOURCE, "com.example.Sample#applyGreeting"));

        assertThat(names).contains("applyGreeting");
    }

    @Test
    void qualifiedKeepListEntryDoesNotLeakToOtherOwners() {
        KeepNames keep = KeepNames.parse("com.example.Other#applyGreeting");

        assertThat(keep.keeps("com/example/Sample", "applyGreeting")).isFalse();
    }

    @Test
    void avoidCollidingWithExistingMethodNames() {
        String name = "com.example.Crowded";
        String source = """
                package com.example;

                public class Crowded {
                    public void a() {
                    }

                    public void b() {
                    }

                    private void somethingMeaningful() {
                    }
                }
                """;

        List<String> names = TestClasses.methodNames(run(name, source, null));
        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).contains("c");
        assertThat(names).contains("a", "b");
    }

    @Test
    void keepsSyntheticBridgeMethodsAlone() {
        String childName = "com.example.Child";
        String parent = """
                package com.example;

                public class Parent<T> {
                    public T get() {
                        return null;
                    }
                }
                """;
        String child = """
                package com.example;

                public class Child extends Parent<String> {
                    @Override
                    public String get() {
                        return "value";
                    }

                    private String internalHelper() {
                        return "helper";
                    }
                }
                """;

        byte[] obfuscated = TestClasses.compile(childName, child, parent);
        List<String> names = TestClasses.methodNames(renaming.apply(
                        Artifact.of(ArtifactFormat.JVM_CLASS, obfuscated), KeepNames.none())
                .output()
                .bytes());

        assertThat(names).contains("get");
        assertThat(names).contains("a");
        assertThat(names).doesNotContain("internalHelper");
    }

    @Test
    void rejectsInputThatIsNotAClassFile() {
        Artifact notAClass = Artifact.of(ArtifactFormat.JVM_CLASS, new byte[] {1, 2, 3});

        assertThatThrownBy(() -> renaming.apply(notAClass, KeepNames.none()))
                .isInstanceOf(RuntimeException.class);
    }

    private MethodRenaming.RenamingResult result(String name, String source, String keepOption) {
        return renaming.apply(
                Artifact.of(ArtifactFormat.JVM_CLASS, TestClasses.compile(name, source)),
                KeepNames.parse(keepOption));
    }

    private byte[] run(String name, String source, String keepOption) {
        return result(name, source, keepOption).output().bytes();
    }
}