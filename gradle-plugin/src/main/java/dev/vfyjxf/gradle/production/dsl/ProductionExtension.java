package dev.vfyjxf.gradle.production.dsl;

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

public abstract class ProductionExtension {
    private final IdeaSpec idea;
    private final NamedDomainObjectContainer<ProductionRunSpec> runs;

    @Inject
    public ProductionExtension(Project project) {
        ObjectFactory objects = project.getObjects();
        this.idea = objects.newInstance(IdeaSpec.class);
        this.runs = objects.domainObjectContainer(ProductionRunSpec.class, name -> objects.newInstance(
                ProductionRunSpec.class,
                name,
                objects.newInstance(ModSetSpec.class, project)));
    }

    public abstract Property<Boolean> getAutoDetect();

    public abstract DirectoryProperty getInstanceDir();

    public abstract DirectoryProperty getCacheDir();

    public IdeaSpec getIdea() {
        return idea;
    }

    public NamedDomainObjectContainer<ProductionRunSpec> getRuns() {
        return runs;
    }

    public void autoDetect(boolean autoDetect) {
        getAutoDetect().set(autoDetect);
    }

    public void idea(Action<? super IdeaSpec> action) {
        action.execute(idea);
    }

    public void runs(Action<? super NamedDomainObjectContainer<ProductionRunSpec>> action) {
        action.execute(runs);
    }
}
