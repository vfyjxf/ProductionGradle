package dev.vfyjxf.gradle.launcher.core.download;

import java.nio.file.Path;
import java.util.Objects;

public record DownloadResult(Path target, boolean downloaded) {
    public DownloadResult {
        Objects.requireNonNull(target, "target");
    }
}
