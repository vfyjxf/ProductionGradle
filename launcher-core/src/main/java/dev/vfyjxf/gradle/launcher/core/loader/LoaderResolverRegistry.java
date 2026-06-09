package dev.vfyjxf.gradle.launcher.core.loader;

import java.util.List;

public class LoaderResolverRegistry {
    private final List<LoaderResolver> resolvers;

    public LoaderResolverRegistry(List<LoaderResolver> resolvers) {
        if (resolvers == null) {
            this.resolvers = List.of();
            return;
        }
        if (resolvers.stream().anyMatch(resolver -> resolver == null)) {
            throw new IllegalArgumentException("resolver is required");
        }
        this.resolvers = List.copyOf(resolvers);
    }

    public LoaderResolver resolverFor(String loader) {
        if (loader == null || loader.isBlank()) {
            throw new IllegalArgumentException("loader is required");
        }
        return resolvers.stream()
                .filter(resolver -> resolver.supports(loader))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown loader: " + loader));
    }
}
