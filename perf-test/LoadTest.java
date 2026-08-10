import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class LoadTest {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/plaintext";
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int duration = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.printf("Target: %s%n", url);
        System.out.printf("Threads: %d  Duration: %ds%n%n", threads, duration);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        // Warmup
        System.out.print("Warming up...");
        for (int i = 0; i < 5000; i++) {
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }
        System.out.println(" done");

        LongAdder totalRequests = new LongAdder();
        LongAdder totalErrors = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();
        AtomicLong maxLatencyNanos = new AtomicLong(0);

        // Collect latencies for percentile calculation
        int bucketCount = 10000;
        LongAdder[] latencyBuckets = new LongAdder[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            latencyBuckets[i] = new LongAdder();
        }

        long deadline = System.nanoTime() + (long) duration * 1_000_000_000L;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.startVirtualThread(() -> {
                try {
                    while (System.nanoTime() < deadline) {
                        long start = System.nanoTime();
                        try {
                            HttpResponse<Void> resp = client.send(request,
                                    HttpResponse.BodyHandlers.discarding());
                            if (resp.statusCode() != 200) {
                                totalErrors.increment();
                            }
                        } catch (Exception e) {
                            totalErrors.increment();
                        }
                        long elapsed = System.nanoTime() - start;
                        totalRequests.increment();
                        totalLatencyNanos.add(elapsed);

                        int bucket = (int) Math.min(elapsed / 100_000, bucketCount - 1);
                        latencyBuckets[bucket].increment();

                        long currentMax;
                        do {
                            currentMax = maxLatencyNanos.get();
                        } while (elapsed > currentMax && !maxLatencyNanos.compareAndSet(currentMax, elapsed));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Progress
        long startTime = System.nanoTime();
        while (latch.getCount() > 0) {
            Thread.sleep(1000);
            double elapsed = (System.nanoTime() - startTime) / 1e9;
            System.out.printf("\r  %.0fs: %,d requests, %,d errors",
                    elapsed, totalRequests.sum(), totalErrors.sum());
        }
        latch.await();
        double totalTime = (System.nanoTime() - startTime) / 1e9;
        System.out.println();

        long total = totalRequests.sum();
        long errors = totalErrors.sum();
        double rps = total / totalTime;
        double avgLatencyMs = (totalLatencyNanos.sum() / (double) total) / 1_000_000.0;
        double maxLatencyMs = maxLatencyNanos.get() / 1_000_000.0;

        // Calculate percentiles
        long p50 = percentile(latencyBuckets, total, 0.50);
        long p90 = percentile(latencyBuckets, total, 0.90);
        long p99 = percentile(latencyBuckets, total, 0.99);

        System.out.println();
        System.out.println("Results:");
        System.out.printf("  Requests/sec:   %,.2f%n", rps);
        System.out.printf("  Total requests: %,d%n", total);
        System.out.printf("  Errors:         %,d%n", errors);
        System.out.printf("  Avg latency:    %.2f ms%n", avgLatencyMs);
        System.out.printf("  p50 latency:    %.2f ms%n", p50 * 0.1);
        System.out.printf("  p90 latency:    %.2f ms%n", p90 * 0.1);
        System.out.printf("  p99 latency:    %.2f ms%n", p99 * 0.1);
        System.out.printf("  Max latency:    %.2f ms%n", maxLatencyMs);

        client.close();
    }

    private static long percentile(LongAdder[] buckets, long total, double pct) {
        long target = (long) (total * pct);
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) {
                return i;
            }
        }
        return buckets.length - 1;
    }
}
