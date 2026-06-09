package dev.vfyjxf.gradle.launcher.core.auth;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public interface AuthProvider {
    AuthResult authenticate(AuthRequest request) throws IOException;

    static List<String> redactTokens(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }

        List<String> redacted = new ArrayList<>(arguments.size());
        boolean redactNext = false;
        for (String argument : arguments) {
            if (redactNext) {
                redacted.add("<redacted>");
                redactNext = false;
                continue;
            }
            if (isSecretFlag(argument)) {
                redacted.add(argument);
                redactNext = true;
                continue;
            }
            redacted.add(redactInlineToken(argument));
        }
        return List.copyOf(redacted);
    }

    private static boolean isSecretFlag(String argument) {
        String normalized = argument == null ? "" : argument.toLowerCase(Locale.ROOT);
        return normalized.equals("--accesstoken")
                || normalized.equals("--access-token")
                || normalized.equals("--access_token")
                || normalized.equals("--refreshtoken")
                || normalized.equals("--refresh-token")
                || normalized.equals("--refresh_token");
    }

    private static String redactInlineToken(String argument) {
        if (argument == null) {
            return null;
        }

        String normalized = argument.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("bearer ")) {
            return argument.substring(0, argument.indexOf(' ') + 1) + "<redacted>";
        }

        int equals = argument.indexOf('=');
        if (equals <= 0) {
            return argument;
        }

        String key = normalized.substring(0, equals);
        if (key.equals("token")
                || key.endsWith("token")
                || key.contains("access_token")
                || key.contains("refresh_token")
                || key.contains("access-token")
                || key.contains("refresh-token")) {
            return argument.substring(0, equals + 1) + "<redacted>";
        }
        return argument;
    }

    record AuthRequest(Path gradleUserHome, String userName, String cacheKey) {
        public AuthRequest {
            Objects.requireNonNull(gradleUserHome, "gradleUserHome");
        }
    }

    record AuthResult(
            AuthStatus status,
            List<String> gameArguments,
            String cacheKey) {
        public AuthResult {
            Objects.requireNonNull(status, "status");
            gameArguments = gameArguments == null ? List.of() : List.copyOf(gameArguments);
        }

        public static AuthResult authenticated(List<String> gameArguments, String cacheKey) {
            return new AuthResult(AuthStatus.AUTHENTICATED, gameArguments, cacheKey);
        }
    }

    enum AuthStatus {
        AUTHENTICATED
    }
}
