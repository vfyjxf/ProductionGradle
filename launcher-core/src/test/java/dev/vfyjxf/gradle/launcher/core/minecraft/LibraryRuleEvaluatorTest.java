package dev.vfyjxf.gradle.launcher.core.minecraft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryRuleEvaluatorTest {
    @Test
    void allowsLibrariesWithoutRules() {
        assertThat(new LibraryRuleEvaluator("linux", "x86_64").allowed(List.of())).isTrue();
    }

    @Test
    void rejectsAllowRuleForDifferentOs() {
        assertThat(new LibraryRuleEvaluator("linux", "x86_64")
                .allowed(List.of(Map.of("action", "allow", "os", Map.of("name", "windows"))))).isFalse();
    }

    @Test
    void acceptsAllowRuleForMatchingOs() {
        assertThat(new LibraryRuleEvaluator("windows", "x86_64")
                .allowed(List.of(Map.of("action", "allow", "os", Map.of("name", "windows"))))).isTrue();
    }

    @Test
    void appliesRulesInOrderWithLastMatchingRuleWinning() {
        assertThat(new LibraryRuleEvaluator("linux", "x86_64").allowed(List.of(
                Map.of("action", "allow"),
                Map.of("action", "disallow", "os", Map.of("name", "linux"))))).isFalse();
        assertThat(new LibraryRuleEvaluator("linux", "x86_64").allowed(List.of(
                Map.of("action", "disallow"),
                Map.of("action", "allow", "os", Map.of("name", "linux"))))).isTrue();
    }

    @Test
    void normalizesArchitectureNames() {
        assertThat(new LibraryRuleEvaluator("linux", "amd64")
                .allowed(List.of(Map.of("action", "allow", "os", Map.of("arch", "x86_64"))))).isTrue();
        assertThat(new LibraryRuleEvaluator("linux", "arm64")
                .allowed(List.of(Map.of("action", "allow", "os", Map.of("arch", "aarch64"))))).isTrue();
    }

    @Test
    void evaluatesOsVersionRegex() {
        assertThat(new LibraryRuleEvaluator("windows", "x86_64", "10.0")
                .allowed(List.of(Map.of(
                        "action", "allow",
                        "os", Map.of("name", "windows", "version", "^10\\."))))).isTrue();
        assertThat(new LibraryRuleEvaluator("windows", "x86_64", "11.0")
                .allowed(List.of(Map.of(
                        "action", "allow",
                        "os", Map.of("name", "windows", "version", "^10\\."))))).isFalse();
    }

    @Test
    void rejectsFeatureRulesWithoutFeatureContext() {
        assertThat(new LibraryRuleEvaluator("linux", "x86_64").allowed(List.of(Map.of(
                "action", "allow",
                "features", Map.of("is_demo_user", true))))).isFalse();
    }
}
