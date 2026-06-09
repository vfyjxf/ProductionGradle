package dev.vfyjxf.gradle.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionGradleE2ETest {
    private static final String SERVER_READY = "For help, type \"help\"";
    private static final String SERVER_STOPPING = "Stopping server";
    private static final String CLIENT_MAIN = "net.minecraft.client.main.Main";
    private static final String CLIENT_E2E_MAIN = "dev.vfyjxf.gradle.integration.ClientE2EMain";
    private static final String CLIENT_E2E_MARKER = "PRODUCTION_GRADLE_CLIENT_E2E_RAN";
    private static final String RCON_PASSWORD = "production-gradle-e2e";
    private static final Set<String> FIXTURE_OUTPUT_DIRECTORIES = Set.of(
            ".gradle",
            ".idea",
            "build",
            "generated-e2e",
            "production-cache",
            "run-production");

    @TempDir
    Path tempDir;
    private Path projectDirectory;
    private Path fixtureDirectory;
    private Path serverGradleUserHome;
    private Path clientGradleUserHome;

    @BeforeEach
    void setUp() throws IOException {
        projectDirectory = Path.of("").toAbsolutePath().normalize();
        Path rootDirectory = rootDirectory(projectDirectory);
        fixtureDirectory = tempDir.resolve("production-e2e");
        serverGradleUserHome = tempDir.resolve("server-gradle-home");
        clientGradleUserHome = tempDir.resolve("client-gradle-home");
        copyDirectory(fixtureRoot(projectDirectory).resolve("production-e2e"), fixtureDirectory, FIXTURE_OUTPUT_DIRECTORIES);
        rewriteIncludedBuild(fixtureDirectory.resolve("settings.gradle"), rootDirectory);
        copyCurrentGradleWrapperDistribution(serverGradleUserHome);
    }

    @Test
    void productionServerAndClientSmokeTest() throws Exception {
        String prepareServerOutput = runGradleTask("prepareProductionServer");
        assertThat(prepareServerOutput).contains("BUILD SUCCESSFUL");
        assertThat(fixtureDirectory.resolve("run-production/server/eula.txt")).isRegularFile();
        ServerPorts ports = writeServerProperties();
        Process server = startGradleTask("runProductionServer");
        OutputCapture output = captureOutput(server);
        try {
            List<String> serverOutput = waitForOutput(server, output, SERVER_READY, Duration.ofMinutes(3));
            assertThat(String.join(System.lineSeparator(), serverOutput))
                    .as("server output before ready marker, process alive=%s", server.isAlive())
                    .contains(SERVER_READY);
            sendRconCommand(ports.rconPort(), "stop");
            serverOutput = waitForOutput(server, output, SERVER_STOPPING, Duration.ofSeconds(45));
            assertThat(String.join(System.lineSeparator(), serverOutput))
                    .as("server output before stop marker, process alive=%s", server.isAlive())
                    .contains(SERVER_STOPPING);
            assertThat(server.waitFor(45, TimeUnit.SECONDS))
                    .as("Gradle server process exits after Minecraft stop command")
                    .isTrue();
            assertThat(String.join(System.lineSeparator(), output.lines()))
                    .as("server output after process exit")
                    .contains(SERVER_STOPPING);
            assertThat(server.exitValue()).isZero();
        } finally {
            if (server.isAlive()) {
                stopServerProcess(server, ports.rconPort());
            }
        }

        seedBoundedClientCache();
        String clientOutput = runGradleTask("runProductionClient", clientGradleUserHome);
        assertThat(clientOutput).contains(CLIENT_E2E_MARKER);
        runGradleTask("prepareProductionClient", clientGradleUserHome);
        String commandOutput = runGradleTask("printProductionClientCommand", clientGradleUserHome);
        assertThat(commandOutput).contains(CLIENT_E2E_MAIN);
    }

    private ServerPorts writeServerProperties() throws IOException {
        int rconPort = freePort();
        int serverPort = freePortExcept(rconPort);
        Path serverDirectory = fixtureDirectory.resolve("run-production/server");
        Files.createDirectories(serverDirectory);
        Files.writeString(
                serverDirectory.resolve("server.properties"),
                "enable-rcon=true" + System.lineSeparator()
                        + "rcon.password=" + RCON_PASSWORD + System.lineSeparator()
                        + "rcon.port=" + rconPort + System.lineSeparator()
                        + "server-ip=127.0.0.1" + System.lineSeparator()
                        + "server-port=" + serverPort + System.lineSeparator(),
                StandardCharsets.UTF_8);
        return new ServerPorts(rconPort, serverPort);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freePortExcept(int excludedPort) throws IOException {
        int port = freePort();
        while (port == excludedPort) {
            port = freePort();
        }
        return port;
    }

    private String runGradleTask(String task) {
        return runGradleTask(task, serverGradleUserHome);
    }

    private String runGradleTask(String task, Path gradleUserHome) {
        return GradleRunner.create()
                .withProjectDir(fixtureDirectory.toFile())
                .withTestKitDir(gradleUserHome.toFile())
                .withArguments(task, "--stacktrace")
                .build()
                .getOutput();
    }

    private Process startGradleTask(String task) throws IOException {
        Path executable = rootDirectory(projectDirectory).resolve(isWindows() ? "gradlew.bat" : "gradlew");
        ProcessBuilder builder = new ProcessBuilder(
                executable.toString(),
                "--gradle-user-home",
                serverGradleUserHome.toString(),
                "--no-daemon",
                task,
                "--stacktrace");
        builder.directory(fixtureDirectory.toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private void seedBoundedClientCache() throws IOException {
        Path versionDirectory = clientGradleUserHome
                .resolve("caches/production-gradle/minecraft/versions/1.21.1");
        Files.createDirectories(versionDirectory);
        Files.writeString(
                versionDirectory.resolve("1.21.1.json"),
                """
                {
                  "id": "1.21.1",
                  "type": "release",
                  "mainClass": "dev.vfyjxf.gradle.integration.ClientE2EMain",
                  "libraries": [],
                  "arguments": {
                    "jvm": [],
                    "game": []
                  }
                }
                """,
                StandardCharsets.UTF_8);
        writeClientE2EJar(versionDirectory.resolve("1.21.1.jar"));
    }

    private void writeClientE2EJar(Path jarPath) throws IOException {
        Path sourceDirectory = fixtureDirectory.resolve("generated-e2e/client-src");
        Path classesDirectory = fixtureDirectory.resolve("generated-e2e/client-classes");
        Path sourceFile = sourceDirectory.resolve("dev/vfyjxf/gradle/integration/ClientE2EMain.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(
                sourceFile,
                """
                package dev.vfyjxf.gradle.integration;

                public final class ClientE2EMain {
                    private ClientE2EMain() {
                    }

                    public static void main(String[] args) {
                        System.out.println("PRODUCTION_GRADLE_CLIENT_E2E_RAN");
                    }
                }
                """,
                StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("system Java compiler").isNotNull();
        Files.createDirectories(classesDirectory);
        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "21",
                "-d",
                classesDirectory.toString(),
                sourceFile.toString());
        assertThat(exitCode).isZero();

        Files.createDirectories(jarPath.getParent());
        Path classFile = classesDirectory.resolve("dev/vfyjxf/gradle/integration/ClientE2EMain.class");
        try (OutputStream output = Files.newOutputStream(jarPath);
                JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("dev/vfyjxf/gradle/integration/ClientE2EMain.class"));
            Files.copy(classFile, jar);
            jar.closeEntry();
        }
    }

    private static void stopServerProcess(Process process, int rconPort) throws InterruptedException {
        try {
            sendRconCommand(rconPort, "stop", Duration.ofSeconds(3));
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                return;
            }
        } catch (Exception ignored) {
            // Fall through to process-tree cleanup when RCON is unavailable or the server is already failing.
        }
        destroyProcessTree(process);
    }

    private static void destroyProcessTree(Process process) throws InterruptedException {
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants = new ArrayList<>(root.descendants().toList());
        Collections.reverse(descendants);
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroy();
            }
        }
        if (root.isAlive()) {
            root.destroy();
        }
        waitForExit(root, descendants, Duration.ofSeconds(10));
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (root.isAlive()) {
            root.destroyForcibly();
        }
        waitForExit(root, descendants, Duration.ofSeconds(10));
    }

    private static void waitForExit(
            ProcessHandle root,
            List<ProcessHandle> descendants,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean anyAlive = root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
            if (!anyAlive) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private OutputCapture captureOutput(Process process) {
        OutputCapture capture = new OutputCapture();
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    capture.add(line);
                }
            } catch (IOException exception) {
                capture.add("Output capture failed: " + exception.getMessage());
            }
        }, "production-e2e-output");
        readerThread.setDaemon(true);
        readerThread.start();
        return capture;
    }

    private List<String> waitForOutput(
            Process process,
            OutputCapture output,
            String marker,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<String> lines = output.lines();
            if (lines.stream().anyMatch(line -> line.contains(marker))) {
                return lines;
            }
            if (!process.isAlive()) {
                return lines;
            }
            Thread.sleep(100);
        }
        return output.lines();
    }

    private static void sendRconCommand(int port, String command) throws Exception {
        sendRconCommand(port, command, Duration.ofSeconds(30));
    }

    private static void sendRconCommand(int port, String command, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                socket.setSoTimeout(5000);
                writeRconPacket(socket.getOutputStream(), 1, 3, RCON_PASSWORD);
                RconPacket login = readRconPacket(socket);
                if (login.id() == -1) {
                    throw new IOException("RCON authentication failed");
                }
                writeRconPacket(socket.getOutputStream(), 2, 2, command);
                readRconPacket(socket);
                return;
            } catch (IOException exception) {
                lastFailure = exception;
                Thread.sleep(250);
            }
        }
        throw new IOException("RCON did not become available on port " + port, lastFailure);
    }

    private static void writeRconPacket(OutputStream output, int id, int type, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + bodyBytes.length + 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4 + 4 + bodyBytes.length + 2);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(bodyBytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        output.write(buffer.array());
        output.flush();
    }

    private static RconPacket readRconPacket(Socket socket) throws IOException {
        byte[] lengthBytes = socket.getInputStream().readNBytes(4);
        if (lengthBytes.length != 4) {
            throw new IOException("RCON response missing length");
        }
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] packet = socket.getInputStream().readNBytes(length);
        if (packet.length != length) {
            throw new IOException("RCON response truncated");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        byte[] body = new byte[Math.max(0, length - 10)];
        buffer.get(body);
        return new RconPacket(id, type, new String(body, StandardCharsets.UTF_8));
    }

    private record RconPacket(int id, int type, String body) {
    }

    private record ServerPorts(int rconPort, int serverPort) {
    }

    private static final class OutputCapture {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final List<String> lines = new ArrayList<>();

        void add(String line) {
            queue.add(line);
        }

        List<String> lines() {
            queue.drainTo(lines);
            return List.copyOf(lines);
        }
    }

    private static Path fixtureRoot(Path projectDirectory) {
        Path direct = projectDirectory.resolve("fixtures");
        if (direct.toFile().isDirectory()) {
            return direct;
        }
        return projectDirectory.resolve("integration-tests/fixtures");
    }

    private static Path rootDirectory(Path projectDirectory) {
        Path directory = projectDirectory;
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve(isWindows() ? "gradlew.bat" : "gradlew"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate Gradle wrapper from " + projectDirectory);
    }

    private static void copyDirectory(Path source, Path target, Set<String> excludedDirectoryNames)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(source)
                        && excludedDirectoryNames.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = source.relativize(file);
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rewriteIncludedBuild(Path settingsFile, Path rootDirectory) throws IOException {
        String settings = Files.readString(settingsFile, StandardCharsets.UTF_8);
        Files.writeString(
                settingsFile,
                settings.replace("includeBuild(\"../../..\")",
                        "includeBuild(\"" + gradleString(rootDirectory) + "\")"),
                StandardCharsets.UTF_8);
    }

    private static String gradleString(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void copyCurrentGradleWrapperDistribution(Path targetGradleUserHome) throws IOException {
        Path wrapperDirectory = currentGradleUserHome().resolve("wrapper");
        if (Files.isDirectory(wrapperDirectory)) {
            copyDirectory(wrapperDirectory, targetGradleUserHome.resolve("wrapper"), Set.of());
        }
    }

    private Path currentGradleUserHome() {
        String systemProperty = System.getProperty("gradle.user.home");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return Path.of(systemProperty);
        }
        String environment = System.getenv("GRADLE_USER_HOME");
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment);
        }
        Path projectGradleHome = projectDirectory.resolve(".gradle");
        if (Files.isDirectory(projectGradleHome)) {
            return projectGradleHome;
        }
        return Path.of(System.getProperty("user.home"), ".gradle");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
