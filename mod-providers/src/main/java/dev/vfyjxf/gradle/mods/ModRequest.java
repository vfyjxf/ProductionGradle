package dev.vfyjxf.gradle.mods;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record ModRequest(
        Type type,
        String identifier,
        String version,
        Integer projectId,
        Integer fileId,
        Path file,
        Map<String, String> metadata) {
    public ModRequest {
        Objects.requireNonNull(type, "type");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ModRequest modrinthProject(String slugOrId) {
        return modrinthProject(slugOrId, null);
    }

    public static ModRequest modrinthProject(String slugOrId, String versionNumber) {
        return new ModRequest(Type.MODRINTH_PROJECT, requireNotBlank(slugOrId, "slugOrId"), versionNumber, null, null, null, Map.of());
    }

    public static ModRequest modrinthVersion(String versionId) {
        return new ModRequest(Type.MODRINTH_VERSION, requireNotBlank(versionId, "versionId"), null, null, null, null, Map.of());
    }

    public static ModRequest curseForgeFile(int projectId, int fileId) {
        if (projectId < 0 || fileId < 0) {
            throw new IllegalArgumentException("projectId and fileId must be non-negative");
        }
        return new ModRequest(Type.CURSEFORGE_FILE, Integer.toString(projectId), null, projectId, fileId, null, Map.of());
    }

    public static ModRequest gradleArtifact(Path file) {
        Objects.requireNonNull(file, "file");
        return gradleArtifact(file.getFileName().toString(), null, file, Map.of());
    }

    public static ModRequest gradleArtifact(String id, String version, Path file, Map<String, String> metadata) {
        Objects.requireNonNull(file, "file");
        return new ModRequest(Type.GRADLE_ARTIFACT, requireNotBlank(id, "id"), version, null, null, file, metadata);
    }

    private static String requireNotBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public enum Type {
        MODRINTH_PROJECT,
        MODRINTH_VERSION,
        CURSEFORGE_FILE,
        GRADLE_ARTIFACT
    }
}
