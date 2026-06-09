package dev.vfyjxf.gradle.launcher.core.minecraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vfyjxf.gradle.launcher.core.json.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VersionJson(
        String id,
        String type,
        String mainClass,
        String minecraftArguments,
        Downloads downloads,
        List<Library> libraries,
        Arguments arguments,
        AssetIndex assetIndex) {
    public VersionJson {
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
        arguments = arguments == null ? new Arguments(List.of(), List.of()) : arguments;
    }

    public static VersionJson read(Path path) throws IOException {
        return Json.mapper().readValue(path.toFile(), VersionJson.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Downloads(Artifact client, Artifact server) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssetIndex(String id, String sha1, Long size, Long totalSize, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Library(
            String name,
            LibraryDownloads downloads,
            List<Map<String, Object>> rules,
            Map<String, String> natives) {
        public Library {
            rules = copyMapList(rules);
            natives = natives == null ? Map.of() : Map.copyOf(natives);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LibraryDownloads(Artifact artifact, Map<String, Artifact> classifiers) {
        public LibraryDownloads {
            classifiers = classifiers == null ? Map.of() : Map.copyOf(classifiers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artifact(String path, String sha1, Long size, String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Arguments(List<Object> game, List<Object> jvm) {
        public Arguments {
            game = copyObjectList(game);
            jvm = copyObjectList(jvm);
        }
    }

    private static List<Map<String, Object>> copyMapList(List<? extends Map<String, Object>> maps) {
        if (maps == null || maps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copied = new ArrayList<>(maps.size());
        for (Map<String, Object> map : maps) {
            copied.add(copyStringObjectMap(map));
        }
        return List.copyOf(copied);
    }

    private static List<Object> copyObjectList(List<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<Object> copied = new ArrayList<>(values.size());
        for (Object value : values) {
            copied.add(deepImmutable(value));
        }
        return List.copyOf(copied);
    }

    private static Object deepImmutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyStringObjectMap(map);
        }
        if (value instanceof List<?> list) {
            return copyObjectList(list);
        }
        return value;
    }

    private static Map<String, Object> copyStringObjectMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                copied.put(key, deepImmutable(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(copied);
    }
}
