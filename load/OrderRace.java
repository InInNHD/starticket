import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class OrderRace {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("BASE_URL TOKEN PERFORMANCE_ID SEAT_ID ITERATIONS OUTPUT_JSON");
        }
        String baseUrl = args[0], output = args[5];
        String[] tokens = args[1].split(",");
        long performanceId = Long.parseLong(args[2]), seatId = Long.parseLong(args[3]);
        int iterations = Integer.parseInt(args[4]);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        CountDownLatch ready = new CountDownLatch(iterations), start = new CountDownLatch(1);
        long[] durations = new long[iterations];
        int[] statuses = new int[iterations];
        long wallStarted;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            @SuppressWarnings("unchecked") Future<Void>[] futures = new Future[iterations];
            for (int i = 0; i < iterations; i++) {
                int index = i;
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders"))
                            .header("Authorization", "Bearer " + tokens[index % tokens.length])
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "java-race-" + System.nanoTime() + "-" + index)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"performanceId\":" + performanceId
                                    + ",\"seatIds\":[" + seatId + "]}"))
                            .timeout(Duration.ofSeconds(10)).build();
                    long began = System.nanoTime();
                    try {
                        statuses[index] = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                    } catch (Exception exception) {
                        statuses[index] = 0;
                    }
                    durations[index] = System.nanoTime() - began;
                    return null;
                });
            }
            ready.await();
            wallStarted = System.nanoTime();
            start.countDown();
            for (Future<Void> future : futures) future.get();
        }
        double wallSeconds = (System.nanoTime() - wallStarted) / 1_000_000_000d;
        Arrays.sort(durations);
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int status : statuses) counts.merge(status, 1, Integer::sum);
        long total = Arrays.stream(durations).sum();
        int technicalErrors = (int) Arrays.stream(statuses).filter(s -> s != 201 && s != 409 && s != 429).count();
        String json = """
                {"iterations":%d,"throughput":%.2f,"avgMs":%.2f,"p95Ms":%.2f,"p99Ms":%.2f,
                 "technicalErrorRate":%.4f,"statuses":"%s"}
                """.formatted(iterations, iterations / wallSeconds, total / 1_000_000d / iterations,
                percentile(durations, 0.95), percentile(durations, 0.99),
                technicalErrors / (double) iterations, counts).replace(System.lineSeparator(), "");
        Files.writeString(Path.of(output), json);
        System.out.println(json);
    }

    private static double percentile(long[] sortedNanos, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sortedNanos.length) - 1);
        return sortedNanos[index] / 1_000_000d;
    }
}
