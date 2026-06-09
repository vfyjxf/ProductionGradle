package dev.vfyjxf.gradle.integration;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationBuildSmokeTest {
    @Test
    void gradleTestKitIsAvailable() {
        assertThat(GradleRunner.create()).isNotNull();
    }
}
