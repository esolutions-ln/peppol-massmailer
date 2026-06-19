package com.esolutions.watcher.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class RetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(RetryTemplate.class);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double multiplier;

    public RetryTemplate(int maxAttempts, long initialDelayMs, double multiplier) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.multiplier = multiplier;
    }

    public static RetryTemplate defaultTransient() {
        return new RetryTemplate(3, 1000, 2.0);
    }

    public interface RetryableAction<T> {
        T call() throws Exception;
    }

    public interface RetryableVoid {
        void call() throws Exception;
    }

    public <T> T execute(RetryableAction<T> action, String context) throws Exception {
        Exception lastEx = null;
        long delay = initialDelayMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastEx = e;
                if (!isTransient(e) || attempt == maxAttempts) {
                    log.warn("{} failed permanently on attempt {}/{}: {}",
                            context, attempt, maxAttempts, e.getMessage());
                    throw e;
                }
                long jitter = ThreadLocalRandom.current().nextLong(delay / 2, delay);
                log.warn("{} failed on attempt {}/{} — retrying in {}ms: {}",
                        context, attempt, maxAttempts, jitter, e.getMessage());
                try {
                    Thread.sleep(jitter);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                delay = (long) (delay * multiplier);
            }
        }
        throw lastEx;
    }

    public void executeVoid(RetryableVoid action, String context) throws Exception {
        execute(() -> { action.call(); return null; }, context);
    }

    private static boolean isTransient(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout") || msg.contains("connection")
                || msg.contains("temporarily") || msg.contains("try again")
                || msg.contains("503") || msg.contains("502")
                || msg.contains("unavailable");
    }
}
