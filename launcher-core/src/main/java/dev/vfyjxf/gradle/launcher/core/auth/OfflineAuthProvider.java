package dev.vfyjxf.gradle.launcher.core.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class OfflineAuthProvider implements AuthProvider {
    @Override
    public AuthResult authenticate(AuthRequest request) {
        Objects.requireNonNull(request, "request");
        String userName = Objects.requireNonNull(request.userName(), "userName");
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + userName).getBytes(StandardCharsets.UTF_8))
                .toString();

        return AuthResult.authenticated(List.of(
                "--username", userName,
                "--uuid", uuid,
                "--accessToken", "0",
                "--userType", "msa"), request.cacheKey());
    }
}
