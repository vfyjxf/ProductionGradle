package dev.vfyjxf.gradle.production.dsl;

import org.gradle.api.provider.Property;

public abstract class IdeaSpec {
    public abstract Property<Boolean> getEnabled();

    public abstract Property<String> getMode();

    public abstract Property<Boolean> getOverwrite();

    public void enabled(boolean enabled) {
        getEnabled().set(enabled);
    }

    public void mode(String mode) {
        getMode().set(mode);
    }

    public void overwrite(boolean overwrite) {
        getOverwrite().set(overwrite);
    }
}
