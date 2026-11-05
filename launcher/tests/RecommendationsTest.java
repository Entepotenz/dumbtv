package tv.dumbtv.home;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecommendationsTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        check(Recommendations.progress(25, 100) == 25, "quarter watched");
        check(Recommendations.progress(1, 3) == 33, "fractional progress");
        check(Recommendations.progress(120, 100) == 100, "position beyond duration");
        check(Recommendations.progress(-1, 100) == 0, "negative position");
        check(Recommendations.progress(1, 0) == 0, "unknown duration");
        check(Recommendations.progress(1, -1) == 0, "negative duration");
        check(Recommendations.progress(Long.MAX_VALUE, Long.MAX_VALUE) == 100, "large times do not overflow");
        check(Recommendations.progress(Long.MAX_VALUE, 1) == 100, "large position is clamped");
        check("Jellyfin - S2 E3 Episode".equals(Recommendations.subtitle("Jellyfin", "2", "3", "Episode")), "episode metadata");
        check("Prime Video".equals(Recommendations.subtitle("Prime Video", null, null, null)), "movie without episode metadata");
        check("Jellyfin - E3".equals(Recommendations.subtitle("Jellyfin", "", "3", "")), "missing season");
        check("Jellyfin - Finale".equals(Recommendations.subtitle("Jellyfin", null, null, "Finale")), "episode title only");
        check("off".equals(Config.RECOMMENDATIONS.fallback), "recommendations are opt-in");
        check(Arrays.asList(Config.CHOICES).contains(Config.RECOMMENDATIONS), "recommendations appear in Options");
        File root = Files.createTempDirectory("recommendation-cache-test").toFile();
        Recommendations.ArtworkCache cache = new Recommendations.ArtworkCache(root);
        File entry = cache.entry("app.one", "content://art/poster");
        byte[] image = {1, 2, 3};
        try {
            check(cache.read(entry) == null, "missing artwork is a cache miss");
            cache.write(entry, image);
            check(Arrays.equals(cache.read(entry), image), "artwork is read from disk");
            Recommendations.ArtworkCache restarted = new Recommendations.ArtworkCache(root);
            check(Arrays.equals(restarted.read(restarted.entry("app.one", "content://art/poster")), image),
                    "artwork survives a new cache instance");
            check(!entry.equals(cache.entry("app.two", "content://art/poster")), "owners have separate cache keys");
            check(!entry.equals(cache.entry("app.one", "content://art/changed")), "changed URI uses a new entry");
            check(entry.getName().matches("[0-9a-f]{64}"), "cache filenames do not expose source addresses");
            check(entry.setLastModified(System.currentTimeMillis() - Recommendations.ArtworkCache.MAX_AGE - 1000),
                    "set expired artwork timestamp");
            check(cache.read(entry) == null && !entry.exists(), "expired artwork is removed");
            cache.write(entry, image);
            check(entry.setLastModified(System.currentTimeMillis() + 60000), "set future artwork timestamp");
            check(cache.read(entry) == null && !entry.exists(), "clock rollback invalidates cached artwork");
            cache.write(entry, new byte[0]);
            check(!entry.exists(), "empty artwork is not cached");
            cache.write(entry, new byte[2 * 1024 * 1024 + 1]);
            check(!entry.exists(), "oversized artwork is not cached");
            Files.write(entry.toPath(), new byte[2 * 1024 * 1024 + 1]);
            check(cache.read(entry) == null && !entry.exists(), "oversized disk entry is removed");
            Files.write(entry.toPath(), new byte[0]);
            check(cache.read(entry) == null && !entry.exists(), "empty disk entry is removed");
            cache.write(entry, image);
            check(entry.setLastModified(System.currentTimeMillis() - 60000), "mark oldest cache entry");
            byte[] large = new byte[2 * 1024 * 1024];
            for (int index = 0; index < 18; index++) {
                cache.write(cache.entry("app.one", "poster:" + index), large);
            }
            long total = 0;
            for (File file : entry.getParentFile().listFiles()) total += file.length();
            check(total <= Recommendations.ArtworkCache.MAX_SIZE, "disk cache stays within its size budget");
            check(!entry.exists(), "oldest artwork is evicted first");
        } finally {
            File[] files = entry.getParentFile().listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(entry.getParentFile().toPath());
            Files.deleteIfExists(root.toPath());
        }
        Recommendations.ArtworkLoader loader = new Recommendations.ArtworkLoader();
        CountDownLatch sourceStarted = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        CountDownLatch sourceCompleted = new CountDownLatch(1);
        CountDownLatch cacheCompleted = new CountDownLatch(1);
        AtomicBoolean cachedSourceCalled = new AtomicBoolean();
        try {
            loader.execute(() -> false, () -> {
                sourceStarted.countDown();
                try {
                    releaseSource.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }, sourceCompleted::countDown);
            check(sourceStarted.await(5, TimeUnit.SECONDS), "slow image source starts");
            loader.execute(() -> true, () -> cachedSourceCalled.set(true), cacheCompleted::countDown);
            check(cacheCompleted.await(5, TimeUnit.SECONDS), "disk hits bypass a blocked source request");
            check(sourceCompleted.getCount() == 1, "source remains blocked while cached artwork completes");
            check(!cachedSourceCalled.get(), "cache hits do not fetch the source");
            releaseSource.countDown();
            check(sourceCompleted.await(5, TimeUnit.SECONDS), "source completion is delivered");
        } finally {
            releaseSource.countDown();
            loader.shutdownNow();
        }
        System.out.println("RecommendationsTest passed");
    }
}
