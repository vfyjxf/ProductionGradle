package dev.vfyjxf.gradle.mods;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GradleArtifactModProvider implements ModProvider {
    @Override
    public boolean supports(ModRequest request) {
        return request != null && request.type() == ModRequest.Type.GRADLE_ARTIFACT;
    }

    @Override
    public ModProviderResult resolve(ModProviderContext context, ModRequest request) throws ModResolutionException {
        Objects.requireNonNull(request, "request");
        if (!supports(request)) {
            throw new ModResolutionException("Unsupported mod request for Gradle artifact provider: " + request.type());
        }
        if (!Files.isRegularFile(request.file())) {
            throw new ModResolutionException("Gradle artifact mod file does not exist: " + request.file());
        }
        return ModProviderResult.of(List.of(new ModProviderResult.ModFile(
                "gradle",
                request.identifier(),
                request.version(),
                request.file(),
                request.metadata() == null ? Map.of() : request.metadata())));
    }
}
