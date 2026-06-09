package dev.vfyjxf.gradle.launcher.core.loader;

import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchSpec;

public interface LoaderResolver {
    boolean supports(String loader);

    default PreparedLaunch prepare(LaunchContext context) throws Exception {
        return prepare(context.spec());
    }

    PreparedLaunch prepare(LaunchSpec spec) throws Exception;
}
