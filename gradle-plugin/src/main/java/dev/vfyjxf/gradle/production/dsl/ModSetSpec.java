package dev.vfyjxf.gradle.production.dsl;

import javax.inject.Inject;
import java.util.List;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

public abstract class ModSetSpec {
    private final Project project;
    private final ListProperty<String> remoteRequestNotations;
    private String configurationName;

    @Inject
    public ModSetSpec(Project project) {
        this.project = project;
        this.remoteRequestNotations = project.getObjects().listProperty(String.class);
        this.remoteRequestNotations.convention(List.of());
    }

    public abstract Property<Boolean> getIncludeProject();

    public abstract Property<Boolean> getIncludeRequiredDependencies();

    public abstract Property<Boolean> getIncludeOptionalDependencies();

    public Dependency add(Object notation) {
        return project.getDependencies().add(requireConfigurationName(), notation);
    }

    public Dependency add(Object notation, Action<? super Dependency> configure) {
        Dependency dependency = add(notation);
        configure.execute(dependency);
        return dependency;
    }

    public Dependency add(FileCollection files) {
        return add((Object) files);
    }

    public Dependency add(Task task) {
        return add(project.files(task));
    }

    public Dependency add(TaskProvider<? extends Task> task) {
        return add(project.files(task));
    }

    public Dependency add(Provider<RegularFile> file) {
        return add(project.files(file));
    }

    public void modrinth(String slugOrId, Action<? super ModrinthRequestSpec> configure) {
        ModrinthRequestSpec request = new ModrinthRequestSpec(slugOrId, false);
        configure.execute(request);
        remoteRequestNotations.add(request.notation());
    }

    public void modrinthVersion(String versionId) {
        remoteRequestNotations.add(new ModrinthRequestSpec(versionId, true).notation());
    }

    public void curseforge(String name, Action<? super CurseForgeRequestSpec> configure) {
        CurseForgeRequestSpec request = new CurseForgeRequestSpec(name);
        configure.execute(request);
        remoteRequestNotations.add(request.notation());
    }

    public void includeProject(boolean includeProject) {
        getIncludeProject().set(includeProject);
    }

    public void includeRequiredDependencies(boolean includeRequiredDependencies) {
        getIncludeRequiredDependencies().set(includeRequiredDependencies);
    }

    public void includeOptionalDependencies(boolean includeOptionalDependencies) {
        getIncludeOptionalDependencies().set(includeOptionalDependencies);
    }

    public void setConfigurationName(String configurationName) {
        this.configurationName = configurationName;
    }

    public List<String> remoteRequestNotations() {
        return remoteRequestNotations.get();
    }

    public ListProperty<String> getRemoteRequestNotations() {
        return remoteRequestNotations;
    }

    private String requireConfigurationName() {
        if (configurationName == null) {
            throw new InvalidUserDataException("Production mod configuration has not been initialized.");
        }
        return configurationName;
    }

    public interface RemoteModRequestSpec {
        String notation();
    }

    public static final class ModrinthRequestSpec implements RemoteModRequestSpec {
        private final String slugOrId;
        private final boolean versionId;
        private String version;

        private ModrinthRequestSpec(String slugOrId, boolean versionId) {
            this.slugOrId = slugOrId;
            this.versionId = versionId;
        }

        public String getSlugOrId() {
            return slugOrId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        @Override
        public String notation() {
            if (versionId) {
                return "modrinth-version|" + escape(slugOrId);
            }
            return "modrinth-project|" + escape(slugOrId) + "|" + escape(version);
        }
    }

    public static final class CurseForgeRequestSpec implements RemoteModRequestSpec {
        private final String name;
        private Integer projectId;
        private Integer fileId;

        private CurseForgeRequestSpec(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public Integer getProjectId() {
            return projectId;
        }

        public void setProjectId(Integer projectId) {
            this.projectId = projectId;
        }

        public Integer getFileId() {
            return fileId;
        }

        public void setFileId(Integer fileId) {
            this.fileId = fileId;
        }

        @Override
        public String notation() {
            if (projectId == null || fileId == null) {
                throw new InvalidUserDataException(
                        "CurseForge mod '" + name + "' requires projectId and fileId.");
            }
            return "curseforge-file|" + escape(Integer.toString(projectId)) + "|" + escape(Integer.toString(fileId));
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("|", "\\p");
    }
}
