package com.example.simplescheduleapp.lecture.special.domain;

import com.example.simplescheduleapp.common.exception.ApplicationException;
import com.example.simplescheduleapp.lecture.special.exception.SpecialLectureExceptionCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 아웃바운드 포트: 특별 강의 영속성 (구현: infrastructure의 JPA 어댑터).
 *
 * <p>순수 자바 인터페이스 — Spring Data/JPA가 도메인에 침투하지 않는다. 조회 실패 시
 * 도메인 예외를 던지는 {@code getXxx} 규약도 여기(도메인)에 위치한다. (ADR-0002 Stage 2)
 */
public interface SpecialLectureRepository {

    SpecialLecture save(SpecialLecture specialLecture);

    Optional<SpecialLecture> findById(Long id);

    /**
     * 특강 목록 — 시작 시각 오름차순.
     *
     * <p>정렬을 포트 계약에 박아 둔다. 목록 화면은 "다음에 열리는 특강"을 먼저 보여주는 것이
     * 유일하게 쓸모 있는 순서인데, 정렬을 어댑터 재량에 맡기면 DB가 반환 순서를 바꿀 때
     * 화면이 조용히 흔들린다.
     */
    List<SpecialLecture> findAllOrderByStartTime();

    /**
     * 아직 끝나지 않은 특강 — {@code endTime >= now}. 삭제된 것은 제외한다.
     *
     * <p>"끝남"의 기준은 {@link SpecialLecture#hasEnded(LocalDateTime)}와 같은 {@code endTime}이다.
     * 정원 키 복구가 "지금 신청을 받을 수 있는 특강"을 고르는 데 쓴다. {@code now}를 인자로 받아
     * 어댑터가 시계를 읽지 않게 한다.
     */
    List<SpecialLecture> findAllNotEndedAsOf(LocalDateTime now);

    default SpecialLecture getById(Long id) {
        return findById(id).orElseThrow(() -> new ApplicationException(SpecialLectureExceptionCode.SPECIAL_LECTURE_NOT_FOUND));
    }
}
