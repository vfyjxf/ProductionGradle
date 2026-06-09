package dev.vfyjxf.gradle.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExampleProjectConfigurationTest {
    @Test
    void neoforgeMdgExampleUsesDefaultIdeaApplicationMode() throws IOException {
        Path buildFile = repositoryRoot()
                .resolve("examples/neoforge-mdg-production/build.gradle");

        assertThat(Files.readString(buildFile))
                .doesNotContain("mode = \"gradle\"")
                .doesNotContain("mode = 'gradle'");
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.exists(directory.resolve("settings.gradle"))
                    && Files.exists(directory.resolve("examples/neoforge-mdg-production/build.gradle"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate repository root.");
    }
}
