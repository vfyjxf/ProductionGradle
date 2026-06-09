package dev.vfyjxf.gradle.mods;

public interface ModProvider {
    boolean supports(ModRequest request);

    ModProviderResult resolve(ModProviderContext context, ModRequest request) throws ModResolutionException;
}
