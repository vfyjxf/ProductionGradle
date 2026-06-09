package dev.vfyjxf.gradle.adapters;

import org.gradle.api.Project;

public interface DevelopmentEnvironmentAdapter {
    boolean isPresent(Project project);

    DevelopmentEnvironment detect(Project project);
}
