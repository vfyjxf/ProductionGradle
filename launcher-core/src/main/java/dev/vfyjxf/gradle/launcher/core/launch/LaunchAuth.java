package dev.vfyjxf.gradle.launcher.core.launch;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LaunchAuth(String mode, String userName, String tokenCacheKey) {
}
