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
        if (args.length == 7 && "--event-details".equals(args[0])) {
            eventDetails(args);
            return;
        }
        if (args.length == 9 && "--inventory".equals(args[0])) {
            inventory(args);
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
                    活动详情: --event-details BASE_URL EVENT_ID CONCURRENCY WARMUP_SECONDS DURATION_SECONDS OUTPUT_JSON
                    固定库存: --inventory BASE_URL TOKENS PERFORMANCE_ID SEATS CONCURRENCY REQUESTS SINGLE|LIMITED|SPREAD OUTPUT_JSON
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
        write(output, summary("BURST", iterations, wallSeconds, durations, statuses, 0, 0, 201, 409, 429));
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
        write(output, summary(mode, concurrency, wallSeconds, durations, statuses,
                warmupSeconds, warmupRequests, 201, 409, 429));
    }

    private static void eventDetails(String[] args) throws Exception {
        String baseUrl = args[1], output = args[6];
        long eventId = Long.parseLong(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        int warmupSeconds = Integer.parseInt(args[4]);
        int durationSeconds = Integer.parseInt(args[5]);
        int warmupRequests = runEventWindow(baseUrl, eventId, concurrency, warmupSeconds).size();
        long started = System.nanoTime();
        List<Result> results = runEventWindow(baseUrl, eventId, concurrency, durationSeconds);
        double wallSeconds = (System.nanoTime() - started) / 1_000_000_000d;
        long[] durations = results.stream().mapToLong(Result::nanos).toArray();
        int[] statuses = results.stream().mapToInt(Result::status).toArray();
        write(output, summary("EVENT_DETAILS", concurrency, wallSeconds, durations, statuses,
                warmupSeconds, warmupRequests, 200));
    }

    private static void inventory(String[] args) throws Exception {
        String baseUrl = args[1], output = args[8], scenario = args[7].toUpperCase();
        List<String> tokens = values(args[2]);
        long performanceId = Long.parseLong(args[3]);
        List<Long> seats = values(args[4]).stream().map(Long::parseLong).toList();
        int concurrency = Integer.parseInt(args[5]), requests = Integer.parseInt(args[6]);
        if (!List.of("SINGLE", "LIMITED", "SPREAD").contains(scenario)) {
            throw new IllegalArgumentException("固定库存场景必须为 SINGLE、LIMITED 或 SPREAD");
        }
        if (requests < 1 || concurrency < 1) throw new IllegalArgumentException("并发数和请求数必须大于0");
        if (tokens.size() < requests) throw new IllegalArgumentException("固定库存模式需要每个请求使用独立令牌");
        if (seats.isEmpty()) throw new IllegalArgumentException("至少需要一个座位");

        AtomicInteger cursor = new AtomicInteger();
        Result[] results = new Result[requests];
        int workers = Math.min(concurrency, requests);
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        long wallStarted;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int index; (index = cursor.getAndIncrement()) < requests; ) {
                        long seatId = "SINGLE".equals(scenario) ? seats.getFirst()
                                : seats.get(index % seats.size());
                        results[index] = order(baseUrl, tokens.get(index), performanceId, seatId, index);
                    }
                    return null;
                }));
            }
            ready.await();
            wallStarted = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) future.get();
        }
        double wallSeconds = (System.nanoTime() - wallStarted) / 1_000_000_000d;
        write(output, summary(scenario, concurrency, wallSeconds,
                Arrays.stream(results).mapToLong(Result::nanos).toArray(),
                Arrays.stream(results).mapToInt(Result::status).toArray(), 0, 0, 201, 409, 429));
    }

    private static List<Result> runEventWindow(String baseUrl, long eventId,
                                                int concurrency, int seconds) throws Exception {
        ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        CountDownLatch ready = new CountDownLatch(concurrency), start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < concurrency; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    while (System.nanoTime() < deadline) results.add(eventDetails(baseUrl, eventId));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) future.get();
        }
        return new ArrayList<>(results);
    }

    private static Result eventDetails(String baseUrl, long eventId) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/events/" + eventId))
                .header("Accept", "application/json").GET().timeout(Duration.ofSeconds(15)).build();
        long began = System.nanoTime();
        try {
            return new Result(CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode(),
                    System.nanoTime() - began);
        } catch (Exception exception) {
            return new Result(0, System.nanoTime() - began);
        }
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
                                  int[] statuses, int warmupSeconds, int warmupRequests,
                                  int... acceptedStatuses) {
        Arrays.sort(durations);
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int status : statuses) counts.merge(status, 1, Integer::sum);
        long total = Arrays.stream(durations).sum();
        int technicalErrors = (int) Arrays.stream(statuses)
                .filter(status -> Arrays.stream(acceptedStatuses).noneMatch(accepted -> accepted == status)).count();
        int requests = durations.length;
        int success = counts.getOrDefault(201, 0);
        int conflicts = counts.getOrDefault(409, 0);
        int rateLimited = counts.getOrDefault(429, 0);
        int serverErrors = counts.entrySet().stream()
                .filter(entry -> entry.getKey() >= 500).mapToInt(Map.Entry::getValue).sum();
        int transportErrors = counts.getOrDefault(0, 0);
        return """
                {"recordedAt":"%s","mode":"%s","concurrency":%d,"warmupSeconds":%d,"warmupRequests":%d,
                 "durationSeconds":%.3f,"requests":%d,"throughput":%.2f,"avgMs":%.2f,"p95Ms":%.2f,"p99Ms":%.2f,
                 "successCount":%d,"conflictCount":%d,"rateLimitedCount":%d,"serverErrorCount":%d,"transportErrorCount":%d,
                 "successThroughput":%.2f,"conflictThroughput":%.2f,"technicalErrorRate":%.4f,"statuses":%s}
                """.formatted(Instant.now(), mode, concurrency, warmupSeconds, warmupRequests, wallSeconds,
                requests, requests / wallSeconds, requests == 0 ? 0 : total / 1_000_000d / requests,
                percentile(durations, 0.95), percentile(durations, 0.99),
                success, conflicts, rateLimited, serverErrors, transportErrors,
                success / wallSeconds, conflicts / wallSeconds,
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
        String eventSummary = summary("EVENT_DETAILS", 2, 1, new long[]{1_000_000, 2_000_000},
                new int[]{200, 500}, 0, 0, 200);
        if (!eventSummary.contains("\"technicalErrorRate\":0.5000")) throw new AssertionError("acceptedStatuses");
        String orderSummary = summary("LIMITED", 2, 2, new long[]{1_000_000, 2_000_000},
                new int[]{201, 409}, 0, 0, 201, 409, 429);
        if (!orderSummary.contains("\"successThroughput\":0.50")
                || !orderSummary.contains("\"conflictThroughput\":0.50")) throw new AssertionError("throughputBreakdown");
        System.out.println("OrderRace self-test passed");
    }

    private record Result(int status, long nanos) {}
}
