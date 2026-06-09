package dev.vfyjxf.gradle.mods;

public class ModResolutionException extends Exception {
    public ModResolutionException(String message) {
        super(message);
    }

    public ModResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
