package dev.vfyjxf.gradle.production.internal;

import dev.vfyjxf.gradle.launcher.core.json.Json;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpecWriter {
    private SpecWriter() {
    }

    public static void write(LaunchSpec spec, File outputFile) throws IOException {
        Path outputPath = outputFile.toPath();
        Files.createDirectories(outputPath.getParent());
        Json.mapper().writeValue(outputFile, spec);
    }
}
