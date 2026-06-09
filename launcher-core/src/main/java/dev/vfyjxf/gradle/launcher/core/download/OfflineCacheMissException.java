package dev.vfyjxf.gradle.launcher.core.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class OfflineCacheMissException extends IOException {
    public OfflineCacheMissException(URI uri, Path target) {
        super("Offline cache miss for " + target + " from " + uri);
    }
}
