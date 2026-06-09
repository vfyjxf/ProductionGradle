package dev.vfyjxf.gradle.launcher.core.loader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class RemoteResolutionTestSupport {
    private RemoteResolutionTestSupport() {
    }

    public static String sha1(String value) {
        return sha1(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha1(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 digest is not available", exception);
        }
    }

    public static byte[] jarBytes(Path directory, String fileName) throws IOException {
        Path jarPath = directory.resolve(fileName);
        Files.createDirectories(jarPath.getParent());
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream ignored = new JarOutputStream(output)) {
        }
        return Files.readAllBytes(jarPath);
    }

    public static byte[] jarBytes(Path directory, String fileName, String mainClass) throws IOException {
        Path jarPath = directory.resolve(fileName);
        Files.createDirectories(jarPath.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream ignored = new JarOutputStream(output, manifest)) {
        }
        return Files.readAllBytes(jarPath);
    }

    public static byte[] jarBytesWithEntry(Path directory, String fileName, String entryName, byte[] content)
            throws IOException {
        return jarBytesWithEntry(directory, fileName, null, entryName, content);
    }

    public static byte[] jarBytesWithEntry(
            Path directory,
            String fileName,
            String mainClass,
            String entryName,
            byte[] content) throws IOException {
        Path jarPath = directory.resolve(fileName);
        Files.createDirectories(jarPath.getParent());
        Manifest manifest = null;
        if (mainClass != null && !mainClass.isBlank()) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = manifest == null
                        ? new JarOutputStream(output)
                        : new JarOutputStream(output, manifest)) {
            JarEntry entry = new JarEntry(entryName);
            jar.putNextEntry(entry);
            jar.write(content);
            jar.closeEntry();
        }
        return Files.readAllBytes(jarPath);
    }

    public static byte[] installerJarBytes(Path directory, String fileName, String versionJson) throws IOException {
        return installerJarBytes(directory, fileName, versionJson, null);
    }

    public static byte[] installerJarBytes(
            Path directory,
            String fileName,
            String versionJson,
            String installProfileJson) throws IOException {
        Path jarPath = directory.resolve(fileName);
        Files.createDirectories(jarPath.getParent());
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(output)) {
            JarEntry entry = new JarEntry("version.json");
            jar.putNextEntry(entry);
            jar.write(versionJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            if (installProfileJson != null) {
                JarEntry profileEntry = new JarEntry("install_profile.json");
                jar.putNextEntry(profileEntry);
                jar.write(installProfileJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return Files.readAllBytes(jarPath);
    }

    public static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final URI baseUri;
        private final AtomicInteger requestCount = new AtomicInteger();

        private TestServer(HttpServer server) {
            this.server = server;
            this.baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        public static TestServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            return new TestServer(server);
        }

        public URI baseUri() {
            return baseUri;
        }

        public int requestCount() {
            return requestCount.get();
        }

        public void response(String path, String body) {
            response(path, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public void response(String path, byte[] body) {
            server.createContext(path, exchange -> {
                requestCount.incrementAndGet();
                respond(exchange, body);
            });
        }

        private static void respond(HttpExchange exchange, byte[] body) throws IOException {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        @Override
        public void close() {
            server.stop((int) Duration.ZERO.toSeconds());
        }
    }
}
