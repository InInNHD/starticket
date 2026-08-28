package com.starticket.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "app.infrastructure.enabled", havingValue = "true")
class RedisEventCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    RedisEventCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    Optional<EventView> get(Long eventId) {
        try {
            String value = redis.opsForValue().get(key(eventId));
            if (value == null) return Optional.empty();
            return Optional.of(json.readValue(value, EventView.class));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    void put(EventView event) {
        try {
            redis.opsForValue().set(key(event.id()), json.writeValueAsString(event), Duration.ofMinutes(5));
        } catch (Exception ignored) {
            // 缓存失败不影响数据库查询结果。
        }
    }

    void evict(Long eventId) {
        try { redis.delete(key(eventId)); }
        catch (RuntimeException ignored) { }
    }

    private static String key(Long eventId) { return "event:detail:" + eventId; }
}
