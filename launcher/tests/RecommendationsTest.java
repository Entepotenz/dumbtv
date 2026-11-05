package tv.dumbtv.home;

import java.util.Arrays;

public final class RecommendationsTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
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
        System.out.println("RecommendationsTest passed");
    }
}