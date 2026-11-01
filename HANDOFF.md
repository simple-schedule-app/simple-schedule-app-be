# SSA Backend HANDOFF

> 새 세션의 단일 진입점이다. 지금 상태와 다음 행동만 적는다. 지난 이력은 git 로그, [docs/adr/](docs/adr/), [deploy/README.md](deploy/README.md)에 있다.

## 1. 마지막 갱신

2026-10-20 (KST). 기준 커밋: develop `9d77af8`, main `e53fa71`.

## 2. 프로젝트 한 줄 요약

과외, 강의 수강신청 스케줄 앱 백엔드다. Spring Boot 4.1 / Java 21 모듈러 모놀리스(`app` 단일 JVM = course + notification)와 별도 Kotlin 앱 `geekchat`으로 이뤄진다. 운영 DB는 Supabase PostgreSQL(스키마 `ssa`, `geekchat`)이고, 배포는 main 푸시 → GHCR → WireGuard → SSH compose다. 에이전트 지침의 정본은 [AGENTS.md](AGENTS.md)다.

## 3. 직전 세션들에서 한 일 (2026-10-02 릴리스 이후)

- ✅ [#83](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/83) `7882b2c` app 테스트 결과를 테스트 이름별로 CI 로그에 출력
- ✅ [#84](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/84) `1c1fbb7` Stop 훅을 memguard로 감쌌다. 메모리 예산을 1분 기다려도 못 받으면 건너뛰고, 건너뜀과 실패를 구분해 보인다. 본체는 [.claude/hooks/stop-tests.sh](.claude/hooks/stop-tests.sh)
- ✅ [#85](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/85) `4686234` 터널 출시 경로의 연결 지점 검증과 계획([docs/superpowers/plans/2026-10-04-tunnel-launch-seams.md](docs/superpowers/plans/2026-10-04-tunnel-launch-seams.md)). 계획만 있고 구현은 오너 승인 대기
- ✅ [#86](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/86) `dda418c` 밀려난 SSE 커넥션의 정리가 같은 회원의 새 커넥션을 지우지 않게 했다. 테스트 5개: [SseSupersededConnectionTest](notification/src/test/java/com/example/simplescheduleapp/sse/presentation/SseSupersededConnectionTest.java)
- ✅ [#87](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/87) `dacf3da` `SseController` 설명 정정(웹도 `EventSourcePolyfill`로 SSE를 쓴다)
- ✅ [#89](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/89) `9d77af8` 새 운영 서버 기준 배포 런북. FCM 키 권한을 600에서 644로 바로잡았다(§7)
- 🔶 [#88](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/88) SSE 회원당 다중 연결 구현 계획. open, 오너 승인 대기. 브랜치 `docs/sse-multi-connection-plan`
- 🔶 [#90](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/90) 릴리스 PR(develop → main). open, CI 통과(7m35s, 8m3s). **머지 보류**: 새 서버 준비를 오너가 확인한 뒤 머지한다(2026-10-19 오너 결정)
- 운영 사건: 2026-10-18 운영 서버가 교체됐다. 옛 `10.64.212.20`은 내려갔고 `.env`, FCM 키, 볼륨이 남지 않았다. 새 서버 `10.64.212.40`(`agent-worker`, Rocky Linux 10.2)은 관리자의 에이전트 작업과 같은 VM을 쓰는 공유 호스트이고, 2026-10-19 기준 아무것도 배포되지 않았다. 그래서 운영 서비스는 지금 멈춰 있다
- 그 전 이력 요약: 2026-08-02 단일 JVM 대점프 릴리스(Boot 4.1, ADR-0004, ADR-0005, Flyway 최초 실행). 2026-10-02 릴리스 [#82](https://github.com/simple-schedule-app/simple-schedule-app-be/pull/82)(account P1, 운영 `ddl-auto: validate`, Kafka 재시도 수정, 의존성 업그레이드)가 옛 서버의 마지막 성공 배포다(`e53fa71`)

## 4. 현재 git 상태

| 위치 | 브랜치 | HEAD | 메모 |
|---|---|---|---|
| `origin/main` | main | `e53fa71` | 마지막 성공 배포(2026-10-02, 옛 서버) |
| `origin/develop` | develop | `9d77af8` | main보다 6커밋 앞(#83~#87, #89). 실행 중인 앱에 닿는 변경은 #86 하나이고, DB 마이그레이션과 `deploy/` 번들은 바뀌지 않았다 |
| `simple-schedule-app-be/ssa/` (주 체크아웃) | main | `e53fa71` | `.claude/settings.json`(수정)과 `.claude/hooks/`(추적 안 됨)는 #84 훅을 미리 덮어쓴 것이다. #90이 main에 들어간 뒤 당기기 전에 `git restore .claude/settings.json && rm .claude/hooks/stop-tests.sh`로 걷어 낸다. `.omc/` 변경은 도구 상태라 커밋하지 않는다 |
| `simple-schedule-app-be/.wt-ssa-analysis/` (worktree) | 작업 브랜치 | | 작업은 여기서 한다 |

## 5. 다음 작업 우선순위

1. **새 서버 준비 → #90 머지(운영 배포).** 오너와 관리자가 한다. 순서:
   1. 관리자에게 계정을 요청한다.
   2. [deploy/README.md](deploy/README.md) §1을 따른다: Docker CE, `/opt/ssa`, `.env`(600), FCM 키(644), SELinux.
   3. §2에서 시크릿 4개를 갱신한다(`DEPLOY_SSH_KEY`는 새로 발급).
   4. 노트북에서 접속 점검을 한 뒤 WireGuard를 끈다.
   5. 오너 확인을 받고 #90을 머지한다.
2. **첫 배포 전 특강 정원 키 확인.** 새 서버의 Redis는 비어 있고, 정원 키를 자동으로 다시 채우는 경로가 없다. 진행 중인 특강이 DB에 있으면 그 신청은 `SPECIAL_LECTURE_CAPACITY_UNAVAILABLE`로 실패하고 ERROR 로그가 남는다([SpecialLectureRedisClient.java](course/src/main/java/com/example/simplescheduleapp/lecture/special/application/SpecialLectureRedisClient.java)의 `resolveMissingKeyCause`). 아래 쿼리는 시간대 차이를 덮으려고 하루 여유를 둔 넉넉한 목록이다.
   ```sql
   SELECT sl.schedule_id, sl.capacity, s.end_time
   FROM ssa.special_lecture sl
   JOIN ssa.schedule s ON s.schedule_id = sl.schedule_id
   WHERE s.deleted_date IS NULL AND s.end_time > now() - interval '1 day';
   ```
   행이 있으면 남은 정원(정원에서 신청 수를 뺀 값)으로 정원 키와 마감 키를 다시 넣어야 한다. 그 절차와 도구는 아직 없다(확인 필요).
3. **오너 결정 대기:**
   - 공유 호스트에서 CI 키에 docker 그룹(사실상 root 권한)을 줄지, 관리자와 정한다.
   - 시크릿 재발급 범위. 전부 재발급을 권한다.
   - #88 다중 연결 계획의 승인과 실행 방식.
   - 터널 ① realip 구현 승인.
4. **남은 부채:**
   - 시크릿 로테이션.
   - bcrypt 재해싱 검증. 오너가 기존 계정으로 한 번 로그인해야 한다.
   - `/me/schedules` 네이티브 SQL은 H2에서만 검증했다.
   - `FcmService`의 `application → infrastructure` 위반 5건 동결.
   - 3b-full. 쿼리 파라미터를 없애려면 클라이언트를 동시에 릴리스해야 한다.
5. **`.planning/codebase/` 재분석 때 반영할 것.** 이 문서들은 스냅샷이라 직접 고치지 않는다.
   - CONCERNS의 SSE 하트비트 항목: 이미 해결됐다.
   - CONCERNS의 SSE 덮어쓰기 항목: 정리 결함은 #86에서 해결했고, 다중 연결은 #88에서 다룬다.
   - 운영 서버 사양: CONCERNS 148줄과 STACK 115줄이 옛 서버 기준이다.

## 6. 컨텍스트 빠른 복원

```bash
cd simple-schedule-app-be/.wt-ssa-analysis
git fetch origin && git log --oneline origin/main..origin/develop   # 다음 릴리스에 들어갈 것
gh pr list --state open
gh run list --workflow deploy.yml --limit 3                         # 운영에 실제로 나간 것은 마지막 success
memguard -- ./gradlew --max-workers=2 buildAll                      # CI와 같은 검증. Gradle은 직접 감싼다
```

| 문서 | 볼 때 |
|---|---|
| [AGENTS.md](AGENTS.md), 모듈별 `AGENTS.md` | 작업 전 항상 |
| [deploy/README.md](deploy/README.md) | 서버 준비, 배포, 운영 확인 |
| [docs/adr/](docs/adr/) (0001~0006) | 구조 결정의 근거 |
| [docs/superpowers/plans/](docs/superpowers/plans/) | 진행 중인 계획 |
| [docs/SECURITY.md](docs/SECURITY.md) | 시크릿과 보안 조치 |

## 7. 알아두어야 할 함정

**배포와 운영**
- develop → main 머지는 곧 운영 배포다. `deploy.yml`의 paths-ignore는 `**.md`, `docs/**`, `.planning/**`뿐이다. 오너 확인 뒤에만 머지하고, 릴리스는 merge commit으로 한다(#82와 같은 방식).
- 배포 중에는 노트북 WireGuard 터널을 끈다. CI가 같은 피어 키를 쓴다. 실패하면 `gh run rerun <id> --failed`로 다시 돌린다.
- CI 접속 단계의 실패 메시지는 "피어 키 충돌"만 안내한다. 새 서버에서는 키, 계정, `authorized_keys`, 호스트 키 고정이 틀려도 같은 메시지가 나온다. 런북 §2의 3에 있는 노트북 점검으로 먼저 가린다.
- main 끝이 곧 배포된 것은 아니다. 배포가 실패해도 main은 앞으로 간다. 롤백 기준은 `gh run list --workflow deploy.yml`의 마지막 success다.
- nginx는 `up -d`로 갱신되지 않는다. CD가 `docker restart ssa-nginx`를 하고(reload가 아니라 restart여야 upstream DNS를 다시 푼다), 스모크는 401이어야 통과다.
- FCM 키는 644여야 한다. 앱 컨테이너 사용자 `spring`이 읽어야 하므로, 600이면 `FcmConfig`가 기동을 멈춘다. 키는 `secrets` 디렉터리의 700 권한이 지킨다.
- 원격 명령의 URL은 따옴표로 감싼다. 옛 서버 셸 zsh가 `?`를 글로브로 먹어 2026-08-13 스모크가 거짓으로 실패했다. 새 서버의 셸은 확인 필요.
- `JWT_SECRET`은 base64url이어야 한다. GHCR pull은 CI의 `GITHUB_TOKEN`으로만 된다(gh CLI 토큰엔 `read:packages`가 없다). Supabase는 세션 풀러 호스트로 붙는다(direct 호스트는 IPv6 전용).

**빌드와 테스트**
- Gradle은 memguard 자동 감싸기 대상이 아니다. 직접 `memguard -- ./gradlew --max-workers=2 <태스크>`로 돌린다.
- `ScheduleOverlapQuery*Test`는 Testcontainers라 Docker가 필요하다(없으면 실패). SSA 컨텍스트 기동에는 Redis가 필요하다.
- 네이티브 SQL은 H2만으로 검증하지 않는다(운영 PostgreSQL, 로컬 MySQL).
- ArchUnit freeze 갱신은 같은 커밋에 한다. 새 모듈에 래칫을 붙이면 `./gradlew buildAll --dry-run | grep ":.*:test"`로 CI에 포함되는지 확인한다(2026-07-17 `common`이 CI에서 돌지 않았다).
- `ci.yml`의 `pull_request: branches:`는 base 기준 필터다. base가 main이나 develop이 아니면 CI가 돌지 않고, PR 재타깃만으로는 다시 돌지 않는다(close 후 reopen).
- 테스트는 스펙이다. 테스트 수정은 오너 승인을 받는다. 지금 `@Disabled`는 `RequiresNewDeadLockOccurTest` 하나다.

**코드**
- 순수 도메인과 엔티티를 매핑할 때 id와 감사 필드를 전파한다. 매퍼가 만드는 것은 다른 인스턴스라, 빠뜨리면 INSERT가 중복되거나 `createdDate`가 null로 덮인다.
- 낙관적 락 `@Version`은 Schedule 루트에만 둔다. 자식 엔티티에 두면 Hibernate가 기동하지 못한다.
- `KafkaEventProducer.produce()`에 `@Transactional`을 붙이지 않는다. 실패 상태 저장이 롤백된다.
- SSE: `open-in-view: false`를 유지한다. 경로 계약을 바꾸면 [deploy/nginx/conf.d/ssa.conf](deploy/nginx/conf.d/ssa.conf)도 고친다(2026-08-02 SSE 301).
- SSE는 지금 회원당 커넥션 하나다. 같은 계정의 두 클라이언트(탭 둘, 웹과 Android)는 번갈아 끊긴다. #86 뒤로는 이것이 정상 동작이고, 해결은 #88에서 한다.

## 8. 다음 세션에서 할 첫 액션

| 하려는 일 | 첫 행동 |
|---|---|
| 서버 준비가 끝나 배포 | 오너 확인 → §5의 2 쿼리로 진행 중 특강 확인 → WireGuard 끄기 → `gh pr merge 90 --merge` → Deploy 실행 결과와 스모크 401 확인 → [deploy/README.md](deploy/README.md) §1의 "확인" 절 |
| #88 승인됨 | 브랜치 `docs/sse-multi-connection-plan`의 `docs/superpowers/plans/2026-10-15-sse-multi-connection.md`를 읽고 Task 1부터 |
| 터널 ① 구현 승인됨 | [docs/superpowers/plans/2026-10-04-tunnel-launch-seams.md](docs/superpowers/plans/2026-10-04-tunnel-launch-seams.md)의 ① |
| 특강 정원 키 재적재 도구 | `SpecialLectureRedisClient.initializeSpecialLecture`와 신청 수 집계를 묶는 일회성 절차부터 설계 |
| 부채 정리 | §5의 4 목록에서 하나를 골라 계획부터 |
| 운영 장애 조사 | `gh run list --workflow deploy.yml` → 서버에서 `docker logs ssa-app --tail 200` |
