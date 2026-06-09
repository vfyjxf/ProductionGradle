package dev.vfyjxf.gradle.production.internal;

import dev.vfyjxf.gradle.production.dsl.ProductionRunSpec;
import javax.inject.Inject;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;

public class ToolchainResolver {
    private final JavaToolchainService toolchains;

    @Inject
    public ToolchainResolver(JavaToolchainService toolchains) {
        this.toolchains = toolchains;
    }

    public Provider<RegularFile> resolveJavaExecutable(ProductionRunSpec run) {
        Provider<RegularFile> explicit = run.getJavaExecutable();
        Provider<RegularFile> toolchain = run.getJavaVersion().orElse(21).flatMap(version -> toolchains
                .launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(version)))
                .map(JavaLauncher::getExecutablePath));
        return explicit.orElse(toolchain);
    }
}
