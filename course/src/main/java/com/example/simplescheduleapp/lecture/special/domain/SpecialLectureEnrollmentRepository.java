package com.example.simplescheduleapp.lecture.special.domain;

import java.util.Optional;

/**
 * 아웃바운드 포트: 특별 강의 수강신청 영속성 (구현: infrastructure의 JPA 어댑터).
 *
 * <p>순수 자바 인터페이스 — Spring Data/JPA가 도메인에 침투하지 않는다. (ADR-0002 Stage 2)
 */
public interface SpecialLectureEnrollmentRepository {

    SpecialLectureEnrollment save(SpecialLectureEnrollment specialLectureEnrollment);

    Optional<SpecialLectureEnrollment> findById(Long id);

    /** 이 특강에 지금 남아 있는 신청 수. 정원 키 복구 시 "남은 자리 = 정원 - 이 값"의 근거다. */
    long countBySpecialLectureId(Long specialLectureId);

    /**
     * 취소. <b>삭제된 행 수를 반환한다.</b>
     *
     * <p>이 반환값이 좌석 반환의 <b>유일한 멱등성 근거</b>다. 조회 후 삭제(read-then-delete)로는
     * 안 된다 — 동시 취소 두 건이 같은 행을 읽고 <b>둘 다 좌석을 반환해 정원을 넘긴다</b>.
     * 삭제가 원자적으로 알려 주는 행 수만이 "내가 실제로 지웠다"의 증거다.
     */
    int deleteBySpecialLectureIdAndStudentId(Long specialLectureId, Long studentId);
}
