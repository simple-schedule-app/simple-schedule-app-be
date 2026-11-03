package com.example.simplescheduleapp.lecture.special.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * {@link SpecialLectureCapacityReconciler}를 기동 직후 한 번, 그 뒤 주기적으로 돌린다.
 *
 * <p>기동 직후 실행이 새 서버와 Redis 재시작(빈 Redis)을 덮고, 주기 실행이 운영 중 축출을 덮는다.
 * 주기는 {@code ssa.special-lecture.capacity-reconcile.fixed-delay-ms}(기본 10분)로 바꿀 수 있다.
 * 스케줄링 활성화({@code @EnableScheduling})는 common의 {@code SchedulingRetryConfig}가 담당한다.
 *
 * <p>인스턴스가 하나라는 전제다(운영 compose의 {@code ssa-app} 하나). 여럿이 되더라도 SET NX라
 * 중복 실행이 값을 망치지는 않고, 같은 일을 두 번 할 뿐이다.
 *
 * <p>예외를 밖으로 내지 않는다. 스케줄 메서드가 예외를 던지면 로그만 남고 다음 주기는 돌지만,
 * 기동 직후 실행에서 DB나 Redis가 아직 준비 전이라면 그것은 복구 대상이지 기동 실패 사유가 아니다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SpecialLectureCapacityReconcileScheduler {

    private final SpecialLectureCapacityReconciler reconciler;

    @Scheduled(
            initialDelayString = "${ssa.special-lecture.capacity-reconcile.initial-delay-ms:0}",
            fixedDelayString = "${ssa.special-lecture.capacity-reconcile.fixed-delay-ms:600000}")
    public void reconcile() {
        try {
            SpecialLectureCapacityReconciler.Result result = reconciler.reconcile(LocalDateTime.now());
            if (result.restored() > 0 || result.failed() > 0) {
                log.warn("특강 정원 키 복구 주기 완료. checked={}, restored={}, failed={}",
                        result.checked(), result.restored(), result.failed());
            } else {
                log.debug("특강 정원 키 복구 주기 완료. checked={}, 복구 대상 없음", result.checked());
            }
        } catch (RuntimeException e) {
            log.error("특강 정원 키 복구 주기 실패. 다음 주기에 다시 시도한다.", e);
        }
    }
}
