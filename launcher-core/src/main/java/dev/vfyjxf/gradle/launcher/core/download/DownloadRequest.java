package dev.vfyjxf.gradle.launcher.core.download;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public record DownloadRequest(
        URI uri,
        Path target,
        String checksumAlgorithm,
        String checksum,
        boolean offline) {
    public DownloadRequest {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(target, "target");
        checksumAlgorithm = blankToNull(checksumAlgorithm);
        checksum = blankToNull(checksum);
        if (checksum != null && checksumAlgorithm == null) {
            throw new IllegalArgumentException("checksumAlgorithm is required when checksum is provided");
        }
    }

    public boolean hasChecksum() {
        return checksum != null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
