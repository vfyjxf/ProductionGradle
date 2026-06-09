package dev.vfyjxf.gradle.launcher.core.download;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloaderTest {
    @TempDir
    Path tempDir;

    @Test
    void offlineModeWithMissingTargetThrowsOfflineCacheMiss() {
        Path target = tempDir.resolve("missing.jar");
        DownloadRequest request = new DownloadRequest(
                URI.create("https://example.invalid/missing.jar"),
                target,
                null,
                null,
                true);

        assertThatThrownBy(() -> new Downloader().download(request))
                .isInstanceOf(OfflineCacheMissException.class)
                .hasMessageContaining(target.toString());
    }

    @Test
    void existingFileWithMatchingSha1ReturnsWithoutNetwork() throws Exception {
        byte[] cachedBytes = "already cached".getBytes();
        Path target = tempDir.resolve("cached.jar");
        Files.write(target, cachedBytes);

        try (TestServer server = TestServer.respondingWith("network".getBytes())) {
            DownloadRequest request = new DownloadRequest(
                    server.uri(),
                    target,
                    "sha1",
                    Checksum.sha1(target),
                    false);

            DownloadResult result = new Downloader().download(request);

            assertThat(result.target()).isEqualTo(target);
            assertThat(result.downloaded()).isFalse();
            assertThat(Files.readAllBytes(target)).isEqualTo(cachedBytes);
            assertThat(server.requests()).isZero();
        }
    }

    @Test
    void existingFileWithMismatchedSha1IsRejected() throws Exception {
        Path target = tempDir.resolve("cached.jar");
        Files.writeString(target, "stale");

        try (TestServer server = TestServer.respondingWith("network".getBytes())) {
            DownloadRequest request = new DownloadRequest(
                    server.uri(),
                    target,
                    "sha1",
                    sha1Of("expected"),
                    false);

            assertThatThrownBy(() -> new Downloader().download(request))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("checksum")
                    .hasMessageContaining(target.toString());
            assertThat(Files.readString(target)).isEqualTo("stale");
            assertThat(server.requests()).isZero();
        }
    }

    @Test
    void onlineModeWritesPartFileAndMovesIntoPlace() throws Exception {
        byte[] responseBytes = "downloaded content".getBytes();
        Path target = tempDir.resolve("nested").resolve("download.jar");
        Path partial = Path.of(target + ".part");

        try (BlockingTestServer server = BlockingTestServer.respondingWith(responseBytes)) {
            DownloadRequest request = new DownloadRequest(
                    server.uri(),
                    target,
                    "sha1",
                    sha1Of("downloaded content"),
                    false);

            CompletableFuture<DownloadResult> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return new Downloader().download(request);
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });

            assertThat(server.awaitResponseStarted(Duration.ofSeconds(5))).isTrue();
            assertThat(awaitExists(partial, Duration.ofSeconds(5))).isTrue();
            assertThat(Files.exists(target)).isFalse();

            server.finishResponse();
            DownloadResult completed = result.get(5, TimeUnit.SECONDS);

            assertThat(completed.target()).isEqualTo(target);
            assertThat(completed.downloaded()).isTrue();
            assertThat(Files.readAllBytes(target)).isEqualTo(responseBytes);
            assertThat(Files.exists(partial)).isFalse();
            assertThat(server.requests()).isEqualTo(1);
        }
    }

    @Test
    void onlineModePrintsDownloadSourceAndTarget() throws Exception {
        byte[] responseBytes = "downloaded content".getBytes();
        Path target = tempDir.resolve("logged.jar");

        try (TestServer server = TestServer.respondingWith(responseBytes)) {
            DownloadRequest request = new DownloadRequest(
                    server.uri(),
                    target,
                    "sha1",
                    sha1Of("downloaded content"),
                    false);

            String output = captureStandardOut(() -> new Downloader().download(request));

            assertThat(output)
                    .contains("[ProductionGradle] Downloading " + server.uri())
                    .contains(target.toString());
        }
    }

    @Test
    void transientReadTimeoutIsRetried() throws Exception {
        byte[] responseBytes = "downloaded after retry".getBytes();
        Path target = tempDir.resolve("retry.jar");

        try (RetryAfterTimeoutServer server = RetryAfterTimeoutServer.respondingWith(responseBytes)) {
            DownloadRequest request = new DownloadRequest(
                    server.uri(),
                    target,
                    "sha1",
                    sha1Of("downloaded after retry"),
                    false);
            Downloader downloader = new Downloader(new OkHttpClient.Builder()
                    .readTimeout(Duration.ofMillis(100))
                    .build());

            DownloadResult result = downloader.download(request);

            assertThat(result.target()).isEqualTo(target);
            assertThat(result.downloaded()).isTrue();
            assertThat(Files.readAllBytes(target)).isEqualTo(responseBytes);
            assertThat(server.requests()).isEqualTo(2);
        }
    }

    @Test
    void existingFileWithMatchingSha1DoesNotPrintCachedTarget() throws Exception {
        byte[] cachedBytes = "already cached".getBytes();
        Path target = tempDir.resolve("cached-logged.jar");
        Files.write(target, cachedBytes);

        try (TestServer server = TestServer.respondingWith("network".getBytes())) {
            DownloadRequest request = new DownloadRequest(
                    server.uri(),
                    target,
                    "sha1",
                    Checksum.sha1(target),
                    false);

            String output = captureStandardOut(() -> new Downloader().download(request));

            assertThat(output).isEmpty();
            assertThat(server.requests()).isZero();
        }
    }

    private static String sha1Of(String value) throws IOException {
        Path file = Files.createTempFile("checksum", ".txt");
        try {
            Files.writeString(file, value);
            return Checksum.sha1(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static boolean awaitExists(Path path, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) {
                return true;
            }
            Thread.sleep(10);
        }
        return Files.exists(path);
    }

    private static String captureStandardOut(DownloadAction action) throws IOException {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            action.run();
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface DownloadAction {
        void run() throws IOException;
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        private TestServer(HttpServer server) {
            this.server = server;
        }

        static TestServer respondingWith(byte[] body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestServer testServer = new TestServer(server);
            server.createContext("/file", exchange -> {
                testServer.requests.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return testServer;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
        }

        int requests() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class RetryAfterTimeoutServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicInteger requests = new AtomicInteger();

        private RetryAfterTimeoutServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static RetryAfterTimeoutServer respondingWith(byte[] body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            RetryAfterTimeoutServer testServer = new RetryAfterTimeoutServer(server, executor);
            server.setExecutor(executor);
            server.createContext("/file", exchange -> {
                int request = testServer.requests.incrementAndGet();
                if (request == 1) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                    return;
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return testServer;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
        }

        int requests() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class BlockingTestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch responseStarted = new CountDownLatch(1);
        private final CountDownLatch finishResponse = new CountDownLatch(1);

        private BlockingTestServer(HttpServer server) {
            this.server = server;
        }

        static BlockingTestServer respondingWith(byte[] body) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            BlockingTestServer testServer = new BlockingTestServer(server);
            server.createContext("/file", exchange -> {
                testServer.requests.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body, 0, body.length - 1);
                    output.flush();
                    testServer.responseStarted.countDown();
                    if (!testServer.finishResponse.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to finish test response");
                    }
                    output.write(body, body.length - 1, 1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to finish test response", exception);
                }
            });
            server.start();
            return testServer;
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
        }

        boolean awaitResponseStarted(Duration timeout) throws InterruptedException {
            return responseStarted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void finishResponse() {
            finishResponse.countDown();
        }

        int requests() {
            return requests.get();
        }

        @Override
        public void close() {
            finishResponse();
            server.stop(0);
        }
    }
}
