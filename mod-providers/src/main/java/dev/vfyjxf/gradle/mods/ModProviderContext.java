package dev.vfyjxf.gradle.mods;

import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ModProviderContext(
        String minecraftVersion,
        String loader,
        CacheLayout cacheLayout,
        boolean includeRequiredDependencies,
        boolean includeOptionalDependencies,
        Map<String, String> apiCredentials,
        boolean offline) {
    public static final String CURSEFORGE_API_KEY = "CURSEFORGE_API_KEY";
    public static final String PRODUCTION_CURSEFORGE_API_KEY = "production.curseforgeApiKey";

    public ModProviderContext {
        minecraftVersion = requireNotBlank(minecraftVersion, "minecraftVersion");
        loader = requireNotBlank(loader, "loader");
        Objects.requireNonNull(cacheLayout, "cacheLayout");
        apiCredentials = copyCredentials(apiCredentials);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String credential(String name) {
        return apiCredentials.get(name);
    }

    public String curseForgeApiKey() {
        String propertyValue = apiCredentials.get(PRODUCTION_CURSEFORGE_API_KEY);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }
        return apiCredentials.get(CURSEFORGE_API_KEY);
    }

    private static Map<String, String> copyCredentials(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        credentials.forEach((key, value) -> {
            if (key != null && value != null && !value.isBlank()) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    private static String requireNotBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private String minecraftVersion;
        private String loader;
        private CacheLayout cacheLayout;
        private boolean includeRequiredDependencies = true;
        private boolean includeOptionalDependencies;
        private final Map<String, String> apiCredentials = new LinkedHashMap<>();
        private boolean offline;

        private Builder() {
        }

        public Builder minecraftVersion(String minecraftVersion) {
            this.minecraftVersion = minecraftVersion;
            return this;
        }

        public Builder loader(String loader) {
            this.loader = loader;
            return this;
        }

        public Builder cacheLayout(CacheLayout cacheLayout) {
            this.cacheLayout = cacheLayout;
            return this;
        }

        public Builder includeRequiredDependencies(boolean includeRequiredDependencies) {
            this.includeRequiredDependencies = includeRequiredDependencies;
            return this;
        }

        public Builder includeOptionalDependencies(boolean includeOptionalDependencies) {
            this.includeOptionalDependencies = includeOptionalDependencies;
            return this;
        }

        public Builder apiCredentials(Map<String, String> apiCredentials) {
            this.apiCredentials.clear();
            if (apiCredentials != null) {
                apiCredentials.forEach(this::apiCredential);
            }
            return this;
        }

        public Builder apiCredential(String name, String value) {
            Objects.requireNonNull(name, "name");
            if (value == null || value.isBlank()) {
                apiCredentials.remove(name);
            } else {
                apiCredentials.put(name, value);
            }
            return this;
        }

        public Builder curseForgeApiKey(String apiKey) {
            return apiCredential(CURSEFORGE_API_KEY, apiKey);
        }

        public Builder offline(boolean offline) {
            this.offline = offline;
            return this;
        }

        public ModProviderContext build() {
            return new ModProviderContext(
                    minecraftVersion,
                    loader,
                    cacheLayout,
                    includeRequiredDependencies,
                    includeOptionalDependencies,
                    apiCredentials,
                    offline);
        }
    }
}
