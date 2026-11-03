package com.example.simplescheduleapp.lecture.special.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA 리포지토리 — {@link SpecialLectureEnrollmentRepositoryAdapter}가 이 인터페이스로
 * {@code SpecialLectureEnrollmentRepository} 포트를 구현한다. Spring Data는 이 infrastructure 계층에만 존재한다.
 */
interface SpecialLectureEnrollmentJpaRepository extends JpaRepository<SpecialLectureEnrollmentEntity, Long> {

    long countBySpecialLectureId(Long specialLectureId);

    /**
     * 벌크 삭제 — 엔티티를 로드하지 않고 <b>영향 행 수를 그대로</b> 받는다.
     *
     * <p>파생 삭제 메서드는 엔티티를 먼저 조회한 뒤 지우므로 조회와 삭제 사이에 창이 생긴다.
     * 여기서 필요한 것은 "내가 지운 행이 실제로 있었는가"의 원자적 답이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SpecialLectureEnrollmentEntity e "
            + "where e.specialLectureId = :specialLectureId and e.studentId = :studentId")
    int deleteBySpecialLectureIdAndStudentId(@Param("specialLectureId") Long specialLectureId,
                                             @Param("studentId") Long studentId);
}
