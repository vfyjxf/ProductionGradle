package dev.vfyjxf.gradle.adapters;

import java.util.Map;
import java.util.Optional;
import org.gradle.api.Project;
import org.gradle.api.Task;

public final class DevelopmentArtifactResolver {
    public static final String FALLBACK_ARTIFACT_WARNING_KEY = "fallbackArtifactWarning";
    public static final String FALLBACK_ARTIFACT_WARNING =
            "Falling back to jar; verify it is a production-ready remapped or reobfuscated artifact.";

    public Optional<String> findTaskPath(Project project, String... taskNames) {
        for (String taskName : taskNames) {
            Task task = project.getTasks().findByName(taskName);
            if (task != null) {
                return Optional.of(task.getPath());
            }
        }
        return Optional.empty();
    }

    public Map<String, String> warningWhenJarFallback(Optional<String> taskPath) {
        if (taskPath.isPresent() && taskPath.get().endsWith(":jar")) {
            return Map.of(FALLBACK_ARTIFACT_WARNING_KEY, FALLBACK_ARTIFACT_WARNING);
        }
        return Map.of();
    }
}
