package com.github.prepushchecker.stats;

/** Where shared pre-push counts go. Blank disables uploads (counts stay local). */
final class StatsEndpoint {
    /**
     * Base URL of the Catalyst {@code prepush-stats} function. {@code /report} receives counts,
     * {@code /totals} serves the aggregate shown on the GitHub page. See {@code stats-backend/}.
     */
    static final String BASE_URL =
        "https://crmqaautomation-60066298984.development.catalystserverless.in/server/prepush_stats";

    private StatsEndpoint() {
    }

    static boolean isConfigured() {
        return !BASE_URL.isBlank();
    }

    static String reportUrl() {
        return BASE_URL.endsWith("/") ? BASE_URL + "report" : BASE_URL + "/report";
    }
}
