package com.example.simplescheduleapp.lecture.special.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA 리포지토리 — {@link SpecialLectureRepositoryAdapter}가 이 인터페이스로
 * {@code SpecialLectureRepository} 포트를 구현한다. Spring Data는 이 infrastructure 계층에만 존재한다.
 */
interface SpecialLectureJpaRepository extends JpaRepository<SpecialLectureEntity, Long> {

    List<SpecialLectureEntity> findAllByOrderByStartTimeAsc();

    /** 소프트 삭제 제외는 {@code ScheduleEntity}의 {@code @SQLRestriction}이 자동으로 건다. */
    List<SpecialLectureEntity> findAllByEndTimeGreaterThanEqualOrderByIdAsc(LocalDateTime now);
}
