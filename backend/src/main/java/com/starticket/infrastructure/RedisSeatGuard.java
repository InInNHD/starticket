package com.starticket.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
public class RedisSeatGuard {

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            for i, key in ipairs(KEYS) do
              if redis.call('exists', key) == 1 then return 0 end
            end
            for i, key in ipairs(KEYS) do
              redis.call('set', key, ARGV[1], 'PX', ARGV[2])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local released = 0
            for i, key in ipairs(KEYS) do
              if redis.call('get', key) == ARGV[1] then
                redis.call('del', key)
                released = released + 1
              end
            end
            return released
            """, Long.class);
    private static final DefaultRedisScript<Long> RATE_LIMIT = new DefaultRedisScript<>("""
            local value = redis.call('incr', KEYS[1])
            if value == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end
            return value
            """, Long.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean prelockEnabled;
    private final int rateLimitMax;
    private final long rateLimitWindowMillis;

    public RedisSeatGuard(StringRedisTemplate redis, MeterRegistry meters,
                          @Value("${app.order.redis-prelock.enabled:false}") boolean prelockEnabled,
                          @Value("${app.order.rate-limit.max:10}") int rateLimitMax,
                          @Value("${app.order.rate-limit.window:PT10S}") Duration rateLimitWindow) {
        this.redis = redis;
        this.meters = meters;
        this.prelockEnabled = prelockEnabled;
        this.rateLimitMax = rateLimitMax;
        this.rateLimitWindowMillis = rateLimitWindow.toMillis();
    }

    public boolean acquire(long performanceId, List<Long> seatIds, String token, Duration ttl) {
        if (!prelockEnabled) return true;
        try {
            Long result = redis.execute(ACQUIRE, keys(performanceId, seatIds), token, String.valueOf(ttl.toMillis()));
            if (result == null || result != 1) meters.counter("starticket.redis.seat.lock.failed").increment();
            return result != null && result == 1;
        } catch (RuntimeException unavailable) {
            // Redis 只负责削峰，故障时回退到 MySQL 条件更新保证正确性。
            meters.counter("starticket.redis.degraded", "operation", "seat-lock").increment();
            return true;
        }
    }

    public void release(long performanceId, List<Long> seatIds, String token) {
        if (!prelockEnabled) return;
        try { redis.execute(RELEASE, keys(performanceId, seatIds), token); }
        catch (RuntimeException ignored) { }
    }

    public boolean allowOrder(long userId) {
        try {
            Long count = redis.execute(RATE_LIMIT, List.of("rate:order:" + userId),
                    String.valueOf(rateLimitWindowMillis));
            if (count != null && count > rateLimitMax) meters.counter("starticket.order.rate.rejected").increment();
            return count != null && count <= rateLimitMax;
        } catch (RuntimeException unavailable) {
            meters.counter("starticket.redis.degraded", "operation", "rate-limit").increment();
            return true;
        }
    }

    private static List<String> keys(long performanceId, List<Long> seatIds) {
        return seatIds.stream().map(seatId -> "seat:lock:" + performanceId + ":" + seatId).toList();
    }
}
