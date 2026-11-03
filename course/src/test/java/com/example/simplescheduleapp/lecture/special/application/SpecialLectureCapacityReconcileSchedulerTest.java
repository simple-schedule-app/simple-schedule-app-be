package com.example.simplescheduleapp.lecture.special.application;

import com.example.simplescheduleapp.support.MockTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@DisplayName("SpecialLectureCapacityReconcileScheduler 은(는)")
class SpecialLectureCapacityReconcileSchedulerTest extends MockTestSupport {

    @Mock
    SpecialLectureCapacityReconciler reconciler;

    @InjectMocks
    SpecialLectureCapacityReconcileScheduler sut;

    @Test
    @DisplayName("복구기가 예외를 던져도 스케줄 메서드 밖으로 내지 않는다 (기동과 다음 주기를 막지 않음)")
    void swallows_reconciler_failure() {
        // given
        given(reconciler.reconcile(any())).willThrow(new IllegalStateException("redis down"));

        // when / then
        assertThatCode(() -> sut.reconcile()).doesNotThrowAnyException();
        verify(reconciler).reconcile(any());
    }
}
