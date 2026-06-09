package dev.vfyjxf.gradle.launcher.core.loader.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vfyjxf.gradle.launcher.core.cache.CacheLayout;
import dev.vfyjxf.gradle.launcher.core.download.DownloadRequest;
import dev.vfyjxf.gradle.launcher.core.download.DownloadResult;
import dev.vfyjxf.gradle.launcher.core.download.Downloader;
import dev.vfyjxf.gradle.launcher.core.download.OfflineCacheMissException;
import dev.vfyjxf.gradle.launcher.core.json.Json;
import dev.vfyjxf.gradle.launcher.core.launch.LaunchContext;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class MinecraftAssetDownloader {
    private static final int ASSET_DOWNLOAD_WORKERS = 8;

    private final Downloader downloader;

    public MinecraftAssetDownloader(Downloader downloader) {
        this.downloader = downloader;
    }

    public void downloadAssets(Path indexPath, CacheLayout cache, URI assetBaseUri, LaunchContext context) {
        AssetIndex index;
        try {
            index = Json.mapper().readValue(indexPath.toFile(), AssetIndex.class);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read minecraft asset index at " + indexPath, exception);
        }

        List<AssetDownload> assets = downloads(cache, assetBaseUri, index);
        if (assets.isEmpty()) {
            return;
        }

        System.out.println("[ProductionGradle] Downloading " + assets.size()
                + " Minecraft assets with " + ASSET_DOWNLOAD_WORKERS + " workers.");

        AtomicInteger completed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(ASSET_DOWNLOAD_WORKERS);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (AssetDownload asset : assets) {
                futures.add(executor.submit(() -> downloadAsset(asset, context, completed, assets.size())));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            System.out.println("[ProductionGradle] Minecraft assets complete.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(futures);
            throw new IllegalStateException("interrupted while resolving minecraft assets", exception);
        } catch (ExecutionException exception) {
            cancel(futures);
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("failed to resolve minecraft assets", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private void downloadAsset(
            AssetDownload asset,
            LaunchContext context,
            AtomicInteger completed,
            int total) {
        DownloadResult result;
        try {
            result = downloader.download(new DownloadRequest(
                    asset.uri(),
                    asset.target(),
                    "SHA-1",
                    asset.hash(),
                    context.offline()));
        } catch (OfflineCacheMissException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to resolve minecraft asset at " + asset.target(), exception);
        }

        int progress = completed.incrementAndGet();
        if (result.downloaded() || progress == total) {
            System.out.println("[ProductionGradle] Minecraft assets " + progress + "/" + total + ": " + asset.hash());
        }
    }

    private static List<AssetDownload> downloads(CacheLayout cache, URI assetBaseUri, AssetIndex index) {
        List<AssetDownload> downloads = new ArrayList<>();
        for (AssetObject object : index.objects().values()) {
            if (!ProductionLaunchSupport.text(object.hash()) || object.hash().length() < 2) {
                continue;
            }
            String prefix = object.hash().substring(0, 2);
            Path target = cache.assets()
                    .resolve("objects")
                    .resolve(prefix)
                    .resolve(object.hash());
            URI uri = assetBaseUri.resolve(prefix + "/" + object.hash());
            downloads.add(new AssetDownload(object.hash(), target, uri));
        }
        return downloads;
    }

    private static void cancel(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private record AssetDownload(String hash, Path target, URI uri) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AssetIndex(Map<String, AssetObject> objects) {
        private AssetIndex {
            objects = objects == null ? Map.of() : Map.copyOf(objects);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AssetObject(String hash, Long size) {
    }
}
