package dev.vfyjxf.gradle.launcher.core.launch;

import dev.vfyjxf.gradle.launcher.core.auth.AuthProvider;
import java.util.Objects;

public record LaunchContext(LaunchSpec spec, boolean offline, AuthProvider.AuthResult authResult) {
    public LaunchContext {
        Objects.requireNonNull(spec, "spec");
    }

    public LaunchContext(LaunchSpec spec, boolean offline) {
        this(spec, offline, null);
    }

    public static LaunchContext directCli(LaunchSpec spec, boolean offlineOverride) {
        return new LaunchContext(spec, offlineOverride || specOffline(spec), null);
    }

    public LaunchContext withAuthResult(AuthProvider.AuthResult authResult) {
        return new LaunchContext(spec, offline, authResult);
    }

    private static boolean specOffline(LaunchSpec spec) {
        if (spec == null || spec.gradle() == null) {
            return false;
        }
        Object value = spec.gradle().get("offline");
        if (value instanceof Boolean offline) {
            return offline;
        }
        if (value instanceof String offline) {
            return Boolean.parseBoolean(offline);
        }
        return false;
    }
}
