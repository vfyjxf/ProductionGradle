package dev.vfyjxf.gradle.launcher.core.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class Checksum {
    private static final HexFormat HEX = HexFormat.of();

    private Checksum() {
    }

    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1");
    }

    public static String sha512(Path file) throws IOException {
        return digest(file, "SHA-512");
    }

    public static boolean matches(Path file, String algorithm, String expected) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(expected, "expected");
        return digest(file, normalizeAlgorithm(algorithm)).equalsIgnoreCase(expected.trim());
    }

    private static String digest(Path file, String algorithm) throws IOException {
        Objects.requireNonNull(file, "file");
        MessageDigest digest = messageDigest(algorithm);
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(normalizeAlgorithm(algorithm));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Unsupported checksum algorithm: " + algorithm, exception);
        }
    }

    private static String normalizeAlgorithm(String algorithm) {
        String normalized = algorithm.trim().toUpperCase(Locale.ROOT).replace("_", "-");
        return switch (normalized.replace("-", "")) {
            case "SHA1" -> "SHA-1";
            case "SHA512" -> "SHA-512";
            default -> normalized;
        };
    }
}
