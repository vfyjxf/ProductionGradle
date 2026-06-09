package dev.vfyjxf.gradle.launcher.core.launch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ImmutableLaunchData {
    private ImmutableLaunchData() {
    }

    static Map<String, Object> copyMap(Map<?, ?> value) {
        if (value == null) {
            return Map.of();
        }
        return copyAnyMap(value);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyAnyMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(ImmutableLaunchData::copyValue)
                    .toList();
        }
        return value;
    }

    private static Map<String, Object> copyAnyMap(Map<?, ?> value) {
        if (value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Launch metadata map keys must be strings");
            }
            copy.put(key, copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
