package com.example.simplescheduleapp.lecture.special.application;

import com.example.simplescheduleapp.common.exception.ApplicationException;
import com.example.simplescheduleapp.common.redis.counter.AtomicCounter;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLecture;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureRepository;
import com.example.simplescheduleapp.lecture.special.exception.SpecialLectureEnrollmentExceptionCode;
import com.example.simplescheduleapp.lecture.special.exception.SpecialLectureExceptionCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneId;
import java.time.LocalDateTime;

/**
 * 1차 방어선: Redis Atomic Counter 기반 정원 사전 필터링.
 * <p>
 * 10,000명이 정원 100명 특강에 동시 신청해도, 9,900명은 Redis 단계에서 즉시 거절된다.
 * DB까지 도달하는 요청을 정원 수준으로 줄여 DB 부하를 99% 감소시킨다.
 * <p>
 * 신청/보상은 Lua 기반 조건부 연산을 사용한다:
 * <ul>
 *   <li>키 부재(-2)를 정원 초과와 구분 — 만료·미초기화 키를 음수로 생성하지 않음</li>
 *   <li>정원 소진(-1) 시 감소 자체가 일어나지 않아 INCR 복구 왕복이 불필요</li>
 *   <li>보상은 키가 존재할 때만 증가 — 만료 키를 TTL 없이 부활시키지 않음</li>
 * </ul>
 * 정원 캐시 TTL은 특강 종료 시각 + 1일로 수명을 정렬하고, 계산 불가 시 30일로 보정한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SpecialLectureRedisClient {

    private static final String AVAILABLE_SPECIAL_LECTURE_CAPACITY_KEY_PREFIX = "special_lecture:%d:available";

    /** 신청 마감 시각(epoch second). 정원 키와 TTL을 맞춰 함께 사라진다. */
    private static final String SPECIAL_LECTURE_DEADLINE_KEY_PREFIX = "special_lecture:%d:deadline";

    /** 키 부재를 나타내는 Lua 스크립트 반환값 */
    private static final long KEY_NOT_FOUND = -2L;

    /** 정원 소진을 나타내는 Lua 스크립트 반환값 */
    private static final long CAPACITY_EXHAUSTED = -1L;

    /** 신청 마감이 지났음을 나타내는 Lua 스크립트 반환값 */
    private static final long DEADLINE_PASSED = -3L;

    /** endTime 기반 TTL을 계산할 수 없을 때(과거 시각 등)의 보정값 */
    private static final Duration FALLBACK_CAPACITY_TTL = Duration.ofDays(30);

    private final AtomicCounter atomicCounter;
    private final SpecialLectureRepository specialLectureRepository;

    // 특강 생성 시 수강 정원 설정 — TTL은 특강 종료 + 1일로 수명 정렬
    public void initializeSpecialLecture(Long specialLectureId, int capacity, LocalDateTime endTime) {
        String key = buildAvailableSpecialLectureCapacityKey(specialLectureId);
        Duration ttl = resolveCapacityTtl(endTime);
        atomicCounter.set(key, capacity, ttl);
        // 마감 시각을 함께 캐시한다 — 이것이 있어야 신청 거절이 DB를 읽지 않고 이뤄진다.
        // 1차 방어선의 존재 이유가 DB 부하를 태우지 않는 것이므로, 시간 검증도 같은 층에 둔다.
        atomicCounter.set(buildDeadlineKey(specialLectureId), toEpochSecond(endTime), ttl);
    }

    /**
     * 유실된 정원 키를 <b>없을 때만</b> 되살린다. 정원 키 복구 전용이다.
     *
     * <p>{@link #initializeSpecialLecture}와 달리 덮어쓰지 않는다. 살아 있는 카운터는 이미 신청이
     * 반영된 값이라 덮어쓰면 그만큼 초과 판매가 된다. 그래서 SET NX로 "없으면 넣고 있으면 그대로"를
     * 원자적으로 보장하고, 결과를 호출자에게 돌려준다. 마감 키도 같은 규칙으로 함께 채운다.
     *
     * @param remaining 남은 자리. 호출자가 DB로 계산한다(정원 - 신청 수). 0 미만이면 0으로 둔다
     * @return 정원 키를 이 호출이 새로 만들었으면 true. 이미 있었으면 false(아무것도 바꾸지 않음)
     */
    public boolean restoreCapacityIfAbsent(Long specialLectureId, long remaining, LocalDateTime endTime) {
        Duration ttl = resolveCapacityTtl(endTime);
        boolean created = atomicCounter.setIfAbsent(
                buildAvailableSpecialLectureCapacityKey(specialLectureId), Math.max(remaining, 0L), ttl);
        // 마감 키는 정원 키와 독립적으로 사라질 수 있어 따로 본다. 둘 다 NX라 생성 경로와 겹쳐도 안전하다.
        atomicCounter.setIfAbsent(buildDeadlineKey(specialLectureId), toEpochSecond(endTime), ttl);
        return created;
    }

    private Duration resolveCapacityTtl(LocalDateTime endTime) {
        Duration ttl = Duration.between(LocalDateTime.now(), endTime.plusDays(1));
        if (ttl.isZero() || ttl.isNegative()) {
            return FALLBACK_CAPACITY_TTL;
        }
        return ttl;
    }

    // 특강 수강 신청 시도 --> 정원 체크 및 카운트 감소 (원자적 check-and-decrement)
    public void enrollSpecialLectureEnrollment(Long specialLectureId) {
        String key = buildAvailableSpecialLectureCapacityKey(specialLectureId);
        Long result = atomicCounter.decrementIfPositiveBefore(
                key, buildDeadlineKey(specialLectureId), toEpochSecond(LocalDateTime.now()));

        if (result != null && result == DEADLINE_PASSED) {
            throw new ApplicationException(SpecialLectureEnrollmentExceptionCode.SPECIAL_LECTURE_ENDED);
        }
        if (result == null || result == KEY_NOT_FOUND) {
            throw resolveMissingKeyCause(specialLectureId);
        }
        if (result == CAPACITY_EXHAUSTED) {
            throw new ApplicationException(SpecialLectureEnrollmentExceptionCode.CAPACITY_EXCEEDED);
        }
    }

    /**
     * 정원 키가 없을 때 <b>진짜 원인을 DB로 가른다.</b>
     *
     * <p>키 부재는 원인이 셋인데 종전에는 전부 {@code SL002} 하나로 나갔다. 클라이언트는
     * "끝난 특강이라 못 신청한다"와 "서버가 정원 정보를 잃었다"를 구분할 수 없었고,
     * 후자는 알람이 울려야 하는 상황인데 정상 흐름에 섞여 묻혔다.
     *
     * <p>DB 읽기는 <b>이 비정상 경로에서만</b> 일어난다. 정상 신청은 Redis만 친다.
     */
    private ApplicationException resolveMissingKeyCause(Long specialLectureId) {
        SpecialLecture specialLecture = specialLectureRepository.findById(specialLectureId).orElse(null);
        if (specialLecture == null) {
            return new ApplicationException(SpecialLectureEnrollmentExceptionCode.SPECIAL_LECTURE_NOT_FOUND);
        }
        if (specialLecture.hasEnded(LocalDateTime.now())) {
            // 정상 상황이다 — 마감 키까지 TTL로 사라질 만큼 시간이 지났을 뿐이다.
            return new ApplicationException(SpecialLectureEnrollmentExceptionCode.SPECIAL_LECTURE_ENDED);
        }
        // 여기가 진짜 이상 상황이다. 진행 중인 특강인데 정원 정보가 없다.
        log.error("진행 중인 특강의 Redis 정원 키가 없다. 초기화 누락 또는 유실. specialLectureId={}", specialLectureId);
        return new ApplicationException(SpecialLectureExceptionCode.SPECIAL_LECTURE_CAPACITY_UNAVAILABLE);
    }

    private long toEpochSecond(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private String buildDeadlineKey(Long specialLectureId) {
        return String.format(SPECIAL_LECTURE_DEADLINE_KEY_PREFIX, specialLectureId);
    }

    // 레디스는 성공했는데 특강 DB 저장 실패 시 레디스 값 원상복구.
    // 키 부재 시 예외를 던지면 원인이 된 DB 예외를 가리므로 경고 로그만 남긴다.
    public void compensateSpecialLectureEnrollment(Long specialLectureId) {
        String key = buildAvailableSpecialLectureCapacityKey(specialLectureId);
        Long result = atomicCounter.incrementIfExists(key);

        if (result == null || result == KEY_NOT_FOUND) {
            log.warn("보상 대상 Redis 정원 키가 없어 복구를 건너뜀 (만료 추정). specialLectureId={}", specialLectureId);
        }
    }

    private String buildAvailableSpecialLectureCapacityKey(Long specialLectureId) {
        return String.format(AVAILABLE_SPECIAL_LECTURE_CAPACITY_KEY_PREFIX, specialLectureId);
    }
}
