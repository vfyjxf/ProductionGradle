package dev.vfyjxf.gradle.launcher.core.download;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public class Downloader {
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;

    private final OkHttpClient client;

    public Downloader() {
        this(new OkHttpClient());
    }

    public Downloader(OkHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public DownloadResult download(DownloadRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (Files.exists(request.target())) {
            if (request.hasChecksum() && !Checksum.matches(
                    request.target(),
                    request.checksumAlgorithm(),
                    request.checksum())) {
                throw new IOException("Existing file checksum mismatch for " + request.target());
            }
            return new DownloadResult(request.target(), false);
        }
        if (request.offline()) {
            throw new OfflineCacheMissException(request.uri(), request.target());
        }

        Path parent = request.target().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        IOException failure = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                return downloadAttempt(request, attempt);
            } catch (IOException exception) {
                failure = exception;
                cleanupPartial(request.target(), exception);
                if (attempt == MAX_DOWNLOAD_ATTEMPTS) {
                    throw exception;
                }
            } catch (RuntimeException exception) {
                cleanupPartial(request.target(), exception);
                throw exception;
            }
        }
        throw failure == null ? new IOException("Download failed for " + request.uri()) : failure;
    }

    private DownloadResult downloadAttempt(DownloadRequest request, int attempt) throws IOException {
        Path partial = partialPath(request.target());
        if (attempt == 1) {
            System.out.println("[ProductionGradle] Downloading " + request.uri() + " -> " + request.target());
        } else {
            System.out.println("[ProductionGradle] Retrying download " + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS
                    + ": " + request.uri() + " -> " + request.target());
        }
        downloadToPartial(request, partial);
        if (request.hasChecksum() && !Checksum.matches(partial, request.checksumAlgorithm(), request.checksum())) {
            throw new IOException("Downloaded file checksum mismatch for " + request.target());
        }
        moveIntoPlace(partial, request.target());
        System.out.println("[ProductionGradle] Downloaded " + request.target());
        return new DownloadResult(request.target(), true);
    }

    private static void cleanupPartial(Path target, Exception exception) {
        try {
            Files.deleteIfExists(partialPath(target));
        } catch (IOException cleanupFailure) {
            exception.addSuppressed(cleanupFailure);
        }
    }

    private void downloadToPartial(DownloadRequest request, Path partial) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(request.uri().toURL())
                .get()
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Download failed for " + request.uri() + ": HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Download failed for " + request.uri() + ": empty response body");
            }
            try (InputStream input = body.byteStream();
                    OutputStream output = Files.newOutputStream(partial)) {
                input.transferTo(output);
            }
        }
    }

    private static Path partialPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static void moveIntoPlace(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
