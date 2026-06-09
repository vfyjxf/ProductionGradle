package dev.vfyjxf.gradle.launcher.core.auth;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class DevLoginLaunchBridge {
    private static final String DEVLOGIN_MAIN_CLASS = "net.covers1624.devlogin.DevLogin";

    private DevLoginLaunchBridge() {
    }

    public static void main(String[] args) throws Throwable {
        Method main = Class.forName(DEVLOGIN_MAIN_CLASS).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause != null) {
                throw cause;
            }
            throw exception;
        }
    }
}
