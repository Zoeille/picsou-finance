package com.picsou.config;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-IP/per-user bucket-store beans used to be plain {@code ConcurrentHashMap}s that only
 * ever grew: every distinct key -- fully attacker-controlled for the per-IP ones, see
 * {@link ClientIp} -- added an entry nothing ever removed. This asserts the Caffeine-backed
 * replacement actually bounds memory. See docs/features/security-cors-cookies.md
 * ("client IP trust") and {@link RateLimitConfig#loginBuckets()}.
 *
 * <p>Only {@code loginBuckets} is exercised: every {@code Map<String, Bucket>} bean is a one-line
 * delegation to the same private {@code boundedBucketStore()} factory, so this covers all of them.
 */
class RateLimitConfigTest {

    private static final int MAX_SIZE = 50_000;

    @Test
    void loginBuckets_evictsDownToMaximumSize_whenOverfilled() {
        Map<String, Bucket> buckets = new RateLimitConfig().loginBuckets();

        for (int i = 0; i < MAX_SIZE + 10_000; i++) {
            buckets.computeIfAbsent("key-" + i, k -> RateLimitConfig.createLoginBucket());
        }

        // Caffeine's eviction maintenance can run on a background executor (ForkJoinPool
        // commonPool by default), so give it a short, bounded window to catch up rather than
        // asserting immediately after the write burst -- avoids both a flaky immediate check
        // and an unbounded hang if something is genuinely wrong.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (buckets.size() > MAX_SIZE && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertThat(buckets.size()).isLessThanOrEqualTo(MAX_SIZE);
    }
}
