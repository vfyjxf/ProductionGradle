package dev.vfyjxf.gradle.production.internal;

public final class ConfigurationNames {
    private ConfigurationNames() {}

    public static String modClasspath(String runName) {
        return "production" + taskSuffix(runName) + "ModClasspath";
    }

    public static String projectModClasspath(String runName) {
        return "production" + taskSuffix(runName) + "ProjectModClasspath";
    }

    private static String taskSuffix(String name) {
        StringBuilder suffix = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!Character.isLetterOrDigit(character)) {
                capitalizeNext = true;
                continue;
            }
            if (suffix.length() == 0 || capitalizeNext) {
                suffix.append(Character.toUpperCase(character));
            } else {
                suffix.append(character);
            }
            capitalizeNext = false;
        }
        return suffix.toString();
    }
}
