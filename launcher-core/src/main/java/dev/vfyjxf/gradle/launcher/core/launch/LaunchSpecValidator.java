package dev.vfyjxf.gradle.launcher.core.launch;

import java.util.Set;

public class LaunchSpecValidator {
    private static final Set<String> TYPES = Set.of("client", "server");
    private static final Set<String> LOADERS = Set.of("vanilla", "fabric", "forge", "neoforge");

    public void validate(LaunchSpec spec) {
        require(spec != null, "launch spec is required");
        require(spec.schemaVersion() == 1, "schemaVersion must be 1");
        require(text(spec.runName()), "runName is required");
        require(text(spec.type()), "type is required");
        require(TYPES.contains(spec.type()), "type must be client or server");
        require(text(spec.minecraftVersion()), "minecraftVersion is required");
        require(text(spec.loader()), "loader is required");
        require(LOADERS.contains(spec.loader()), "loader must be vanilla, fabric, forge, or neoforge");
        require(text(spec.loaderVersion()) || "vanilla".equals(spec.loader()), "loaderVersion is required for non-vanilla runs");
        require(spec.paths() != null, "paths are required");
        require(spec.auth() != null, "auth is required");
        require(spec.launch() != null, "launch is required");
        require(!"microsoft".equals(spec.auth().mode()) || "client".equals(spec.type()),
                "microsoft auth is only supported for client runs");
        if ("server".equals(spec.type())) {
            require(spec.launch().eulaAccepted(), "server run requires eula to be accepted");
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new LaunchValidationException(message);
        }
    }
}
