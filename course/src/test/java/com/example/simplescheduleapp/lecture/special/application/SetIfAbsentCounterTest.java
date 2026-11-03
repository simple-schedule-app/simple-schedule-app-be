package com.example.simplescheduleapp.lecture.special.application;

import com.example.simplescheduleapp.common.redis.counter.AtomicCounter;
import com.example.simplescheduleapp.support.ApplicationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code setIfAbsent}의 NX 의미를 <b>실제 Redis에서</b> 검증한다.
 *
 * <p>목으로는 "있으면 건드리지 않는다"를 증명할 수 없다. 이 성질이 깨지면 복구가 살아 있는
 * 카운터를 덮어써 초과 판매가 되므로, 스크립트 테스트와 같은 이유로 실 Redis를 쓴다
 * (CI는 redis 서비스 컨테이너, 로컬은 {@code docker compose up -d}의 6379).
 */
@DisplayName("AtomicCounter.setIfAbsent 은(는)")
class SetIfAbsentCounterTest extends ApplicationTest {

    @Autowired
    private AtomicCounter atomicCounter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String key = "test:nx:" + UUID.randomUUID();

    @Test
    @DisplayName("없으면 값과 TTL을 넣고 true, 있으면 아무것도 바꾸지 않고 false")
    void sets_only_when_absent() {
        // when
        boolean first = atomicCounter.setIfAbsent(key, 18L, Duration.ofMinutes(10));
        // 살아 있는 카운터가 이미 감소한 상태를 흉내 낸다
        atomicCounter.decrement(key);
        boolean second = atomicCounter.setIfAbsent(key, 99L, Duration.ofMinutes(10));

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("17");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 600L);

        redisTemplate.delete(key);
    }
}
