/*
 * This file is part of VelocityPteroPower, licensed under the MIT License.
 */
package de.tubyoub.velocitypteropower.util;

import de.tubyoub.velocitypteropower.api.PanelType;
import de.tubyoub.velocitypteropower.manager.ConfigurationManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks and manages API rate limit information obtained from panel responses.
 */
public class RateLimitTracker {

    private final ComponentLogger logger;
    private final ConfigurationManager configurationManager;

    private final AtomicInteger rateLimit = new AtomicInteger(60); // Default API rate limit
    private final AtomicInteger remainingRequests = new AtomicInteger(60); // Default remaining requests
    private final ReentrantLock rateLimitLock = new ReentrantLock(); // Lock for updating rate limit info

    /**
     * Constructor for RateLimitTracker.
     * @param logger The plugin's logger.
     * @param configurationManager The plugin's configuration manager.
     */
    public RateLimitTracker(ComponentLogger logger, ConfigurationManager configurationManager) {
        this.logger = logger;
        this.configurationManager = configurationManager;
    }

    /**
     * Checks if an API request can be made based on the remaining request count.
     * Uses a lock to ensure thread safety.
     *
     * @return {@code true} if a request can be made, {@code false} otherwise.
     */
    public boolean canMakeRequest() {
        if (!configurationManager.getPanelType().equals(PanelType.mcServerSoft)) {
            rateLimitLock.lock();
            try {
                boolean canMake = remainingRequests.get() > 0;
                if (!canMake) {
                    logger.debug("API request blocked due to rate limiting ({} remaining).", remainingRequests.get());
                }
                return canMake;
            } finally {
                rateLimitLock.unlock();
            }
        }
        return true;
    }

    /**
     * Updates the rate limit information based on headers from an API response.
     * Uses a lock to ensure thread-safe updates to atomic integers.
     *
     * @param response The HTTP response from the panel API.
     */
    public void updateRateLimitInfo(HttpResponse<?> response) { // Use wildcard for flexibility
        rateLimitLock.lock();
        try {
            Optional<String> limitHeader = response.headers().firstValue("x-ratelimit-limit");
            Optional<String> remainingHeader = response.headers().firstValue("x-ratelimit-remaining");

            limitHeader.ifPresent(limitStr -> {
                try {
                    rateLimit.set(Integer.parseInt(limitStr));
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse X-RateLimit-Limit header: {}", limitStr);
                }
            });

            remainingHeader.ifPresent(remainingStr -> {
                try {
                    remainingRequests.set(Integer.parseInt(remainingStr));
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse X-RateLimit-Remaining header: {}", remainingStr);
                }
            });

            if (configurationManager.isPrintRateLimit()) {
                logger.info("Rate limit updated: Limit: {}, Remaining: {}", rateLimit.get(), remainingRequests.get());
            }

        } finally {
            rateLimitLock.unlock();
        }
    }

    /**
     * Gets the current remaining request count.
     * @return The number of remaining requests.
     */
    public int getRemainingRequests() {
        return remainingRequests.get();
    }

    /**
     * Gets the current rate limit per window.
     * @return The rate limit.
     */
    public int getRateLimit() {
        return rateLimit.get();
    }
}
