package dev.vfyjxf.gradle.adapters.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;

public final class GradleModelReflection {
    private static final int FULL_MINECRAFT_VERSION_MAJOR = 26;
    private static final Pattern LEADING_NUMBER_PATTERN = Pattern.compile("^(\\d+).*$");

    private GradleModelReflection() {}

    public static boolean hasPlugin(Project project, String... pluginIds) {
        for (String pluginId : pluginIds) {
            if (project.getPluginManager().hasPlugin(pluginId)) {
                return true;
            }
        }
        return false;
    }

    public static Optional<Object> findExtension(Project project, String... extensionNames) {
        for (String extensionName : extensionNames) {
            Object extension = project.getExtensions().findByName(extensionName);
            if (extension != null) {
                return Optional.of(extension);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> findStringProperty(Object target, String... propertyNames) {
        if (target == null) {
            return Optional.empty();
        }
        for (String propertyName : propertyNames) {
            Optional<String> value = readJavaBeanGetter(target, propertyName);
            if (value.isPresent()) {
                return value;
            }
            value = readNoArgMethod(target, propertyName);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    public static Optional<String> findDependencyVersion(
            Project project, Collection<String> configurationNames, String group, String name) {
        for (String configurationName : configurationNames) {
            Optional<String> version = findDependencyVersion(project, configurationName, group, name);
            if (version.isPresent()) {
                return version;
            }
        }
        return Optional.empty();
    }

    public static Optional<String> findDependencyVersion(
            Project project, String configurationName, String group, String name) {
        Configuration configuration = project.getConfigurations().findByName(configurationName);
        if (configuration == null) {
            return Optional.empty();
        }
        for (Dependency dependency : configuration.getDependencies()) {
            if (group.equals(dependency.getGroup()) && name.equals(dependency.getName())) {
                String version = dependency.getVersion();
                if (version != null && !version.isBlank()) {
                    return Optional.of(version);
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<String> inferMinecraftVersionFromNeoForgeVersion(String neoForgeVersion) {
        if (neoForgeVersion == null) {
            return Optional.empty();
        }
        List<Integer> versionParts = numericVersionParts(neoForgeVersion);
        if (versionParts.size() < 2) {
            return Optional.empty();
        }
        int major = versionParts.get(0);
        int minor = versionParts.get(1);
        if (major < FULL_MINECRAFT_VERSION_MAJOR) {
            return Optional.of("1." + major + "." + minor);
        }

        if (versionParts.size() > 2 && versionParts.get(2) != 0) {
            return Optional.of(major + "." + minor + "." + versionParts.get(2));
        }
        return Optional.of(major + "." + minor);
    }

    private static List<Integer> numericVersionParts(String version) {
        List<Integer> parts = new ArrayList<>();
        for (String part : version.split("\\.")) {
            java.util.regex.Matcher matcher = LEADING_NUMBER_PATTERN.matcher(part);
            if (!matcher.matches()) {
                break;
            }
            parts.add(Integer.parseInt(matcher.group(1)));
        }
        return parts;
    }

    private static Optional<String> readJavaBeanGetter(Object target, String propertyName) {
        if (propertyName.isBlank()) {
            return Optional.empty();
        }
        return readNoArgMethod(
                target,
                "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1));
    }

    private static Optional<String> readNoArgMethod(Object target, String methodName) {
        Method method;
        try {
            method = target.getClass().getMethod(methodName);
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
        try {
            Object value = method.invoke(target);
            return unpackStringValue(value);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            return Optional.empty();
        }
    }

    private static Optional<String> unpackStringValue(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof CharSequence text && !text.isEmpty()) {
            return Optional.of(text.toString());
        }
        Optional<String> providerValue = readProviderValue(value);
        if (providerValue.isPresent()) {
            return providerValue;
        }
        return Optional.empty();
    }

    private static Optional<String> readProviderValue(Object value) {
        try {
            Method getOrNull = value.getClass().getMethod("getOrNull");
            Object providerValue = getOrNull.invoke(value);
            if (providerValue instanceof CharSequence text && !text.isEmpty()) {
                return Optional.of(text.toString());
            }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
