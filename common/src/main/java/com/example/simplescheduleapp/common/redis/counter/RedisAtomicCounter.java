package com.example.simplescheduleapp.common.redis.counter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
@Component
public class RedisAtomicCounter implements AtomicCounter {

    /**
     * 원자적 check-and-decrement — 키 없음(-2)과 소진(-1)을 구분해 반환.
     */
    private static final DefaultRedisScript<Long> DECREMENT_IF_POSITIVE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('get', KEYS[1])
            if current == false then return -2 end
            if tonumber(current) <= 0 then return -1 end
            return redis.call('decr', KEYS[1])
            """,
            Long.class
    );

    /**
     * 마감 검사 + 조건부 감소. <b>마감을 먼저 본다</b> — 마감된 특강에 자리가 남아 있어도
     * "자리 없음"이 아니라 "마감됨"이 나가야 한다.
     *
     * <p>마감 키가 없으면(이 기능 이전에 만들어진 키) 검사를 건너뛰고 기존 동작을 유지한다.
     */
    private static final DefaultRedisScript<Long> DECREMENT_IF_POSITIVE_BEFORE_SCRIPT = new DefaultRedisScript<>(
            """
            local deadline = redis.call('get', KEYS[2])
            if deadline ~= false and tonumber(deadline) <= tonumber(ARGV[1]) then return -3 end
            local current = redis.call('get', KEYS[1])
            if current == false then return -2 end
            if tonumber(current) <= 0 then return -1 end
            return redis.call('decr', KEYS[1])
            """,
            Long.class
    );

    /**
     * 키가 존재할 때만 증가 — 만료 키를 TTL 없이 부활시키지 않는다.
     */
    private static final DefaultRedisScript<Long> INCREMENT_IF_EXISTS_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('get', KEYS[1])
            if current == false then return -2 end
            return redis.call('incr', KEYS[1])
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void set(String key, long value) {
        stringRedisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    @Override
    public void set(String key, long value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        // SET key value EX seconds — 원자적 SET + EXPIRE
        stringRedisTemplate.opsForValue().set(key, String.valueOf(value), ttl);
    }

    @Override
    public boolean setIfAbsent(String key, long value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        // SET key value NX EX seconds — 존재 검사와 설정이 한 명령이라 생성 경로와 경합해도 둘 중 하나만 쓴다
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(value), ttl));
    }

    @Override
    public Long decrement(String key) {
        return stringRedisTemplate.opsForValue().decrement(key);
    }

    @Override
    public Long increment(String key) {
        return stringRedisTemplate.opsForValue().increment(key);
    }

    @Override
    public Long decrementIfPositive(String key) {
        return stringRedisTemplate.execute(DECREMENT_IF_POSITIVE_SCRIPT, List.of(key));
    }

    @Override
    public Long decrementIfPositiveBefore(String key, String deadlineKey, long nowEpochSecond) {
        return stringRedisTemplate.execute(
                DECREMENT_IF_POSITIVE_BEFORE_SCRIPT, List.of(key, deadlineKey), String.valueOf(nowEpochSecond));
    }

    @Override
    public Long incrementIfExists(String key) {
        return stringRedisTemplate.execute(INCREMENT_IF_EXISTS_SCRIPT, List.of(key));
    }
}
