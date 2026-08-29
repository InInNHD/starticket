import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderRace {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        if (args.length == 6) {
            burst(args);
            return;
        }
        if (args.length != 9) {
            throw new IllegalArgumentException("""
                    突发模式: BASE_URL TOKENS PERFORMANCE_ID SEAT_ID ITERATIONS OUTPUT_JSON
                    持续模式: BASE_URL TOKENS PERFORMANCE_ID SEATS CONCURRENCY WARMUP_SECONDS DURATION_SECONDS HOTSPOT|SPREAD OUTPUT_JSON
                    TOKENS 和 SEATS 支持逗号分隔或 @文件路径。持续模式至少需要 2 * CONCURRENCY 个令牌。""");
        }
        sustained(args);
    }

    private static void burst(String[] args) throws Exception {
        String baseUrl = args[0], output = args[5];
        List<String> tokens = values(args[1]);
        long performanceId = Long.parseLong(args[2]), seatId = Long.parseLong(args[3]);
        int iterations = Integer.parseInt(args[4]);
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
                    Result result = order(baseUrl, tokens.get(index % tokens.size()), performanceId, seatId, index);
                    statuses[index] = result.status();
                    durations[index] = result.nanos();
                    return null;
                });
            }
            ready.await();
            wallStarted = System.nanoTime();
            start.countDown();
            for (Future<Void> future : futures) future.get();
        }
        double wallSeconds = (System.nanoTime() - wallStarted) / 1_000_000_000d;
        write(output, summary("BURST", iterations, wallSeconds, durations, statuses, 0, 0));
    }

    private static void sustained(String[] args) throws Exception {
        String baseUrl = args[0], output = args[8], mode = args[7].toUpperCase();
        List<String> tokens = values(args[1]);
        long performanceId = Long.parseLong(args[2]);
        List<Long> seats = values(args[3]).stream().map(Long::parseLong).toList();
        int concurrency = Integer.parseInt(args[4]);
        int warmupSeconds = Integer.parseInt(args[5]);
        int durationSeconds = Integer.parseInt(args[6]);
        if (!List.of("HOTSPOT", "SPREAD").contains(mode)) throw new IllegalArgumentException("模式必须为 HOTSPOT 或 SPREAD");
        if (tokens.size() < concurrency * 2) throw new IllegalArgumentException("持续模式至少需要 " + concurrency * 2 + " 个令牌");
        if (seats.isEmpty()) throw new IllegalArgumentException("至少需要一个座位");

        AtomicInteger seatCursor = new AtomicInteger();
        int warmupRequests = runWindow(baseUrl, tokens.subList(0, concurrency), performanceId, seats, mode,
                concurrency, warmupSeconds, seatCursor, null).size();
        seatCursor.set(0);
        long started = System.nanoTime();
        List<Result> results = runWindow(baseUrl, tokens.subList(concurrency, concurrency * 2), performanceId,
                seats, mode, concurrency, durationSeconds, seatCursor, new ConcurrentLinkedQueue<>());
        double wallSeconds = (System.nanoTime() - started) / 1_000_000_000d;
        long[] durations = results.stream().mapToLong(Result::nanos).toArray();
        int[] statuses = results.stream().mapToInt(Result::status).toArray();
        write(output, summary(mode, concurrency, wallSeconds, durations, statuses, warmupSeconds, warmupRequests));
    }

    private static List<Result> runWindow(String baseUrl, List<String> tokens, long performanceId,
                                           List<Long> seats, String mode, int concurrency, int seconds,
                                           AtomicInteger seatCursor, ConcurrentLinkedQueue<Result> supplied) throws Exception {
        ConcurrentLinkedQueue<Result> results = supplied == null ? new ConcurrentLinkedQueue<>() : supplied;
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        CountDownLatch ready = new CountDownLatch(concurrency), start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < concurrency; worker++) {
                int index = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int request = 0;
                    while (System.nanoTime() < deadline) {
                        long seat = "HOTSPOT".equals(mode) ? seats.get(0)
                                : seats.get(Math.floorMod(seatCursor.getAndIncrement(), seats.size()));
                        results.add(order(baseUrl, tokens.get(index), performanceId, seat, request++));
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) future.get();
        }
        return new ArrayList<>(results);
    }

    private static Result order(String baseUrl, String token, long performanceId, long seatId, int index) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/orders"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "load-" + UUID.randomUUID() + "-" + index)
                .POST(HttpRequest.BodyPublishers.ofString("{\"performanceId\":" + performanceId
                        + ",\"seatIds\":[" + seatId + "]}"))
                .timeout(Duration.ofSeconds(15)).build();
        long began = System.nanoTime();
        try {
            return new Result(CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(),
                    System.nanoTime() - began);
        } catch (Exception exception) {
            return new Result(0, System.nanoTime() - began);
        }
    }

    private static String summary(String mode, int concurrency, double wallSeconds, long[] durations,
                                  int[] statuses, int warmupSeconds, int warmupRequests) {
        Arrays.sort(durations);
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int status : statuses) counts.merge(status, 1, Integer::sum);
        long total = Arrays.stream(durations).sum();
        int technicalErrors = (int) Arrays.stream(statuses)
                .filter(status -> status != 201 && status != 409 && status != 429).count();
        int requests = durations.length;
        return """
                {"recordedAt":"%s","mode":"%s","concurrency":%d,"warmupSeconds":%d,"warmupRequests":%d,
                 "durationSeconds":%.3f,"requests":%d,"throughput":%.2f,"avgMs":%.2f,"p95Ms":%.2f,"p99Ms":%.2f,
                 "technicalErrorRate":%.4f,"statuses":%s}
                """.formatted(Instant.now(), mode, concurrency, warmupSeconds, warmupRequests, wallSeconds,
                requests, requests / wallSeconds, requests == 0 ? 0 : total / 1_000_000d / requests,
                percentile(durations, 0.95), percentile(durations, 0.99),
                requests == 0 ? 0 : technicalErrors / (double) requests, statusesJson(counts))
                .replace(System.lineSeparator(), "");
    }

    private static List<String> values(String argument) throws Exception {
        String content = argument.startsWith("@") ? Files.readString(Path.of(argument.substring(1))) : argument;
        return Arrays.stream(content.split("[,\\r\\n]+"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static String statusesJson(Map<Integer, Integer> statuses) {
        StringBuilder json = new StringBuilder("{");
        statuses.forEach((status, count) -> {
            if (json.length() > 1) json.append(',');
            json.append('"').append(status).append("\":").append(count);
        });
        return json.append('}').toString();
    }

    private static double percentile(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * sortedNanos.length) - 1);
        return sortedNanos[index] / 1_000_000d;
    }

    private static void write(String output, String json) throws Exception {
        Path path = Path.of(output);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, json + System.lineSeparator());
        System.out.println(json);
    }

    private static void selfTest() throws Exception {
        if (!values("1,2\n3").equals(List.of("1", "2", "3"))) throw new AssertionError("values");
        if (!"{\"201\":1,\"409\":2}".equals(statusesJson(new TreeMap<>(Map.of(201, 1, 409, 2)))))
            throw new AssertionError("statusesJson");
        if (percentile(new long[]{1_000_000, 2_000_000, 3_000_000}, 0.95) != 3) throw new AssertionError("percentile");
        System.out.println("OrderRace self-test passed");
    }

    private record Result(int status, long nanos) {}
}
