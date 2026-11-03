package com.example.simplescheduleapp.lecture.special.infrastructure.persistence;

import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureEnrollment;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@code SpecialLectureEnrollmentRepository} 포트의 JPA 어댑터 (ADR-0004).
 * <p>Spring Data/JPA 세부와 도메인↔엔티티 매핑을 여기에 격리한다.
 */
@Repository
@RequiredArgsConstructor
public class SpecialLectureEnrollmentRepositoryAdapter implements SpecialLectureEnrollmentRepository {

    private final SpecialLectureEnrollmentJpaRepository jpaRepository;

    @Override
    public SpecialLectureEnrollment save(SpecialLectureEnrollment specialLectureEnrollment) {
        return SpecialLectureEnrollmentMapper.toDomain(
                jpaRepository.save(SpecialLectureEnrollmentMapper.toEntity(specialLectureEnrollment)));
    }

    @Override
    public Optional<SpecialLectureEnrollment> findById(Long id) {
        return jpaRepository.findById(id).map(SpecialLectureEnrollmentMapper::toDomain);
    }

    @Override
    public int deleteBySpecialLectureIdAndStudentId(Long specialLectureId, Long studentId) {
        return jpaRepository.deleteBySpecialLectureIdAndStudentId(specialLectureId, studentId);
    }

    @Override
    public long countBySpecialLectureId(Long specialLectureId) {
        return jpaRepository.countBySpecialLectureId(specialLectureId);
    }
}
