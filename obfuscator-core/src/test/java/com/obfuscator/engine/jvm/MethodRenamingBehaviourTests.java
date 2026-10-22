package com.obfuscator.engine.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import com.obfuscator.domain.Artifact;
import com.obfuscator.domain.ArtifactFormat;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Loads the original and the obfuscated class side by side and compares what
 * they actually compute. Renaming is only acceptable if this holds.
 */
class MethodRenamingBehaviourTests {

    private static final String SUBJECT = "com.example.Pricing";
    private static final String SOURCE = """
            package com.example;

            public class Pricing {

                public double total(double[] amounts, double rate) {
                    double sum = 0;
                    for (double amount : amounts) {
                        sum = sum + amount;
                    }
                    return applyDiscount(sum, rate);
                }

                public String describe(String plan) {
                    return decorate(plan) + ":" + applyDiscount(100.0, 0.5);
                }

                private double applyDiscount(double amount, double rate) {
                    return amount * (1.0 - rate);
                }

                private String decorate(String plan) {
                    return "[" + plan.trim().toUpperCase() + "]";
                }
            }
            """;

    private final MethodRenaming renaming = new MethodRenaming();

    @Test
    void obfuscatedClassComputesIdenticalResults() throws Exception {
        byte[] obfuscatedBytes = obfuscate(null);

        assertThat(TestClasses.methodNames(obfuscatedBytes))
                .doesNotContain("applyDiscount", "decorate");

        Object originalInstance = load(originalBytes()).getConstructor().newInstance();
        Object obfuscatedInstance = load(obfuscatedBytes).getConstructor().newInstance();

        double[] amounts = {19.99, 5.0, 42.5};

        assertThat(total(originalInstance, amounts, 0.15)).isEqualTo(total(obfuscatedInstance, amounts, 0.15));
        assertThat(describe(originalInstance, "  pro ")).isEqualTo(describe(obfuscatedInstance, "  pro "));
    }

    @Test
    void renamedPrivateMethodsAreReachableAndAgreeWithTheOriginals() throws Exception {
        Class<?> original = load(originalBytes());
        Class<?> obfuscated = load(obfuscate(null));

        Object originalInstance = original.getConstructor().newInstance();
        Object obfuscatedInstance = obfuscated.getConstructor().newInstance();

        Method originalDiscount = original.getDeclaredMethod("applyDiscount", double.class, double.class);
        originalDiscount.setAccessible(true);
        Method obfuscatedDiscount = privateMethodWithDescriptor(obfuscated, "(DD)D");

        assertThat(obfuscatedDiscount.getName()).isNotEqualTo("applyDiscount");
        assertThat((double) obfuscatedDiscount.invoke(obfuscatedInstance, 250.0, 0.25))
                .isEqualTo((double) originalDiscount.invoke(originalInstance, 250.0, 0.25));

        Method originalDecorate = original.getDeclaredMethod("decorate", String.class);
        originalDecorate.setAccessible(true);
        Method obfuscatedDecorate = privateMethodWithDescriptor(obfuscated, "(Ljava/lang/String;)Ljava/lang/String;");

        assertThat((String) obfuscatedDecorate.invoke(obfuscatedInstance, " go "))
                .isEqualTo((String) originalDecorate.invoke(originalInstance, " go "));
    }

@Test
    void keptMethodsStillMatchAfterRenaming() throws Exception {
        byte[] obfuscatedBytes = obfuscate("applyDiscount");

        assertThat(TestClasses.methodNames(obfuscatedBytes)).contains("applyDiscount");

        Class<?> original = load(originalBytes());
        Class<?> obfuscated = load(obfuscatedBytes);

        Object originalInstance = original.getConstructor().newInstance();
        Object obfuscatedInstance = obfuscated.getConstructor().newInstance();

        Method originalDiscount = original.getDeclaredMethod("applyDiscount", double.class, double.class);
        originalDiscount.setAccessible(true);
        Method obfuscatedDiscount = obfuscated.getDeclaredMethod("applyDiscount", double.class, double.class);
        obfuscatedDiscount.setAccessible(true);

        assertThat((double) obfuscatedDiscount.invoke(obfuscatedInstance, 80.0, 0.10))
                .isEqualTo((double) originalDiscount.invoke(originalInstance, 80.0, 0.10));
    }

    private double total(Object instance, double[] amounts, double rate) throws Exception {
        return (double) instance.getClass().getMethod("total", double[].class, double.class)
                .invoke(instance, (Object) amounts, rate);
    }

    private String describe(Object instance, String plan) throws Exception {
        return (String) instance.getClass().getMethod("describe", String.class).invoke(instance, plan);
    }

    private byte[] originalBytes() {
        return TestClasses.compile(SUBJECT, SOURCE);
    }

    private byte[] obfuscate(String keepOption) {
        return renaming.apply(
                        Artifact.of(ArtifactFormat.JVM_CLASS, originalBytes()),
                        KeepNames.parse(keepOption))
                .output()
                .bytes();
    }

    private static Class<?> load(byte[] bytes) throws ClassNotFoundException {
        return new TestClasses.SingleClassLoader(SUBJECT, bytes).loadClass(SUBJECT);
    }

    private static Method privateMethodWithDescriptor(Class<?> type, String descriptor) {
        List<Method> matches = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPrivate(method.getModifiers()))
                .filter(method -> descriptorOf(method).equals(descriptor))
                .toList();
        assertThat(matches).hasSize(1);
        Method found = matches.get(0);
        found.setAccessible(true);
        return found;
    }

    private static String descriptorOf(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(MethodRenamingBehaviourTests::descriptorOf)
                .collect(Collectors.joining());
        return "(" + parameters + ")" + descriptorOf(method.getReturnType());
    }

    private static String descriptorOf(Class<?> type) {
        if (type == void.class) {
            return "V";
        }
        if (type == double.class) {
            return "D";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type.isArray()) {
            return "[" + descriptorOf(type.getComponentType());
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }
}