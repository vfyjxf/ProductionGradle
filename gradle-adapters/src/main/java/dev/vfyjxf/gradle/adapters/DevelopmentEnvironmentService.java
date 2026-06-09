package dev.vfyjxf.gradle.adapters;

import dev.vfyjxf.gradle.adapters.fabricloom.FabricLoomAdapter;
import dev.vfyjxf.gradle.adapters.forgegradle.ForgeGradleAdapter;
import dev.vfyjxf.gradle.adapters.moddevgradle.ModDevGradleAdapter;
import dev.vfyjxf.gradle.adapters.neogradle.NeoGradleAdapter;
import java.util.List;
import java.util.Optional;
import org.gradle.api.Project;

public final class DevelopmentEnvironmentService {
    private final List<DevelopmentEnvironmentAdapter> adapters;

    public DevelopmentEnvironmentService() {
        this(List.of(new FabricLoomAdapter(), new ForgeGradleAdapter(), new NeoGradleAdapter(), new ModDevGradleAdapter()));
    }

    public DevelopmentEnvironmentService(List<DevelopmentEnvironmentAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public Optional<DevelopmentEnvironment> detect(Project project) {
        for (DevelopmentEnvironmentAdapter adapter : adapters) {
            if (adapter.isPresent(project)) {
                return Optional.ofNullable(adapter.detect(project));
            }
        }
        return Optional.empty();
    }
}
