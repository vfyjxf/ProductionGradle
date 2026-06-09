package dev.vfyjxf.gradle.launcher.core.loader.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vfyjxf.gradle.launcher.core.minecraft.VersionJson;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallerInstallProfile(
        String profile,
        String version,
        Map<String, Map<String, String>> data,
        List<Processor> processors,
        List<VersionJson.Library> libraries) {
    public InstallerInstallProfile {
        data = data == null ? Map.of() : Map.copyOf(data);
        processors = processors == null ? List.of() : List.copyOf(processors);
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
    }

    String dataValue(String key, String side) {
        Map<String, String> values = data.get(key);
        if (values == null) {
            return null;
        }
        return values.get(side);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Processor(List<String> sides, String jar, List<String> classpath, List<String> args) {
        public Processor {
            sides = sides == null ? List.of() : List.copyOf(sides);
            classpath = classpath == null ? List.of() : List.copyOf(classpath);
            args = args == null ? List.of() : List.copyOf(args);
        }

        boolean appliesTo(String side) {
            return sides.isEmpty() || sides.contains(side);
        }
    }
}
