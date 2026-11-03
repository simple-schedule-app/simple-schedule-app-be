package com.example.simplescheduleapp.lecture.special.infrastructure.persistence;

import com.example.simplescheduleapp.lecture.special.domain.SpecialLecture;
import com.example.simplescheduleapp.lecture.special.domain.SpecialLectureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code SpecialLectureRepository} 포트의 JPA 어댑터 (ADR-0004).
 * <p>매퍼가 version을 왕복시키므로 save 시 낙관적 락(4단계 방어의 3차선)이 유지된다.
 */
@Repository
@RequiredArgsConstructor
public class SpecialLectureRepositoryAdapter implements SpecialLectureRepository {

    private final SpecialLectureJpaRepository jpaRepository;

    @Override
    public SpecialLecture save(SpecialLecture specialLecture) {
        return SpecialLectureMapper.toDomain(jpaRepository.save(SpecialLectureMapper.toEntity(specialLecture)));
    }

    @Override
    public Optional<SpecialLecture> findById(Long id) {
        return jpaRepository.findById(id).map(SpecialLectureMapper::toDomain);
    }

    @Override
    public List<SpecialLecture> findAllOrderByStartTime() {
        return jpaRepository.findAllByOrderByStartTimeAsc().stream()
                .map(SpecialLectureMapper::toDomain)
                .toList();
    }

    @Override
    public List<SpecialLecture> findAllNotEndedAsOf(LocalDateTime now) {
        return jpaRepository.findAllByEndTimeGreaterThanEqualOrderByIdAsc(now).stream()
                .map(SpecialLectureMapper::toDomain)
                .toList();
    }
}
