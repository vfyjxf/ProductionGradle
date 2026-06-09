package dev.vfyjxf.gradle.launcher.core.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OfflineAuthProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void offlineAuthProducesStableUuidCompatibleIdAndRedactedCommandOutput() throws Exception {
        OfflineAuthProvider provider = new OfflineAuthProvider();
        Path gradleUserHome = tempDir.resolve("gradle-home");

        AuthProvider.AuthResult result = provider.authenticate(
                new AuthProvider.AuthRequest(gradleUserHome, "DevPlayer", null));
        AuthProvider.AuthResult repeated = provider.authenticate(
                new AuthProvider.AuthRequest(gradleUserHome, "DevPlayer", null));

        String expectedUuid = UUID.nameUUIDFromBytes("OfflinePlayer:DevPlayer".getBytes(StandardCharsets.UTF_8))
                .toString();

        assertThat(result.status()).isEqualTo(AuthProvider.AuthStatus.AUTHENTICATED);
        assertThat(result.gameArguments()).containsExactly(
                "--username", "DevPlayer",
                "--uuid", expectedUuid,
                "--accessToken", "0",
                "--userType", "msa");
        assertThat(repeated.gameArguments()).isEqualTo(result.gameArguments());
        assertThatCode(() -> UUID.fromString(valueAfter(result.gameArguments(), "--uuid")))
                .doesNotThrowAnyException();

        assertThat(AuthProvider.redactTokens(result.gameArguments())).containsExactly(
                "--username", "DevPlayer",
                "--uuid", expectedUuid,
                "--accessToken", "<redacted>",
                "--userType", "msa");
    }

    private static String valueAfter(List<String> arguments, String flag) {
        int index = arguments.indexOf(flag);
        assertThat(index).isGreaterThanOrEqualTo(0);
        assertThat(index + 1).isLessThan(arguments.size());
        return arguments.get(index + 1);
    }
}
