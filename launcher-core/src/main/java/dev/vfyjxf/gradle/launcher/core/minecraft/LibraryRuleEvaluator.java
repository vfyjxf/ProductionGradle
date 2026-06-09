package dev.vfyjxf.gradle.launcher.core.minecraft;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class LibraryRuleEvaluator {
    private final String osName;
    private final String osArch;
    private final String osVersion;

    public LibraryRuleEvaluator(String osName, String osArch) {
        this(osName, osArch, "");
    }

    public LibraryRuleEvaluator(String osName, String osArch, String osVersion) {
        this.osName = normalizeOs(osName);
        this.osArch = normalizeArch(osArch);
        this.osVersion = osVersion == null ? "" : osVersion;
    }

    public static LibraryRuleEvaluator current() {
        return new LibraryRuleEvaluator(
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("os.version"));
    }

    public boolean allowed(List<Map<String, Object>> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }

        boolean allowed = false;
        for (Map<String, Object> rule : rules) {
            if (rule == null || !matches(rule)) {
                continue;
            }
            Object action = rule.get("action");
            if ("allow".equals(action)) {
                allowed = true;
            } else if ("disallow".equals(action)) {
                allowed = false;
            }
        }
        return allowed;
    }

    private boolean matches(Map<String, Object> rule) {
        Object features = rule.get("features");
        if (features instanceof Map<?, ?> featureMap && !featureMap.isEmpty()) {
            return false;
        }

        Object os = rule.get("os");
        if (!(os instanceof Map<?, ?> osRule)) {
            return true;
        }

        Object name = osRule.get("name");
        if (name instanceof String ruleOs && !normalizeOs(ruleOs).equals(osName)) {
            return false;
        }

        Object arch = osRule.get("arch");
        if (arch instanceof String ruleArch && !normalizeArch(ruleArch).equals(osArch)) {
            return false;
        }

        Object version = osRule.get("version");
        return !(version instanceof String versionPattern) || matchesVersion(versionPattern);
    }

    private boolean matchesVersion(String versionPattern) {
        if (osVersion.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(versionPattern).matcher(osVersion).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private static String normalizeOs(String value) {
        String normalized = lower(value);
        if (normalized.contains("win")) {
            return "windows";
        }
        if (normalized.contains("mac") || normalized.contains("darwin") || normalized.contains("osx")) {
            return "osx";
        }
        if (normalized.contains("linux")) {
            return "linux";
        }
        return normalized;
    }

    private static String normalizeArch(String value) {
        String normalized = lower(value);
        return switch (normalized) {
            case "amd64", "x86_64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "aarch64", "arm64" -> "aarch64";
            default -> normalized;
        };
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
