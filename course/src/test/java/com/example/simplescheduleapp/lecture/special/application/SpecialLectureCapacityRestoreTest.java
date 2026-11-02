package com.example.simplescheduleapp.lecture.special.application;

import com.example.simplescheduleapp.common.redis.counter.AtomicCounter;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureRepository;
import com.example.simplescheduleapp.support.MockTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SpecialLectureRedisClient#restoreCapacityIfAbsent}의 계약을 고정한다.
 *
 * <ul>
 *   <li>덮어쓰는 {@code set}이 아니라 {@code setIfAbsent}만 쓴다 (초과 판매 방지의 핵심)</li>
 *   <li>정원 키와 마감 키를 같은 TTL로 함께 채운다</li>
 *   <li>남은 자리가 음수면 0으로 둔다 (정원 축소 뒤 신청 수가 정원을 넘는 경우)</li>
 *   <li>반환값은 정원 키를 새로 만들었는지다</li>
 * </ul>
 */
@DisplayName("SpecialLectureRedisClient.restoreCapacityIfAbsent 은(는)")
class SpecialLectureCapacityRestoreTest extends MockTestSupport {

    @Mock
    AtomicCounter atomicCounter;

    @Mock
    SpecialLectureRepository specialLectureRepository;

    @InjectMocks
    SpecialLectureRedisClient sut;

    @Test
    @DisplayName("정원 키와 마감 키를 setIfAbsent로만 채우고 set은 부르지 않는다")
    void uses_set_if_absent_only() {
        // given
        LocalDateTime endTime = LocalDateTime.now().plusDays(3);
        given(atomicCounter.setIfAbsent(eq("special_lecture:7:available"), eq(18L), any(Duration.class))).willReturn(true);

        // when
        boolean created = sut.restoreCapacityIfAbsent(7L, 18L, endTime);

        // then
        assertThat(created).isTrue();
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(atomicCounter).setIfAbsent(eq("special_lecture:7:available"), eq(18L), ttl.capture());
        verify(atomicCounter).setIfAbsent(eq("special_lecture:7:deadline"), anyLong(), eq(ttl.getValue()));
        assertThat(ttl.getValue()).isBetween(Duration.ofDays(3).minusMinutes(1), Duration.ofDays(4));
        verify(atomicCounter, never()).set(anyString(), anyLong(), any(Duration.class));
        verify(atomicCounter, never()).set(anyString(), anyLong());
    }

    @Test
    @DisplayName("정원 키가 이미 있으면 false를 돌려주고 값을 바꾸지 않는다")
    void returns_false_when_key_exists() {
        // given
        given(atomicCounter.setIfAbsent(eq("special_lecture:7:available"), anyLong(), any(Duration.class))).willReturn(false);

        // when
        boolean created = sut.restoreCapacityIfAbsent(7L, 18L, LocalDateTime.now().plusDays(3));

        // then
        assertThat(created).isFalse();
        verify(atomicCounter, never()).set(anyString(), anyLong(), any(Duration.class));
    }

    @Test
    @DisplayName("남은 자리가 음수면 0으로 둔다")
    void clamps_negative_remaining_to_zero() {
        // when
        sut.restoreCapacityIfAbsent(7L, -3L, LocalDateTime.now().plusDays(3));

        // then
        verify(atomicCounter).setIfAbsent(eq("special_lecture:7:available"), eq(0L), any(Duration.class));
    }

    @Test
    @DisplayName("종료 시각이 과거면 보정 TTL(30일)을 쓴다")
    void falls_back_to_thirty_days_for_past_end_time() {
        // when
        sut.restoreCapacityIfAbsent(7L, 5L, LocalDateTime.now().minusDays(2));

        // then
        verify(atomicCounter).setIfAbsent(eq("special_lecture:7:available"), eq(5L), eq(Duration.ofDays(30)));
    }
}
