package com.example.simplescheduleapp.lecture.special.application;

import com.example.simplescheduleapp.lecture.special.domain.SpecialLecture;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureEnrollmentRepository;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 진행 중인 특강의 Redis 정원 키가 없으면 DB 기준으로 되살린다.
 *
 * <h2>왜 필요한가</h2>
 * 정원 키는 특강 생성 시 한 번만 만들어진다({@link SpecialLectureService#createSpecialLecture}).
 * 그 뒤 Redis가 비면(서버 교체, 볼륨 유실, {@code allkeys-lru} 축출) 진행 중인 특강의 신청이 전부
 * {@code SL002}로 실패하고, 되돌릴 경로가 없었다(HANDOFF 2026-10-20 §5-2). 이 클래스가 그 경로다.
 *
 * <h2>무엇만 하는가</h2>
 * <b>없는 키만 채운다.</b> 있는 키의 값은 보지도 고치지도 않는다. 살아 있는 카운터는 진행 중인
 * 신청과 보상이 섞여 있어, 그 값을 DB와 맞추려 들면 경합 창이 열린다. 키가 없는 동안에는 신청이
 * 1차 방어선에서 전부 거절되므로({@code KEY_NOT_FOUND}), "없음을 확인하고 SET NX로 채운다"는
 * 어떤 신청과도 겹치지 않는다. 그 사이의 취소는 자리를 하나 덜 돌려줄 뿐 초과 판매는 아니다.
 *
 * <h2>실패 격리</h2>
 * 특강 하나의 실패(DB, Redis)가 나머지의 복구를 막지 않는다. 결과는 {@link Result}로 돌려주고
 * 호출자(스케줄러)가 요약 로그를 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SpecialLectureCapacityReconciler {

    private final SpecialLectureRepository specialLectureRepository;
    private final SpecialLectureEnrollmentRepository specialLectureEnrollmentRepository;
    private final SpecialLectureRedisClient specialLectureRedisClient;

    /**
     * @param now 기준 시각. 이 시각에 끝나지 않은 특강만 본다
     * @return 검사, 복구, 실패 건수
     */
    public Result reconcile(LocalDateTime now) {
        List<SpecialLecture> inProgress = specialLectureRepository.findAllNotEndedAsOf(now);
        int restored = 0;
        int failed = 0;
        for (SpecialLecture lecture : inProgress) {
            try {
                if (restoreIfMissing(lecture)) {
                    restored++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("특강 정원 키 복구 실패. 다음 주기에 다시 시도한다. specialLectureId={}", lecture.getId(), e);
            }
        }
        return new Result(inProgress.size(), restored, failed);
    }

    private boolean restoreIfMissing(SpecialLecture lecture) {
        long enrolled = specialLectureEnrollmentRepository.countBySpecialLectureId(lecture.getId());
        long remaining = lecture.getCapacity() - enrolled;
        boolean created = specialLectureRedisClient.restoreCapacityIfAbsent(lecture.getId(), remaining, lecture.getEndTime());
        if (created) {
            // WARN이다. 복구 자체는 정상 동작이지만, 키가 없어진 원인(축출, 유실)은 운영이 알아야 한다.
            log.warn("진행 중인 특강의 Redis 정원 키가 없어 DB 기준으로 되살렸다. specialLectureId={}, capacity={}, enrolled={}, remaining={}",
                    lecture.getId(), lecture.getCapacity(), enrolled, Math.max(remaining, 0L));
        }
        return created;
    }

    /** 한 번의 복구 결과. {@code checked}는 진행 중 특강 수, 나머지는 그중 되살린 수와 실패한 수다. */
    public record Result(int checked, int restored, int failed) {
    }
}
