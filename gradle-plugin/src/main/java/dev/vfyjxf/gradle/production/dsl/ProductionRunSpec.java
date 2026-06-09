package dev.vfyjxf.gradle.production.dsl;

import java.util.Arrays;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

public abstract class ProductionRunSpec implements Named {
    private final String name;
    private final ModSetSpec mods;

    @Inject
    public ProductionRunSpec(String name, ModSetSpec mods) {
        this.name = name;
        this.mods = mods;
    }

    @Override
    public String getName() {
        return name;
    }

    public abstract Property<String> getType();

    public abstract Property<String> getMinecraftVersion();

    public abstract Property<String> getLoader();

    public abstract Property<String> getLoaderVersion();

    public abstract DirectoryProperty getInstanceDir();

    public abstract DirectoryProperty getWorkingDir();

    public abstract DirectoryProperty getCacheDir();

    public abstract Property<Integer> getJavaVersion();

    public abstract RegularFileProperty getJavaExecutable();

    public abstract ListProperty<String> getJvmArgs();

    public abstract ListProperty<String> getGameArgs();

    public abstract MapProperty<String, String> getEnvironment();

    public abstract Property<String> getMainClass();

    public abstract Property<Boolean> getEula();

    public abstract Property<String> getUserName();

    public abstract Property<Boolean> getMicrosoftAuth();

    public ModSetSpec getMods() {
        return mods;
    }

    public void jvmArgs(String... args) {
        getJvmArgs().addAll(Arrays.asList(args));
    }

    public void gameArgs(String... args) {
        getGameArgs().addAll(Arrays.asList(args));
    }

    public void environment(String key, String value) {
        getEnvironment().put(key, value);
    }

    public void eula(boolean accepted) {
        getEula().set(accepted);
    }

    public void userName(String userName) {
        getUserName().set(userName);
    }

    public void microsoftAuth(boolean microsoftAuth) {
        getMicrosoftAuth().set(microsoftAuth);
    }

    public void mods(Action<? super ModSetSpec> action) {
        action.execute(mods);
    }
}
