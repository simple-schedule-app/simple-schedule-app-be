package com.example.simplescheduleapp.lecture.special.application;

import com.example.simplescheduleapp.lecture.special.domain.SpecialLecture;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureEnrollmentRepository;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureRepository;
import com.example.simplescheduleapp.support.MockTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 정원 키 복구의 판단 규칙을 고정한다.
 *
 * <p>Redis 쪽 원자성(SET NX)은 {@code SpecialLectureCapacityRestoreTest}와
 * {@code SetIfAbsentCounterTest}가 본다. 여기서는 "어떤 특강을, 어떤 값으로, 실패를 어떻게 격리해"
 * 복구하는지만 본다.
 */
@DisplayName("SpecialLectureCapacityReconciler 은(는)")
class SpecialLectureCapacityReconcilerTest extends MockTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);

    @Mock
    SpecialLectureRepository specialLectureRepository;

    @Mock
    SpecialLectureEnrollmentRepository specialLectureEnrollmentRepository;

    @Mock
    SpecialLectureRedisClient specialLectureRedisClient;

    @InjectMocks
    SpecialLectureCapacityReconciler sut;

    private static SpecialLecture lecture(long id, int capacity) {
        return SpecialLecture.reconstitute(id, 0L, "특강 " + id, NOW.plusDays(1), NOW.plusDays(2), null, 1L, capacity);
    }

    @Test
    @DisplayName("진행 중인 특강의 남은 자리를 정원에서 신청 수를 뺀 값으로 되살린다")
    void restores_remaining_seats_from_db() {
        // given
        SpecialLecture lecture = lecture(10L, 30);
        given(specialLectureRepository.findAllNotEndedAsOf(NOW)).willReturn(List.of(lecture));
        given(specialLectureEnrollmentRepository.countBySpecialLectureId(10L)).willReturn(12L);
        given(specialLectureRedisClient.restoreCapacityIfAbsent(10L, 18L, lecture.getEndTime())).willReturn(true);

        // when
        SpecialLectureCapacityReconciler.Result result = sut.reconcile(NOW);

        // then
        assertThat(result).isEqualTo(new SpecialLectureCapacityReconciler.Result(1, 1, 0));
        verify(specialLectureRedisClient).restoreCapacityIfAbsent(10L, 18L, lecture.getEndTime());
    }

    @Test
    @DisplayName("정원 키가 이미 있으면 복구로 세지 않는다 (값은 건드리지 않는다)")
    void existing_key_is_not_counted_as_restored() {
        // given
        SpecialLecture lecture = lecture(10L, 30);
        given(specialLectureRepository.findAllNotEndedAsOf(NOW)).willReturn(List.of(lecture));
        given(specialLectureEnrollmentRepository.countBySpecialLectureId(10L)).willReturn(0L);
        given(specialLectureRedisClient.restoreCapacityIfAbsent(anyLong(), anyLong(), any())).willReturn(false);

        // when
        SpecialLectureCapacityReconciler.Result result = sut.reconcile(NOW);

        // then
        assertThat(result).isEqualTo(new SpecialLectureCapacityReconciler.Result(1, 0, 0));
    }

    @Test
    @DisplayName("진행 중인 특강이 없으면 Redis를 건드리지 않는다")
    void nothing_in_progress_touches_nothing() {
        // given
        given(specialLectureRepository.findAllNotEndedAsOf(NOW)).willReturn(List.of());

        // when
        SpecialLectureCapacityReconciler.Result result = sut.reconcile(NOW);

        // then
        assertThat(result).isEqualTo(new SpecialLectureCapacityReconciler.Result(0, 0, 0));
        verify(specialLectureRedisClient, never()).restoreCapacityIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("한 특강의 복구가 실패해도 나머지 특강은 계속 복구한다")
    void one_failure_does_not_stop_the_rest() {
        // given
        SpecialLecture broken = lecture(1L, 10);
        SpecialLecture healthy = lecture(2L, 20);
        given(specialLectureRepository.findAllNotEndedAsOf(NOW)).willReturn(List.of(broken, healthy));
        given(specialLectureEnrollmentRepository.countBySpecialLectureId(1L)).willThrow(new IllegalStateException("db down"));
        given(specialLectureEnrollmentRepository.countBySpecialLectureId(2L)).willReturn(5L);
        given(specialLectureRedisClient.restoreCapacityIfAbsent(eq(2L), eq(15L), any())).willReturn(true);

        // when
        SpecialLectureCapacityReconciler.Result result = sut.reconcile(NOW);

        // then
        assertThat(result).isEqualTo(new SpecialLectureCapacityReconciler.Result(2, 1, 1));
        verify(specialLectureRedisClient).restoreCapacityIfAbsent(eq(2L), eq(15L), any());
    }
}
