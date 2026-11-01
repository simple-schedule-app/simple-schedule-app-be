# simple-schedule-app-be (SSA)

이 파일이 에이전트 지침의 정본이다. `CLAUDE.md`는 `@AGENTS.md`로 이 파일을 가져오기만 하므로 내용은 여기에 쓴다.

과외·강의 수강신청 스케줄 앱 백엔드. **Spring Boot 4.1.0 / Java 21 / Gradle 멀티모듈(모듈러 모놀리스, 단일 JVM).**

빌드 구조와 단계별 진행은 `docs/adr/0003-modular-monolith-consolidation.md`가 정본이다.

## 모듈 구조

| 모듈 | 역할 | 포트 |
|---|---|---|
| `app/` | **유일한 실행 모듈(bootJar).** course와 notification을 조합하는 합성 루트. 설정(`application*.yml`), Flyway 마이그레이션(`db/`), 모듈 간 인프로세스 어댑터 | 8080 |
| `common/` | 공유 라이브러리 (bootJar 비활성). Kafka 이벤트·DTO, JWT(jjwt), 공통 예외, testFixtures(Fixture Monkey) | - |
| `course/` | 수강/강의/회원 바운디드 컨텍스트(라이브러리, 자체 bootJar 없음). 도메인: member, student, tutor, parent, lecture(general/special), schedule, consultation | - |
| `notification/` | 알림 바운디드 컨텍스트(라이브러리). SSE 스트림(`/sse-stream`, 인증 토큰으로 회원 식별), FCM 푸시, Kafka 컨슈머 | - |
| `geekchat/` | 별도 Kotlin 앱. `includeBuild`로 묶은 컴포지트 빌드(ADR-0003 Stage 4 전까지 분리) | - |
| `playground/`, `ngrinder/` | 부하 테스트 전용 — CI/배포 대상 아님 | - |

## 빌드·테스트 명령

```bash
./gradlew buildAll                                                     # CI와 동일 (SSA + geekchat)
./gradlew :course:test --tests '*SpecialLecture*'                      # 단일 테스트
docker compose up -d    # 로컬 인프라 (MySQL 3306/3307, Redis 6379, Kafka 9092). 앱 기본 DB는 3306의 `ssa` 스키마
```

- 테스트는 **H2 + @EmbeddedKafka** 기반. 예외: `ScheduleOverlapQuery*Test`는 Testcontainers(MySQL 8.0, PostgreSQL 16)를 쓰므로 **Docker가 필요**하다(없으면 스킵이 아니라 실패).
- SSA 컨텍스트 기동에 Redis가 필요하다(CI는 redis 서비스 컨테이너, `TEST_REDIS_HOST`/`TEST_REDIS_PORT`).
- 운영 DB는 PostgreSQL(`application-prod.yml`), 로컬 기본은 MySQL이다. 네이티브 SQL은 H2만으로 검증하지 않는다.
- 테스트 픽스처는 `common`의 testFixtures + Fixture Monkey 사용.

## 아키텍처 규칙 (위반 금지)

1. **레이어 방향**: 헥사고날(ADR-0002). 도메인별 패키지 안에 `presentation`(인바운드 어댑터) / `application` / `domain` / `infrastructure`(아웃바운드 어댑터) / `exception`. 의존은 안쪽(`domain`)으로만. 영속성은 `application/port/out` 포트 뒤에 두고 `infrastructure/persistence`가 구현한다.
2. **모듈 간 통신**: course와 notification은 서로를 컴파일 의존하지 않는다. 비동기는 Kafka 이벤트(common의 이벤트 클래스, 단일 JVM이 발행하고 자기 소비). 동기는 notification의 포트를 `:app`의 인프로세스 어댑터(`InProcessEnrolledStudentsAdapter`)가 구현한다. course의 `/internal/**` REST는 남아 있으며 외부 노출 금지(nginx에서 403 차단).
3. **동시성 제어**: course는 Redisson 분산락 + DB 락 계층 방어(특강 수강신청은 4단계 방어). 락 순서 변경 시 `test/select-lock-deadlock` 브랜치의 데드락 사례 참고.
4. **SSE**: `app`의 `open-in-view: false` 유지 (Emitter 커넥션 고갈 방지).
5. **시크릿**: 코드·yml에 하드코딩 금지. 운영 값은 환경변수(`application-prod.yml` 패턴), FCM JSON은 gitignore 유지.

## 작업 규칙

- **테스트가 스펙**: 테스트 실패 시 프로덕션 코드를 고친다. 테스트 파일 임의 수정 금지.
- **커밋 전 필수**: `./gradlew buildAll` 전체 통과.

## 브랜치 전략

- `main` = 운영 배포 브랜치(push 시 `deploy.yml` 실행), `develop` = 통합 브랜치(최신 코드), `feature/*` = 작업 브랜치.
- develop → main 머지는 운영 배포이므로 오너 확인 후에만 한다.
- 커밋 컨벤션: `feat:|fix:|refactor:|test:|docs:` + 한국어 요약.

## 문서 맵 (AI 에이전트는 작업 전 필독)

- `.planning/codebase/` — 코드베이스 분석 7종 (STACK, ARCHITECTURE, STRUCTURE, CONVENTIONS, TESTING, INTEGRATIONS, CONCERNS). **2026-08-22 기준 스냅샷**이라 이 파일, ADR과 다르면 이 파일과 ADR이 우선
- 각 모듈의 `AGENTS.md` — 모듈별 상세 가이드 (`common/`, `course/`, `notification/`, `geekchat/`)
- `docs/adr/` — 아키텍처 결정 기록(ADR)
- `deploy/README.md` — 배포 런북 (GitHub Actions → GHCR → SSH. 운영 Oracle A1은 공인 IP, 예비 VM은 WireGuard 경유)

## 전용 에이전트 (.claude/agents/)

| 에이전트 | 용도 |
|---|---|
| `ssa-code-analyzer` | 코드베이스 분석·영향도 조사 (읽기 전용) |
| `ssa-architecture-guardian` | 레이어/의존성/DDD 규칙 준수 리뷰 (읽기 전용) |
| `ssa-prod-builder` | 컨벤션을 따르는 상용 코드 구현 |
| `ssa-qa-reviewer` | 테스트·보안·성능 QA 리뷰 (읽기 전용) |

구현 작업 흐름: analyzer로 영향도 파악 → prod-builder로 구현 → guardian + qa-reviewer로 이중 검증.
