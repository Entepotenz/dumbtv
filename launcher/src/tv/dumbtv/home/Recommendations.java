package tv.dumbtv.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

final class Recommendations {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final String KIND = "recommendations";
    private static final int MAX_ROWS = 12;
    private static final int MAX_ITEMS = 20;
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static final class Channel {
        final String id, title;
        final List<Item> items = new ArrayList<>();

        Channel(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    static final class Result {
        final List<Channel> channels = new ArrayList<>();
        String status = "No recommendations shared by installed apps";
    }

    private Recommendations() {}

    static boolean available(Context context) {
        return Build.VERSION.SDK_INT >= 26
                && context.getPackageManager().resolveContentProvider("android.media.tv", 0) != null;
    }

    static Result load(Context context, Set<String> allowedPackages) {
        Result result = new Result();
        if (!available(context)) {
            result.status = "TV recommendations unavailable on this device";
            return result;
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            result.status = "TV listings permission required";
            return result;
        }
        Map<String, Channel> channels = new HashMap<>();
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://android.media.tv/channel"),
                new String[] {"_id", "package_name", "display_name", "type"}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String owner = cursor.getString(1);
                if (!allowedPackages.contains(owner) || !"TYPE_PREVIEW".equals(cursor.getString(3))) continue;
                String id = cursor.getString(0);
                channels.put(id, new Channel(id, appName(context, owner) + " - " + cursor.getString(2)));
            }
        } catch (RuntimeException ignored) {
            result.status = "TV recommendations could not be read";
        }
        Channel next = new Channel("watch-next", "Continue watching");
        readPrograms(context, "watch_next_program", allowedPackages, channels, next, result);
        if (!next.items.isEmpty()) result.channels.add(0, next);
        readPrograms(context, "preview_program", allowedPackages, channels, null, result);
        return result;
    }

    private static String appName(Context context, String owner) {
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(owner, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return owner;
        }
    }

    private static void readPrograms(Context context, String table, Set<String> allowed,
            Map<String, Channel> channels, Channel next, Result result) {
        String[] columns = {"_id", "package_name", "title", "intent_uri", "poster_art_uri",
                "episode_title", "season_display_number", "episode_display_number",
                "last_playback_position_millis", "duration_millis", "browsable",
                next == null ? "channel_id" : "last_engagement_time_utc_millis"};
        Set<String> seen = new HashSet<>();
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://android.media.tv/" + table), columns, null, null,
                next == null ? "weight DESC, _id ASC" : "last_engagement_time_utc_millis DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                String owner = cursor.getString(1), title = cursor.getString(2), link = cursor.getString(3);
                if (!allowed.contains(owner) || title == null || title.trim().isEmpty()
                        || link == null || cursor.getInt(10) == 0) continue;
                Channel channel = next == null ? channels.get(cursor.getString(11)) : next;
                if (channel == null || channel.items.size() >= MAX_ITEMS) continue;
                if (next == null && !result.channels.contains(channel) && result.channels.size() >= MAX_ROWS) continue;
                if (!seen.add(owner + "\n" + link)) continue;
                Intent intent = launchIntent(owner, link);
                if (intent == null || intent.resolveActivity(context.getPackageManager()) == null) continue;
                Item item = new Item(table + ":" + cursor.getString(0), title, intent);
                item.recommendation = true;
                item.owner = owner;
                item.subtitle = subtitle(appName(context, owner), cursor.getString(6), cursor.getString(7), cursor.getString(5));
                item.poster = cursor.getString(4);
                item.progress = progress(cursor.getLong(8), cursor.getLong(9));
                channel.items.add(item);
                if (next == null && !result.channels.contains(channel)) result.channels.add(channel);
            }
        } catch (RuntimeException ignored) {
            result.status = "TV recommendations could not be read";
        }
    }

    static Intent launchIntent(String owner, String link) {
        try {
            Intent intent = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null && !owner.equals(intent.getComponent().getPackageName())) return null;
            intent.setSelector(null);
            intent.setPackage(owner);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClipData(null);
            return intent;
        } catch (java.net.URISyntaxException | RuntimeException ignored) {
            return null;
        }
    }

    static int progress(long position, long duration) {
        if (position <= 0 || duration <= 0) return 0;
        return (int) Math.min(100, position * 100.0 / duration);
    }

    static String subtitle(String source, String season, String number, String episode) {
        String detail = season == null || season.isEmpty() ? "" : "S" + season;
        if (number != null && !number.isEmpty()) detail += " E" + number;
        if (episode != null && !episode.isEmpty()) detail += " " + episode;
        return source + (detail.trim().isEmpty() ? "" : " - " + detail.trim());
    }

    static final class ArtworkCache {
        static final long MAX_AGE = 7L * 24 * 60 * 60 * 1000;
        static final long MAX_SIZE = 32L * 1024 * 1024;
        private final File directory;

        ArtworkCache(File root) {
            directory = new File(root, "recommendation-art-v1");
        }

        File entry(String owner, String poster) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest((owner + "\n" + poster).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder name = new StringBuilder();
                for (byte value : digest) {
                    name.append(Character.forDigit((value & 255) >>> 4, 16));
                    name.append(Character.forDigit(value & 15, 16));
                }
                return new File(directory, name.toString());
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException(error);
            }
        }

        byte[] read(File file) {
            long age = System.currentTimeMillis() - file.lastModified();
            if (age < 0 || age >= MAX_AGE || file.length() == 0 || file.length() > MAX_BYTES) {
                file.delete();
                return null;
            }
            try (InputStream input = new FileInputStream(file)) {
                return readBytes(input);
            } catch (IOException ignored) {
                file.delete();
                return null;
            }
        }

        void write(File file, byte[] data) {
            synchronized (ArtworkCache.class) {
                store(file, data);
            }
        }

        private void store(File file, byte[] data) {
            if (data.length == 0 || data.length > MAX_BYTES) return;
            File temporary = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                temporary = File.createTempFile("art-", ".tmp", directory);
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(data);
                }
                if (!temporary.renameTo(file)) return;
                trim();
            } catch (IOException | RuntimeException ignored) {
            } finally {
                if (temporary != null) temporary.delete();
            }
        }

        private void trim() {
            File[] files = directory.listFiles();
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long size = 0;
            long now = System.currentTimeMillis();
            for (File file : files) {
                if (file.getName().endsWith(".tmp") || now - file.lastModified() >= MAX_AGE) file.delete();
                else size += file.length();
            }
            for (File file : files) {
                if (size <= MAX_SIZE) break;
                long length = file.length();
                if (file.delete()) size -= length;
            }
        }
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES || Thread.currentThread().isInterrupted()) return null;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static boolean decodeArt(Context context, Item item, byte[] data) {
        if (data == null) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return false;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 640
                || options.outHeight / options.inSampleSize > 360) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) return false;
        item.banner = new BitmapDrawable(context.getResources(), bitmap);
        return true;
    }

    static final class ArtworkLoader {
        private final ExecutorService cacheIo = Executors.newSingleThreadExecutor();
        private final ExecutorService sourceIo = Executors.newFixedThreadPool(2);

        void execute(BooleanSupplier cached, Runnable source, Runnable completed) {
            cacheIo.execute(() -> {
                if (cached.getAsBoolean()) {
                    completed.run();
                    return;
                }
                try {
                    sourceIo.execute(() -> {
                        source.run();
                        completed.run();
                    });
                } catch (RejectedExecutionException ignored) {
                }
            });
        }

        void shutdownNow() {
            cacheIo.shutdownNow();
            sourceIo.shutdownNow();
        }
    }

    static boolean cachedArt(Context context, Item item) {
        if (item.poster == null) return false;
        long started = System.nanoTime();
        try {
            ArtworkCache cache = new ArtworkCache(context.getCacheDir());
            File cached = cache.entry(item.owner, item.poster);
            byte[] data = cache.read(cached);
            if (data == null) return false;
            if (decodeArt(context, item, data)) {
                Log.i("RecommendationArt", "disk hit " + (System.nanoTime() - started) / 1000000 + "ms");
                return true;
            }
            cached.delete();
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    static void art(Context context, Item item) {
        if (item.poster == null) return;
        long started = System.nanoTime();
        String outcome = "source unavailable";
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(item.poster);
            String scheme = uri.getScheme();
            if (!"content".equals(scheme) && !"android.resource".equals(scheme)
                    && !"https".equals(scheme) && !"http".equals(scheme)) return;
            ArtworkCache cache = new ArtworkCache(context.getCacheDir());
            File cached = cache.entry(item.owner, item.poster);
            byte[] stored = cache.read(cached);
            if (stored != null) {
                if (decodeArt(context, item, stored)) {
                    outcome = "disk hit";
                    return;
                }
                cached.delete();
            }
            InputStream stream;
            if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                stream = context.getContentResolver().openInputStream(uri);
            } else if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                connection = (HttpURLConnection) new URL(item.poster).openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setInstanceFollowRedirects(false);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                stream = connection.getInputStream();
            } else return;
            if (stream == null) return;
            byte[] data;
            outcome = "source read failed";
            try (InputStream input = stream) {
                data = readBytes(input);
            }
            if (data == null) {
                outcome = "source oversized or cancelled";
                return;
            }
            outcome = "source decode failed";
            if (decodeArt(context, item, data)) {
                cache.write(cached, data);
                outcome = cached.isFile() ? "source cached" : "source cache write failed";
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            if (connection != null) connection.disconnect();
            Log.i("RecommendationArt", outcome + " " + (System.nanoTime() - started) / 1000000 + "ms");
        }
    }
}
