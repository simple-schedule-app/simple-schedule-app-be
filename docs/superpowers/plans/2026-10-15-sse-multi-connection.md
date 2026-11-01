# SSE 회원당 다중 연결 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**상태:** 계획만. 오너 승인 대기. 승인받을 것은 둘이다. (1) 설계 결정 D1~D5, (2) 기존 테스트 변경 목록.

**Goal:** 같은 계정의 여러 탭과 포그라운드 기기가 SSE 연결을 서로 밀어내며 45초마다 번갈아 끊기는 현상을 없앤다. 회원당 연결을 여럿 두고, 알림을 그 회원의 모든 연결로 보낸다.

**Architecture:** notification 모듈의 `sse.application` 안에서만 바꾼다. 회원당 emitter 하나를 담던 `SseEmitterRegistry`를 회원당 연결 목록을 담는 `SseConnectionRegistry`로 바꾸고, PR #86에서 만든 서비스 내부 클래스 `Connection`을 `SseConnection`으로 꺼낸다. 접속 표시 포트(`SseClientPresence`)와 Redis 키, 웹과 Android는 바꾸지 않는다.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring MVC `SseEmitter`, Redis(접속 표시), JUnit 5, Mockito, MockMvc 비동기 요청.

**Spec:** 별도 스펙 문서는 없다. 이 문서의 "확인한 사실", "고치는 것과 고치지 않는 것", "설계"가 스펙이다. 배경은 PR #86(밀려난 커넥션의 정리 결함)과 2026-10-15 오너 결정("다중 연결 지원 계획부터")이다.

## Global Constraints

- 바꾸는 코드는 `notification/src/main/java/com/example/simplescheduleapp/sse/application/` 안에 둔다. 새 클래스는 infrastructure와 presentation을 import하지 않는다(헥사고날, ArchUnit).
- `SseClientPresence` 포트, Redis 접속 표시 키(`online:<memberId>`), Redis 채널(`sse-notification`)은 바꾸지 않는다.
- 웹(`simple-schedule-app-web`)과 Android(`simple-schedule-app-android`)는 바꾸지 않는다.
- 기존 테스트는 아래 "테스트 변경 목록"에 적힌 것만, 적힌 대로 바꾼다. 그 밖의 테스트가 실패하면 프로덕션 코드를 고친다.
- 무거운 검증은 `memguard -- ./gradlew --max-workers=2 ...`로 한 번에 하나씩 돌린다. Gradle은 memguard 자동 감싸기 대상이 아니라 직접 감싼다.
- 커밋 전에 `memguard -- ./gradlew --max-workers=2 buildAll`이 통과해야 한다(저장소 AGENTS.md).
- 회원당 상한은 `SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER = 10`이다.
- 한국어 주석을 쓴다. em dash는 쓰지 않고, 단순 열거에 가운뎃점을 쓰지 않는다.

## 확인한 사실

| 사실 | 근거 |
|---|---|
| 지금은 회원당 연결이 하나다. 같은 회원의 두 번째 연결이 첫 연결의 하트비트를 멈추고 등록 칸을 차지한다 | `SseConnectionService.connect` (develop `dda418c`) |
| 그래서 같은 계정의 클라이언트 둘은 번갈아 45초씩 침묵한다. 2026-10-13 관측 때 오너는 같은 계정으로 탭이나 기기를 둘 이상 열어 두었다 | 오너 확인(2026-10-15), PR #86 본문 |
| 웹 폴리필은 서버가 스트림을 닫으면 1초 뒤 다시 붙고, 반복되면 간격을 최대 16초까지 늘린다 | 웹 `node_modules/event-source-polyfill/src/eventsource.js:741, 905-906` (1.0.31) |
| Android는 서버가 스트림을 정상 종료해도 재연결 신호로 다룬다 | Android `SseClient.kt:27-37, 63-68` |
| Android는 앱이 포그라운드이고 로그인한 동안에만 SSE를 연다 | Android `MainActivity.kt`의 `observeNotificationEvents()`가 `repeatOnLifecycle(Lifecycle.State.STARTED)` 안에서 수집 |
| 접속 표시는 회원 단위 Redis 키 하나다. 하트비트 갱신은 키를 다시 만드는 SET이다 | `RedisClientManager.java`, `RedisPresenceManager.java:35-36` |
| 알림은 접속 표시가 온라인이면 SSE, 아니면 FCM으로 간다 | `NotificationDispatcher.java:22-31` |
| 운영 앱 인스턴스는 하나다 | `deploy/docker-compose.prod.yml`의 `container_name: ssa-app` |
| MockMvc 비동기 응답 본문에 SSE 이벤트가 쌓인다 | 2026-10-15 버린 실험의 본문: `event:connect\ndata:SSE connected for memberId: 12\n\nevent:notification\ndata:{"title":"t","body":"b"}\n\n` |
| 운영에서 두 경로가 모두 실제로 돌았다. 12:12~12:42에는 "연결 해제" 없이 "요청 수신"만 30여 건이었다(다른 클라이언트의 연결이 현재 연결을 밀어냄, 이 계획이 막는 것). 13:12부터는 정확히 60분 전 연결의 타임아웃이 살아 있는 연결을 지웠고, 약 40초 뒤 재연결이 따랐다(PR #86이 막는 것) | 운영 앱 로그(2026-10-13 UTC, 회원 12의 "요청 수신", "연결 등록", "연결 해제"), 오너 전달과 오케스트레이터 분석 |
| 끊긴 클라이언트를 서버가 알아채는 데 걸리는 시간은 쓰기 여부에 달렸다. 하트비트가 멈춘 밀려난 연결은 60분 타임아웃까지 남았다. 하트비트를 쓰는 연결은 클라이언트가 떠난 뒤 80초 만에 정리된 사례가 있다 | 같은 로그: 13:12 이후의 해제가 모두 60분 전 연결이다. 14:37:59 연결은 80초 뒤 해제됐다 |

## 고치는 것과 고치지 않는 것

| 시나리오 | 이 계획 뒤 |
|---|---|
| 같은 계정으로 탭 둘 이상 | 모든 탭이 계속 연결되고 알림을 받는다 |
| 웹과 Android를 동시에 포그라운드로 | 둘 다 연결되고 알림을 받는다 |
| 웹 탭을 연 채 Android가 백그라운드 | **그대로다.** Android는 SSE를 닫았고, 회원은 웹 탭 때문에 온라인이라 FCM이 가지 않는다. 휴대폰은 알림을 받지 못한다. 접속 표시를 기기 단위로 바꾸는 별개 결정이다(아래 "남는 위험") |

## 설계 (오너 결정 항목)

| 번호 | 결정 | 권고 | 이유 |
|---|---|---|---|
| D1 | 회원당 연결 수 | 여럿. 알림은 그 회원의 모든 연결로 보낸다 | 오너 결정(2026-10-15) |
| D2 | 회원당 상한과 넘쳤을 때 | 상한 10. 넘으면 가장 오래된 연결을 **완료시켜** 닫고 WARN 로그를 남긴다 | 탭 여러 개와 기기 몇 대에, 막 끊겨 아직 정리되지 않은 연결까지 담을 여유다. 완료시키면 클라이언트가 1초 안팎에 다시 붙는다. 하트비트만 멈추면 45초 뒤에야 다시 붙어 그사이 알림을 놓친다. 다중 연결에서는 모든 연결이 하트비트를 쓰므로, 죽은 연결은 60분 타임아웃이 아니라 쓰기가 실패하는 시점에 정리된다. 운영 사례로는 80초다. 전체 페이지를 1분에 한 번 꼴로 다시 열면 죽은 연결은 동시에 두어 개라 10 안에 여유가 있다. Task 4에서 로컬로 다시 재고, 그 값이 훨씬 크면 상한을 다시 본다 |
| D3 | 접속 표시 | 연결이 하나라도 있으면 온라인. 마지막 연결이 끝날 때만 해제한다 | Redis 키 형식을 바꾸지 않는다. 인스턴스가 하나라 이 인스턴스 안에서 세면 된다 |
| D4 | 알림 전송 | 연결마다 따로 보낸다. 실패한 연결(`IOException`, `IllegalStateException`)만 정리하고 나머지로 계속 보낸다. 연결이 없으면 `SSE_NOT_FOUND`, 모두 실패하면 `SSE_SEND_FAILED` | 오류 코드는 지금과 같다. 지금 코드는 `IllegalStateException`(이미 끝난 emitter)을 잡지 않아, 그 경우 오류가 그대로 새어 나간다 |
| D5 | 등록부 | `SseEmitterRegistry`를 지우고 `SseConnectionRegistry`를 새로 둔다. `Connection`은 `SseConnection`으로 꺼낸다 | 연결 단위로 정리하려면 등록부가 연결을 담아야 한다. 서비스와 등록부가 같은 상태를 이중으로 들던 구조(PR #86에서 정합을 맞추느라 복잡해진 부분)도 없어진다 |

## 테스트 변경 목록 (승인 대상)

### A. 스펙이 뒤집히는 것 (2건)

| 테스트 | 지금 고정하는 것 | 바뀐 뒤 |
|---|---|---|
| `SseConnectionServiceTest.재연결시_이전_커넥션의_하트비트를_취소한다` | 같은 회원의 두 번째 연결이 첫 연결의 하트비트를 취소한다 | 취소하지 않는다. 이름도 `두_번째_연결은_첫_연결의_하트비트를_멈추지_않는다`로 바꾼다 |
| `SseHeartbeatLifecycleTest.같은_회원이_재연결하면_이전_하트비트_스케줄이_취소된다` | 같음 | 재연결만으로는 이전 하트비트를 멈추지 않고, 그 연결의 하트비트가 쓰기에 실패할 때 멈춘다. 하트비트 누적은 재연결 취소가 아니라 연결 단위 정리가 막는다 |

### B. 준비 코드나 단언만 바뀌는 것 (2건, 개념은 그대로)

| 테스트 | 바뀌는 부분 |
|---|---|
| `SseConnectionServiceTest.완료된_emitter에_하트비트가_발사되면_스케줄을_취소하고_자원을_정리한다` | 목 타입이 `SseEmitterRegistry`에서 `SseConnectionRegistry`로 바뀐다. `delete(memberId)` 검증이 `remove(memberId, 연결)` 검증으로 바뀌고, "마지막 연결이었다"는 스텁이 붙는다 |
| `SseConnectionServiceTest.알림_이벤트는_이름이_고정이고_제목은_데이터에_들어간다` | `registry.get(memberId)` 스텁이 `registry.connectionsOf(memberId)` 스텁으로 바뀐다 |

### C. 지우고 대체하는 것 (8건)

| 지우는 테스트 | 대체하는 테스트 |
|---|---|
| `SseEmitterRegistryTest` 3건 (save 후 get, 없을 때 get 예외, delete 후 get 예외) | `SseConnectionRegistryTest` 6건 (여럿 담기, 빈 목록, 남은 수와 마지막일 때 0, 이미 빠진 것 무시, 상한 퇴출, 회원 간 독립) |
| `SseSupersededConnectionTest` (a) 옛 완료 콜백이 새 연결을 지우지 않는다 | `SseMultiConnectionTest` "한 연결이 끝나도 다른 연결은 그대로고 알림도 받는다" |
| (b) 옛 하트비트 실패가 새 연결을 지우지 않는다 | "한 연결의 하트비트가 쓰기에 실패해도 다른 연결은 그대로다" |
| (c) 양성 대조 | "양성 대조: 마지막 연결이 끝나면 접속 표시까지 정리한다" |
| (d) 옛 emitter로의 알림 실패 | "한 연결로의 알림 전송이 실패하면 그 연결만 정리하고 나머지로는 보낸다" |
| (e) 스케줄 도중 밀려남 | `SseConnectionTest` "이미 멈춘 커넥션에 붙는 하트비트는 붙자마자 멈춘다". 다중 연결에서는 밀려남이 없어져 커넥션 단위 단언으로 옮긴다 |

새로 추가하는 테스트: `SseConnectionTest` 3건, `SseConnectionRegistryTest` 6건, `SseMultiConnectionTest` 9건.

바꾸지 않는 SSE 관련 테스트: `SseAuthFailureContractTest`(2), `SseConnectionPublisherTest`(1), `RedisSseMessagePublisherTest`(1), `RedisClientManagerTest`(5), `NotificationIntegrationTest`.

## 파일 구조

| 파일 | 변경 | 책임 |
|---|---|---|
| `notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnection.java` | 생성 | 연결 하나: emitter, 하트비트 핸들, 멈춤 표시 |
| `.../sse/application/SseConnectionRegistry.java` | 생성 | 회원별 연결 목록, 상한, 원자적 추가와 제거 |
| `.../sse/application/SseConnectionService.java` | 수정 | 연결 수명, 하트비트, 접속 표시, 알림 팬아웃 |
| `.../sse/application/SseEmitterRegistry.java` | 삭제 | `SseConnectionRegistry`로 대체 |
| `notification/src/test/java/com/example/simplescheduleapp/sse/application/SseConnectionTest.java` | 생성 | 멈춤과 하트비트 붙이기 순서 |
| `.../sse/application/SseConnectionRegistryTest.java` | 생성 | 등록부 단위 테스트 |
| `.../sse/application/SseEmitterRegistryTest.java` | 삭제 | C 목록 |
| `.../sse/application/SseConnectionServiceTest.java` | 수정 | A와 B 목록 |
| `.../sse/application/SseHeartbeatLifecycleTest.java` | 수정 | A 목록 |
| `.../sse/presentation/SseMultiConnectionTest.java` | 생성 | MockMvc 비동기로 다중 연결 동작 |
| `.../sse/presentation/SseSupersededConnectionTest.java` | 삭제 | C 목록 |

파일 11개라 승인 게이트(3개 이상 동시 수정) 대상이다. 이 계획의 승인으로 함께 받는다.

## Review Focus

1. **새로고침이 연달아 일어날 때:** 끊긴 탭의 연결이 정리되기 전에 상한을 채워, 산 연결(먼저 붙은 휴대폰)을 밀어내면 안 된다. Task 2의 상한 테스트가 규칙을 고정하고, Task 4의 실측이 상한 10이 충분한지 판정한다.
2. **정리 중인 연결로 알림이 갈 때:** 이미 완료된 emitter로 보내면 `IllegalStateException`이 난다. 이 하나 때문에 나머지 연결로의 전송이 끊기면 안 된다. Task 3의 "한 연결로의 알림 전송이 실패하면" 테스트가 고정한다.
3. **모든 연결이 죽었을 때:** `SSE_SEND_FAILED`를 내고 접속 표시도 정리해야, 다음 알림이 FCM으로 간다. Task 3의 "모든 연결로의 전송이 실패하면" 테스트가 고정한다.
4. **같은 연결의 해제가 두 번 불릴 때:** 콜백과 catch에서 두 번 불려도 등록부와 접속 표시를 두 번 건드리면 안 된다. Task 2의 "이미 빠진 커넥션" 테스트와 Task 3의 상한 테스트(밀려난 연결의 콜백)가 고정한다.
5. **마지막 연결의 해제와 새 연결이 겹칠 때:** 접속 표시가 잠시(최대 10초) 오프라인일 수 있다. 다음 하트비트의 SET으로 스스로 회복하고, 그동안 알림은 FCM으로 간다. 결정적으로 재현할 수 없어 남는 위험으로 둔다.

---

### Task 1: `SseConnection` 꺼내기

**Files:**
- Create: `notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnection.java`
- Modify: `notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnectionService.java` (내부 클래스 `Connection` 제거와 참조 교체)
- Test: `notification/src/test/java/com/example/simplescheduleapp/sse/application/SseConnectionTest.java` (생성)

**Interfaces:**
- Produces: `public final class SseConnection` 생성자 `SseConnection(SseEmitter emitter)`, 메서드 `SseEmitter emitter()`, `void stop()`, `void attachHeartbeat(ScheduledFuture<?> scheduled)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.simplescheduleapp.sse.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("SSE 커넥션 은(는)")
class SseConnectionTest {

    private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);

    @DisplayName("붙은 하트비트를 멈춘다")
    @Test
    void 붙은_하트비트를_멈춘다() {
        SseConnection connection = new SseConnection(new SseEmitter());
        connection.attachHeartbeat(heartbeat);

        connection.stop();

        verify(heartbeat).cancel(false);
    }

    @DisplayName("이미 멈춘 커넥션에 붙는 하트비트는 붙자마자 멈춘다")
    @Test
    void 멈춘_뒤에_붙는_하트비트는_바로_멈춘다() {
        SseConnection connection = new SseConnection(new SseEmitter());
        connection.stop();

        connection.attachHeartbeat(heartbeat);

        verify(heartbeat).cancel(false);
    }

    @DisplayName("멈추지 않은 커넥션의 하트비트는 그대로 둔다")
    @Test
    void 멈추지_않으면_하트비트를_그대로_둔다() {
        SseConnection connection = new SseConnection(new SseEmitter());

        connection.attachHeartbeat(heartbeat);

        verify(heartbeat, never()).cancel(false);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `memguard -- ./gradlew --max-workers=2 :notification:test --tests '*SseConnectionTest*'`
Expected: 컴파일 실패, `cannot find symbol: class SseConnection`

- [ ] **Step 3: `SseConnection`을 만든다**

```java
package com.example.simplescheduleapp.sse.application;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ScheduledFuture;

/**
 * SSE 커넥션 하나: 진행 중인 응답(emitter)과 그 하트비트 스케줄.
 *
 * <p>하트비트 핸들은 스케줄한 뒤에 붙고 다른 스레드가 멈출 수 있어, 두 필드 모두 volatile이다.
 * 멈추는 쪽({@link #stop()})은 표시를 먼저 쓰고 핸들을 읽는다. 붙이는 쪽({@link #attachHeartbeat})은
 * 핸들을 먼저 쓰고 표시를 읽는다. volatile 접근은 하나의 전체 순서를 따르므로, 둘이 엇갈려도 적어도
 * 한쪽은 상대의 쓰기를 보고 하트비트를 멈춘다. 그래서 멈춘 커넥션에 하트비트가 살아남지 않는다.
 */
public final class SseConnection {

    private final SseEmitter emitter;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile boolean stopped;

    public SseConnection(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    /** 하트비트를 멈추고, 앞으로 붙을 하트비트도 바로 멈추도록 표시한다. 여러 번 불러도 된다. */
    public void stop() {
        stopped = true;
        ScheduledFuture<?> scheduled = heartbeat;
        if (scheduled != null) {
            // false: 실행 중인 하트비트를 인터럽트하지 않고 다음 주기부터 멈춘다.
            // (하트비트 자신의 실패 경로에서 호출될 수 있으므로 자기 인터럽트를 피한다)
            scheduled.cancel(false);
        }
    }

    /** 스케줄한 하트비트를 붙인다. 그 사이에 이미 멈춘 커넥션이면 붙이자마자 멈춘다. */
    public void attachHeartbeat(ScheduledFuture<?> scheduled) {
        heartbeat = scheduled;
        if (stopped) {
            scheduled.cancel(false);
        }
    }
}
```

- [ ] **Step 4: 서비스가 `SseConnection`을 쓰게 한다**

`SseConnectionService.java`에서 아래만 바꾼다. 동작은 그대로다.
- 파일 끝의 `/** 한 논리적 커넥션. ...` 주석과 `private static final class Connection { ... }` 블록 전체를 지운다.
- `Map<Long, Connection> connections`를 `Map<Long, SseConnection> connections`로 바꾼다.
- `sendSseNotification`의 `Connection current = connections.get(targetId);`를 `SseConnection current = connections.get(targetId);`로, `current.emitter == emitter`를 `current.emitter() == emitter`로 바꾼다.
- `connect`의 `Connection connection = new Connection(emitter);`를 `SseConnection connection = new SseConnection(emitter);`로 바꾼다.
- `release(Long memberId, Connection connection)`과 `startHeartbeat(Long memberId, Connection connection)`의 매개변수 타입을 `SseConnection`으로 바꾼다.
- `startHeartbeat`의 `SseEmitter emitter = connection.emitter;`를 `SseEmitter emitter = connection.emitter();`로 바꾼다.

- [ ] **Step 5: 통과를 확인한다**

Run: `memguard -- ./gradlew --max-workers=2 :notification:test --tests '*Sse*'`
Expected: PASS. 새 `SseConnectionTest` 3건과 기존 SSE 테스트 전부(이 Task에서는 기존 테스트를 하나도 바꾸지 않는다)

- [ ] **Step 6: 커밋한다**

```bash
git add notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnection.java \
        notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnectionService.java \
        notification/src/test/java/com/example/simplescheduleapp/sse/application/SseConnectionTest.java
git commit -m "refactor(notification): SSE 커넥션을 SseConnection으로 꺼낸다"
```

### Task 2: 회원당 다중 연결 등록부 `SseConnectionRegistry`

**Files:**
- Create: `notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnectionRegistry.java`
- Test: `notification/src/test/java/com/example/simplescheduleapp/sse/application/SseConnectionRegistryTest.java` (생성)

**Interfaces:**
- Consumes: `SseConnection` (Task 1)
- Produces: `@Component public class SseConnectionRegistry` 상수 `public static final int MAX_CONNECTIONS_PER_MEMBER = 10`, 메서드 `List<SseConnection> add(Long memberId, SseConnection connection)` (밀려난 연결 목록), `OptionalInt remove(Long memberId, SseConnection connection)` (남은 수, 등록돼 있지 않았으면 빈 값), `List<SseConnection> connectionsOf(Long memberId)` (불변 스냅샷)

이 Task에서는 서비스가 아직 이 등록부를 쓰지 않는다. 더하기만 한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.example.simplescheduleapp.sse.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SSE 커넥션 등록부 은(는)")
class SseConnectionRegistryTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long OTHER_MEMBER_ID = 99L;

    private final SseConnectionRegistry registry = new SseConnectionRegistry();

    private static SseConnection newConnection() {
        return new SseConnection(new SseEmitter());
    }

    @DisplayName("한 회원의 커넥션 여럿을 들어온 순서대로 함께 담는다")
    @Test
    void 커넥션_여럿을_함께_담는다() {
        SseConnection first = newConnection();
        SseConnection second = newConnection();

        registry.add(MEMBER_ID, first);
        registry.add(MEMBER_ID, second);

        assertThat(registry.connectionsOf(MEMBER_ID)).containsExactly(first, second);
    }

    @DisplayName("커넥션이 없는 회원은 빈 목록이다")
    @Test
    void 커넥션이_없는_회원은_빈_목록이다() {
        assertThat(registry.connectionsOf(MEMBER_ID)).isEmpty();
    }

    @DisplayName("커넥션을 빼면 남은 수를 돌려주고, 마지막이면 0이다")
    @Test
    void 남은_수를_돌려주고_마지막이면_0이다() {
        SseConnection first = newConnection();
        SseConnection second = newConnection();
        registry.add(MEMBER_ID, first);
        registry.add(MEMBER_ID, second);

        assertThat(registry.remove(MEMBER_ID, first)).hasValue(1);
        assertThat(registry.connectionsOf(MEMBER_ID)).containsExactly(second);
        assertThat(registry.remove(MEMBER_ID, second)).hasValue(0);
        assertThat(registry.connectionsOf(MEMBER_ID)).isEmpty();
    }

    @DisplayName("이미 빠진 커넥션을 다시 빼면 아무것도 하지 않고 빈 값이다")
    @Test
    void 이미_빠진_커넥션은_무시한다() {
        SseConnection connection = newConnection();
        registry.add(MEMBER_ID, connection);
        registry.remove(MEMBER_ID, connection);

        assertThat(registry.remove(MEMBER_ID, connection)).isEmpty();
    }

    @DisplayName("상한을 넘으면 가장 오래된 커넥션부터 빼서 돌려준다")
    @Test
    void 상한을_넘으면_가장_오래된_커넥션부터_밀어낸다() {
        List<SseConnection> added = new ArrayList<>();
        for (int i = 0; i < SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER; i++) {
            SseConnection connection = newConnection();
            added.add(connection);
            assertThat(registry.add(MEMBER_ID, connection)).isEmpty();
        }
        SseConnection overflow = newConnection();

        List<SseConnection> evicted = registry.add(MEMBER_ID, overflow);

        assertThat(evicted).containsExactly(added.get(0));
        assertThat(registry.connectionsOf(MEMBER_ID))
                .hasSize(SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER)
                .doesNotContain(added.get(0))
                .contains(overflow);
    }

    @DisplayName("회원끼리는 서로의 커넥션에 영향을 주지 않는다")
    @Test
    void 회원끼리_독립적이다() {
        SseConnection mine = newConnection();
        SseConnection others = newConnection();
        registry.add(MEMBER_ID, mine);
        registry.add(OTHER_MEMBER_ID, others);

        assertThat(registry.remove(MEMBER_ID, mine)).hasValue(0);
        assertThat(registry.connectionsOf(OTHER_MEMBER_ID)).containsExactly(others);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `memguard -- ./gradlew --max-workers=2 :notification:test --tests '*SseConnectionRegistryTest*'`
Expected: 컴파일 실패, `cannot find symbol: class SseConnectionRegistry`

- [ ] **Step 3: `SseConnectionRegistry`를 만든다**

```java
package com.example.simplescheduleapp.sse.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이 인스턴스에 붙어 있는 살아 있는 SSE 커넥션 등록부. 회원 하나가 탭이나 기기마다 커넥션을 따로 가질 수 있다.
 *
 * <p><b>왜 infrastructure가 아닌가.</b> {@link SseConnection}이 들고 있는 emitter는 진행 중인 HTTP 응답
 * 그 자체라 프로세스 밖으로 옮길 수 없다. 외부 시스템을 부르는 어댑터가 아니라 유스케이스가 들고 있는
 * 인메모리 상태다. 프로세스 경계를 넘는 부분(누가 어디에 붙어 있는가, 알림 브로드캐스트)은 각각
 * {@code SseClientPresence}와 {@code SseMessagePublisher} 포트가 맡는다.
 *
 * <p>회원별 목록은 바꿀 때마다 새 불변 목록으로 갈아 끼운다. 갈아 끼우기는 {@link ConcurrentHashMap#compute}
 * 안에서 하므로 같은 회원의 추가와 제거가 서로를 덮어쓰지 않고, 읽는 쪽은 락 없이 그 시점의 목록을 받는다.
 */
@Component
public class SseConnectionRegistry {

    /**
     * 회원 한 명이 동시에 가질 수 있는 커넥션 수. 넘으면 가장 오래된 커넥션부터 밀어낸다.
     * 탭 여러 개와 기기 몇 대에, 막 끊겨 아직 정리되지 않은 커넥션까지 담을 여유를 둔 값이다.
     */
    public static final int MAX_CONNECTIONS_PER_MEMBER = 10;

    private final Map<Long, List<SseConnection>> connections = new ConcurrentHashMap<>();

    /** 커넥션을 더한다. 상한을 넘으면 가장 오래된 커넥션부터 빼서 돌려준다. 닫는 일은 호출한 쪽이 한다. */
    public List<SseConnection> add(Long memberId, SseConnection connection) {
        List<SseConnection> evicted = new ArrayList<>();
        connections.compute(memberId, (id, current) -> {
            List<SseConnection> next = current == null ? new ArrayList<>() : new ArrayList<>(current);
            next.add(connection);
            while (next.size() > MAX_CONNECTIONS_PER_MEMBER) {
                evicted.add(next.remove(0));
            }
            return List.copyOf(next);
        });
        return evicted;
    }

    /**
     * 커넥션을 빼고 그 회원에게 남은 커넥션 수를 돌려준다. 0이면 마지막 커넥션이었다는 뜻이다.
     * 등록돼 있지 않은 커넥션(이미 밀려났거나 두 번째 해제)이면 아무것도 하지 않고 빈 값을 돌려준다.
     */
    public OptionalInt remove(Long memberId, SseConnection connection) {
        AtomicInteger remaining = new AtomicInteger(-1);
        connections.computeIfPresent(memberId, (id, current) -> {
            if (!current.contains(connection)) {
                return current;
            }
            List<SseConnection> next = new ArrayList<>(current);
            next.remove(connection);
            remaining.set(next.size());
            return next.isEmpty() ? null : List.copyOf(next);
        });
        return remaining.get() < 0 ? OptionalInt.empty() : OptionalInt.of(remaining.get());
    }

    /** 그 회원의 커넥션 목록. 그 시점의 불변 스냅샷이고, 없으면 빈 목록이다. */
    public List<SseConnection> connectionsOf(Long memberId) {
        return connections.getOrDefault(memberId, List.of());
    }
}
```

`SseConnection`은 `equals`를 재정의하지 않으므로 `contains`와 `remove`는 같은 객체인지로 판단한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `memguard -- ./gradlew --max-workers=2 :notification:test --tests '*SseConnectionRegistryTest*'`
Expected: PASS 6건

- [ ] **Step 5: 커밋한다**

```bash
git add notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnectionRegistry.java \
        notification/src/test/java/com/example/simplescheduleapp/sse/application/SseConnectionRegistryTest.java
git commit -m "feat(notification): 회원당 SSE 커넥션 여럿을 담는 등록부를 더한다"
```

### Task 3: 서비스를 다중 연결로 바꾼다

**Files:**
- Modify: `notification/src/main/java/com/example/simplescheduleapp/sse/application/SseConnectionService.java` (전체 교체)
- Delete: `notification/src/main/java/com/example/simplescheduleapp/sse/application/SseEmitterRegistry.java`
- Delete: `notification/src/test/java/com/example/simplescheduleapp/sse/application/SseEmitterRegistryTest.java`
- Delete: `notification/src/test/java/com/example/simplescheduleapp/sse/presentation/SseSupersededConnectionTest.java`
- Modify: `notification/src/test/java/com/example/simplescheduleapp/sse/application/SseConnectionServiceTest.java` (A, B 목록)
- Modify: `notification/src/test/java/com/example/simplescheduleapp/sse/application/SseHeartbeatLifecycleTest.java` (A 목록)
- Create: `notification/src/test/java/com/example/simplescheduleapp/sse/presentation/SseMultiConnectionTest.java`

**Interfaces:**
- Consumes: `SseConnection` (Task 1), `SseConnectionRegistry` (Task 2)
- Produces: `SseConnectionService`의 공개 메서드 시그니처는 그대로다. `SseEmitter connect(Long memberId)`, `void sendSseNotification(Long targetId, String title, String body)`

- [ ] **Step 1: 다중 연결 테스트를 먼저 쓴다**

레드 확인을 위해, 이 단계에서는 `@Import`에 아직 남아 있는 `SseEmitterRegistry.class`를 함께 넣는다(Step 4에서 뺀다).

```java
package com.example.simplescheduleapp.sse.presentation;

import com.example.simplescheduleapp.common.auth.BearerTokenExtractor;
import com.example.simplescheduleapp.common.auth.TokenService;
import com.example.simplescheduleapp.common.exception.ApplicationException;
import com.example.simplescheduleapp.sse.application.SseConnection;
import com.example.simplescheduleapp.sse.application.SseConnectionRegistry;
import com.example.simplescheduleapp.sse.application.SseConnectionService;
import com.example.simplescheduleapp.sse.application.SseEmitterRegistry;
import com.example.simplescheduleapp.sse.application.port.out.SseClientPresence;
import com.example.simplescheduleapp.sse.exception.SseExceptionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static com.example.simplescheduleapp.config.ThreadPoolConfig.SSE_HEARTBEAT_SCHEDULER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 같은 회원이 여러 연결(탭, 기기)을 가질 때의 SSE 동작.
 *
 * <p>컨트롤러, 서비스, 등록부를 실제 빈으로 띄우고 MockMvc의 비동기 요청을 쓴다. 그래야 컨테이너가
 * 비동기 요청을 끝낼 때 Spring이 emitter 콜백을 부르는 실제 배선을 지난다. 하트비트 스케줄러만 목이라
 * 하트비트는 테스트가 직접 실행한다. 이 배선이 정말 콜백을 부르는지는 양성 대조가 보증한다. 그것이
 * 없으면 "건드리지 않았다"는 단언이 콜백이 안 불려서 통과할 수 있다.
 *
 * <p>증명하지 않는 것: 실제 Tomcat이 클라이언트 이탈을 언제 알아채는지. 그건 계획 Task 4의 실측으로 본다.
 */
@DisplayName("SSE 회원당 다중 연결 은(는)")
@WebMvcTest(SseController.class)
@Import({SseConnectionService.class, SseConnectionRegistry.class, SseEmitterRegistry.class})
class SseMultiConnectionTest {

    private static final Long MEMBER_ID = 12L;
    private static final String BEARER = "Bearer token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseConnectionService sseConnectionService;

    @Autowired
    private SseConnectionRegistry sseConnectionRegistry;

    @MockitoBean(name = SSE_HEARTBEAT_SCHEDULER)
    private TaskScheduler taskScheduler;

    @MockitoBean
    private SseClientPresence sseClientPresence;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private BearerTokenExtractor bearerTokenExtractor;

    /** 연결마다 스케줄된 하트비트 핸들과 태스크. 순서는 연결 순서와 같다. */
    private final List<ScheduledFuture<?>> heartbeats = new ArrayList<>();
    private final List<Runnable> heartbeatTasks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(bearerTokenExtractor.extract(BEARER)).willReturn("token");
        given(tokenService.extractMemberId("token")).willReturn(MEMBER_ID);
        doAnswer(invocation -> {
            heartbeatTasks.add(invocation.getArgument(0));
            ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
            heartbeats.add(heartbeat);
            return heartbeat;
        }).when(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    private MvcResult subscribe() throws Exception {
        return mockMvc.perform(get("/sse-stream")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static int notificationsIn(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.split("event:notification", -1).length - 1;
    }

    @DisplayName("두 번째 연결은 첫 연결의 하트비트를 멈추지 않는다")
    @Test
    void 두_번째_연결은_첫_연결을_밀어내지_않는다() throws Exception {
        subscribe();
        subscribe();

        verify(heartbeats.get(0), never()).cancel(anyBoolean());
        verify(heartbeats.get(1), never()).cancel(anyBoolean());
    }

    @DisplayName("알림은 그 회원의 모든 연결로 간다")
    @Test
    void 알림은_모든_연결로_간다() throws Exception {
        MvcResult first = subscribe();
        MvcResult second = subscribe();

        sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문");

        assertThat(notificationsIn(first)).isEqualTo(1);
        assertThat(notificationsIn(second)).isEqualTo(1);
    }

    @DisplayName("한 연결이 끝나도 다른 연결은 그대로고 알림도 받는다")
    @Test
    void 한_연결이_끝나도_다른_연결은_그대로다() throws Exception {
        MvcResult first = subscribe();
        MvcResult second = subscribe();

        first.getRequest().getAsyncContext().complete();
        sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문");

        verify(heartbeats.get(0), atLeastOnce()).cancel(false);
        verify(heartbeats.get(1), never()).cancel(anyBoolean());
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
        assertThat(notificationsIn(second)).isEqualTo(1);
    }

    @DisplayName("한 연결의 하트비트가 쓰기에 실패해도 다른 연결은 그대로다")
    @Test
    void 한_연결의_하트비트_실패는_다른_연결에_영향이_없다() throws Exception {
        subscribe();
        SseConnection first = sseConnectionRegistry.connectionsOf(MEMBER_ID).get(0);
        subscribe();

        first.emitter().complete();
        heartbeatTasks.get(0).run();

        // 하트비트 쓰기가 실패해 catch 경로를 탔다는 증거다. 쓰기가 성공했다면 접속 표시를 갱신했다.
        verify(sseClientPresence, never()).refreshConnection(MEMBER_ID);
        verify(heartbeats.get(0), atLeastOnce()).cancel(false);
        verify(heartbeats.get(1), never()).cancel(anyBoolean());
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
        assertThat(sseConnectionRegistry.connectionsOf(MEMBER_ID)).hasSize(1).doesNotContain(first);
    }

    @DisplayName("한 연결로의 알림 전송이 실패하면 그 연결만 정리하고 나머지로는 보낸다")
    @Test
    void 한_연결로의_전송_실패는_그_연결만_정리한다() throws Exception {
        subscribe();
        SseConnection broken = sseConnectionRegistry.connectionsOf(MEMBER_ID).get(0);
        MvcResult healthy = subscribe();
        broken.emitter().complete();   // 이 연결로 쓰면 IllegalStateException이 난다

        sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문");

        assertThat(notificationsIn(healthy)).isEqualTo(1);
        assertThat(sseConnectionRegistry.connectionsOf(MEMBER_ID)).hasSize(1).doesNotContain(broken);
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
    }

    @DisplayName("모든 연결로의 전송이 실패하면 SSE_SEND_FAILED이고 접속 표시도 정리한다")
    @Test
    void 모든_연결이_실패하면_전송_실패이고_접속_표시를_정리한다() throws Exception {
        subscribe();
        sseConnectionRegistry.connectionsOf(MEMBER_ID).get(0).emitter().complete();

        assertThatThrownBy(() -> sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(SseExceptionCode.SSE_SEND_FAILED.name());
        verify(sseClientPresence, times(1)).unsubscribeClient(MEMBER_ID);
        assertThat(sseConnectionRegistry.connectionsOf(MEMBER_ID)).isEmpty();
    }

    @DisplayName("양성 대조: 마지막 연결이 끝나면 접속 표시까지 정리한다")
    @Test
    void 마지막_연결이_끝나면_접속_표시까지_정리한다() throws Exception {
        MvcResult first = subscribe();
        MvcResult second = subscribe();

        first.getRequest().getAsyncContext().complete();
        second.getRequest().getAsyncContext().complete();

        verify(sseClientPresence, times(1)).unsubscribeClient(MEMBER_ID);
        assertThat(sseConnectionRegistry.connectionsOf(MEMBER_ID)).isEmpty();
        assertThatThrownBy(() -> sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(SseExceptionCode.SSE_NOT_FOUND.name());
    }

    @DisplayName("회원당 상한을 넘으면 가장 오래된 연결을 닫는다")
    @Test
    void 상한을_넘으면_가장_오래된_연결을_닫는다() throws Exception {
        subscribe();
        SseConnection oldest = sseConnectionRegistry.connectionsOf(MEMBER_ID).get(0);
        for (int i = 0; i < SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER; i++) {
            subscribe();
        }

        assertThat(sseConnectionRegistry.connectionsOf(MEMBER_ID))
                .hasSize(SseConnectionRegistry.MAX_CONNECTIONS_PER_MEMBER)
                .doesNotContain(oldest);
        verify(heartbeats.get(0), atLeastOnce()).cancel(false);
        verify(sseClientPresence, never()).unsubscribeClient(MEMBER_ID);
    }

    @DisplayName("연결이 없는 회원에게 보내면 SSE_NOT_FOUND다")
    @Test
    void 연결이_없으면_찾을_수_없다() {
        assertThatThrownBy(() -> sseConnectionService.sendSseNotification(MEMBER_ID, "제목", "본문"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining(SseExceptionCode.SSE_NOT_FOUND.name());
    }
}
```

- [ ] **Step 2: 레드를 확인한다 (서비스는 아직 단일 연결)**

Run: `memguard -- ./gradlew --max-workers=2 :notification:test --tests '*SseMultiConnectionTest*'`
Expected: 9건 중 6건 실패.
- 행동 때문에 실패하는 것: "두 번째 연결은 첫 연결의 하트비트를 멈추지 않는다"(첫 하트비트가 취소됨), "알림은 그 회원의 모든 연결로 간다"(첫 연결이 0건)
- 아직 연결되지 않은 등록부 때문에 실패하는 것: `connectionsOf(...).get(0)`을 쓰는 4건(`IndexOutOfBoundsException`)
- 통과하는 것: 양성 대조, "한 연결이 끝나도 다른 연결은 그대로", "연결이 없는 회원" (단일 연결에서도 참)

- [ ] **Step 3: 서비스를 바꾼다 (파일 전체 교체)**

```java
package com.example.simplescheduleapp.sse.application;

import com.example.simplescheduleapp.common.exception.ApplicationException;
import com.example.simplescheduleapp.sse.application.port.out.SseClientPresence;
import com.example.simplescheduleapp.sse.exception.SseExceptionCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledFuture;

import static com.example.simplescheduleapp.config.ThreadPoolConfig.SSE_HEARTBEAT_SCHEDULER;

@Slf4j
@RequiredArgsConstructor
@Service
public class SseConnectionService {

    private static final Long DEFAULT_TIMEOUT = 60L * 1000 * 60;
    private static final long INITIAL_DELAY = 10L;
    private static final long PERIOD = 10L;

    /**
     * 알림 이벤트의 SSE {@code event:} 이름(고정).
     *
     * <p>SSE의 {@code event:} 필드는 <b>이벤트 타입 구분자</b>이고, 클라이언트는 그 이름으로
     * 리스너를 등록한다({@code EventSource.addEventListener(name, ...)}, 또는 이름이 없을 때만
     * {@code onmessage}). 여기에 강의 제목처럼 <b>런타임에 결정되는 값</b>을 넣으면 클라이언트가
     * 이름을 미리 알 수 없어 어떤 리스너로도 수신할 수 없다. 그래서 이름은 고정하고
     * 제목·본문은 데이터(JSON)로 보낸다.
     */
    private static final String NOTIFICATION_EVENT_NAME = "notification";

    @Qualifier(SSE_HEARTBEAT_SCHEDULER)
    private final TaskScheduler taskScheduler;

    /**
     * 회원별 커넥션 등록부. 한 회원이 탭이나 기기마다 커넥션을 따로 가진다. 한 논리적 커넥션의 자원은
     * emitter, Redis 구독, 하트비트 3가지이고, 커넥션이 끝날 때 자기 몫만 정리한다. 회원 단위 자원인
     * 접속 표시는 그 회원의 마지막 커넥션이 끝날 때만 해제한다.
     *
     * <p>예전에는 회원당 커넥션이 하나라서 같은 계정의 두 번째 클라이언트가 첫 클라이언트를 밀어냈다.
     * 밀려난 쪽은 하트비트가 멈춰 45초 뒤 다시 붙고 다시 상대를 밀어내, 둘이 번갈아 끊겼다(2026-10-13 관측).
     */
    private final SseConnectionRegistry sseConnectionRegistry;
    private final SseClientPresence sseClientPresence;

    public void sendSseNotification(Long targetId, String title, String body) {
        List<SseConnection> targets = sseConnectionRegistry.connectionsOf(targetId);
        if (targets.isEmpty()) {
            throw new ApplicationException(SseExceptionCode.SSE_NOT_FOUND);
        }

        int delivered = 0;
        for (SseConnection target : targets) {
            try {
                // 이벤트 빌더는 한 번 쓰면 끝이라 커넥션마다 새로 만든다
                target.emitter().send(SseEmitter.event()
                        .name(NOTIFICATION_EVENT_NAME)
                        .data(Map.of("title", title, "body", body), MediaType.APPLICATION_JSON));
                delivered++;
            } catch (IOException | IllegalStateException e) {
                // IOException: 이 커넥션만 끊겼다. IllegalStateException: 이 커넥션이 이미 끝났다.
                // 이 커넥션만 정리하고 나머지 커넥션으로 계속 보낸다.
                log.warn("SSE 커넥션 하나로 전송 실패, 그 커넥션을 정리한다 - targetId: {}", targetId);
                release(targetId, target);
            }
        }

        if (delivered == 0) {
            log.error("targetId: {} 의 모든 SSE 커넥션으로 전송 실패: {}", targetId, body);
            throw new ApplicationException(SseExceptionCode.SSE_SEND_FAILED);
        }
        log.info("SSE 이벤트 전송 성공 - targetId: {}, 전달: {}/{}, title: {}, body: {}",
                targetId, delivered, targets.size(), title, body);
    }

    public SseEmitter connect(Long memberId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        SseConnection connection = new SseConnection(emitter);

        List<SseConnection> evicted = sseConnectionRegistry.add(memberId, connection);
        sseClientPresence.subscribeClient(memberId);
        if (!evicted.isEmpty()) {
            log.warn("회원당 SSE 커넥션 상한을 넘어 가장 오래된 커넥션을 닫는다 - memberId: {}, 닫은 수: {}",
                    memberId, evicted.size());
            evicted.forEach(SseConnectionService::close);
        }

        // 콜백은 회원이 아니라 이 커넥션을 정리한다
        emitter.onCompletion(() -> release(memberId, connection));

        emitter.onTimeout(() -> {
            emitter.complete();
            release(memberId, connection);
        });

        emitter.onError((ex) -> {
            emitter.completeWithError(ex);
            release(memberId, connection);
        });

        // 초기 연결 메시지 및 하트비트 시작
        try {
            emitter.send(SseEmitter.event()
                    .name("connect")
                    .data("SSE connected for memberId: " + memberId));
            startHeartbeat(memberId, connection);
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        log.info("SSE 커넥션 등록 - memberId: {}, 커넥션 수: {}",
                memberId, sseConnectionRegistry.connectionsOf(memberId).size());
        return emitter;
    }

    /** 상한을 넘어 밀려난 커넥션을 닫는다. 클라이언트는 닫힌 스트림을 보고 다시 붙는다. */
    private static void close(SseConnection evicted) {
        evicted.stop();
        evicted.emitter().complete();
    }

    /**
     * 커넥션 하나를 정리한다. 자기 하트비트는 언제나 멈추고 등록부에서 뺀다. 그 회원의 마지막 커넥션이었을
     * 때만 접속 표시를 해제한다. 이미 빠진 커넥션(밀려났거나 두 번째 해제)이면 등록부와 접속 표시는 건드리지 않는다.
     */
    private void release(Long memberId, SseConnection connection) {
        connection.stop();

        OptionalInt remaining = sseConnectionRegistry.remove(memberId, connection);
        if (remaining.isEmpty()) {
            return;
        }
        log.info("SSE 커넥션 해제 - memberId: {}, 남은 커넥션 수: {}", memberId, remaining.getAsInt());
        if (remaining.getAsInt() == 0) {
            sseClientPresence.unsubscribeClient(memberId);
        }
    }

    private void startHeartbeat(Long memberId, SseConnection connection) {
        SseEmitter emitter = connection.emitter();
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("연결 끊김 방지"));
                sseClientPresence.refreshConnection(memberId);
            } catch (IOException | IllegalStateException e) {
                // IOException: 클라이언트 연결이 끊어져 전송 실패.
                // IllegalStateException: emitter가 이미 완료(완료/타임아웃/에러 콜백 등)되어
                //   "ResponseBodyEmitter has already completed"로 실패. 재시도해도 영원히 실패하므로
                //   여기서 정리하지 않으면 스케줄이 10초마다 이 예외를 영구히 반복한다.
                emitter.complete();
                release(memberId, connection);
            }
            },
                Instant.now().plusSeconds(INITIAL_DELAY),
                Duration.ofSeconds(PERIOD));

        // 스케줄 핸들을 커넥션에 붙여야 테어다운(완료/타임아웃/에러)에서 취소할 수 있다.
        // 스케줄하는 사이에 이미 정리된 커넥션이면 붙이는 쪽이 바로 멈춘다.
        connection.attachHeartbeat(heartbeat);
    }
}
```

- [ ] **Step 4: 옛 등록부와 그 테스트를 지우고, 새 테스트의 `@Import`를 정리한다**

```bash
git rm notification/src/main/java/com/example/simplescheduleapp/sse/application/SseEmitterRegistry.java \
       notification/src/test/java/com/example/simplescheduleapp/sse/application/SseEmitterRegistryTest.java \
       notification/src/test/java/com/example/simplescheduleapp/sse/presentation/SseSupersededConnectionTest.java
```

`SseMultiConnectionTest`에서 `import ...SseEmitterRegistry;` 줄을 지우고, `@Import`를 `@Import({SseConnectionService.class, SseConnectionRegistry.class})`로 바꾼다.

- [ ] **Step 5: `SseConnectionServiceTest`를 바꾼다 (A, B 목록)**

필드 `@Mock private SseEmitterRegistry sseEmitterRegistry;`를 `@Mock private SseConnectionRegistry sseConnectionRegistry;`로 바꾸고, import에서 `SseEmitterRegistry`를 지운다. 아래 import를 더한다.

```java
import java.util.List;
import java.util.OptionalInt;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
```

첫 테스트(B 목록)는 given 블록과 then 블록만 바꾼다.

```java
        // given: connect()로 SSE 연결을 맺으면 하트비트가 스케줄러(mock)에 등록된다.
        Long memberId = 1L;
        ArgumentCaptor<Runnable> heartbeatTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduledFuture)
                .when(taskScheduler)
                .scheduleAtFixedRate(heartbeatTaskCaptor.capture(), any(Instant.class), any(Duration.class));
        // 이 연결이 그 회원의 마지막 연결이라고 등록부가 답한다
        given(sseConnectionRegistry.remove(eq(memberId), any(SseConnection.class))).willReturn(OptionalInt.of(0));
```

```java
        // then: 하트비트 스케줄이 취소되고, SSE 자원(등록부 엔트리, Redis 구독)이 해제되어야 한다.
        verify(scheduledFuture, times(1)).cancel(false);
        verify(sseConnectionRegistry, times(1)).remove(eq(memberId), any(SseConnection.class));
        verify(sseClientPresence, times(1)).unsubscribeClient(memberId);
        // send()가 이미 완료 상태에서 실패했으므로 refreshConnection까지는 도달하지 않는다.
        verify(sseClientPresence, never()).refreshConnection(memberId);
```

둘째 테스트(A 목록)는 메서드 전체를 바꾼다.

```java
    @DisplayName("같은 회원의 두 번째 연결은 첫 연결의 하트비트를 멈추지 않는다")
    @Test
    void 두_번째_연결은_첫_연결의_하트비트를_멈추지_않는다() {
        // given: 같은 회원이 두 번 연결한다(탭 둘, 또는 웹과 Android). 스케줄러는 매번 다른 핸들을 반환한다.
        Long memberId = 2L;
        doReturn(scheduledFuture, secondScheduledFuture)
                .when(taskScheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));

        // when
        sseConnectionService.connect(memberId);
        sseConnectionService.connect(memberId);

        // then: 두 연결은 함께 산다. 어느 하트비트도 멈추지 않는다.
        verify(scheduledFuture, never()).cancel(anyBoolean());
        verify(secondScheduledFuture, never()).cancel(anyBoolean());
    }
```

셋째 테스트(B 목록)는 스텁 한 줄만 바꾼다.

```java
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(List.of(new SseConnection(emitter))).when(sseConnectionRegistry).connectionsOf(memberId);
```

- [ ] **Step 6: `SseHeartbeatLifecycleTest`를 바꾼다 (A 목록, 파일 전체 교체)**

```java
package com.example.simplescheduleapp.sse.application;

import com.example.simplescheduleapp.sse.application.port.out.SseClientPresence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SSE 하트비트 스케줄의 라이프사이클 검증 (ADR-0004 / 구조 리뷰 HIGH).
 *
 * <p>한 논리적 커넥션의 자원은 emitter, Redis 구독, 하트비트 3가지다. 하트비트 스케줄 핸들을 보관하지
 * 않으면 커넥션이 끝나도 취소할 수 없어 <b>커넥션마다 하트비트가 영구히 누적</b>되고, 각 태스크가 emitter
 * 참조를 붙들어 메모리도 함께 샌다(CLAUDE.md의 "Emitter 커넥션 고갈 방지" 우려 지점).
 *
 * <p>회원당 커넥션이 여럿인 지금은 재연결이 이전 하트비트를 멈추지 않는다(같은 회원의 다른 탭일 수 있다).
 * 누적을 막는 것은 <b>커넥션 단위 정리</b>다: 끝난 커넥션의 하트비트는 쓰기에 실패하는 순간 멈춘다.
 */
@DisplayName("SSE 하트비트 라이프사이클")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SseHeartbeatLifecycleTest {

    @Mock
    TaskScheduler taskScheduler;

    @Mock
    SseConnectionRegistry sseConnectionRegistry;

    @Mock
    SseClientPresence sseClientPresence;

    @Mock
    ScheduledFuture<Object> firstHeartbeat;

    @Mock
    ScheduledFuture<Object> secondHeartbeat;

    @InjectMocks
    SseConnectionService sseConnectionService;

    @Test
    void 재연결해도_이전_하트비트는_그_연결이_끝날_때_멈춘다() {
        // given: 연결마다 서로 다른 스케줄 핸들이 반환된다
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        given(taskScheduler.scheduleAtFixedRate(tasks.capture(), any(Instant.class), any(Duration.class)))
                .willReturn((ScheduledFuture) firstHeartbeat, (ScheduledFuture) secondHeartbeat);

        // when: 같은 회원이 두 번 연결한다
        SseEmitter first = sseConnectionService.connect(1L);
        sseConnectionService.connect(1L);

        // then: 재연결만으로는 이전 하트비트를 멈추지 않는다
        verify(firstHeartbeat, never()).cancel(anyBoolean());

        // when: 이전 연결이 끝나 그 하트비트의 쓰기가 실패한다
        first.complete();
        tasks.getAllValues().get(0).run();

        // then: 그 하트비트만 멈춘다(누적 방지)
        verify(firstHeartbeat).cancel(false);
        verify(secondHeartbeat, never()).cancel(anyBoolean());
    }
}
```

- [ ] **Step 7: 통과를 확인한다**

Run: `memguard -- ./gradlew --max-workers=2 :notification:test`
Expected: PASS. notification 테스트 수는 65건(이번 브랜치 전)에서 바뀐 수만큼 달라진다. 지운 8건, 더한 18건(SseConnectionTest 3, SseConnectionRegistryTest 6, SseMultiConnectionTest 9)이라 75건이 기대값이다. 실패 0건

- [ ] **Step 8: `buildAll`을 돌린다**

Run: `memguard -- ./gradlew --max-workers=2 buildAll`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋한다**

```bash
git add -A notification/src
git commit -m "feat(notification): 회원당 SSE 커넥션 여럿을 두고 알림을 모든 커넥션으로 보낸다"
```

### Task 4: 실서버 측정과 PR

MockMvc는 실제 Tomcat이 끊긴 클라이언트를 언제 알아채는지 보여 주지 못한다. 상한 10과 수용 기준이 그 값에 달려 있으므로 로컬 실서버로 잰다.

**Files:** 없음 (측정 결과는 PR 본문에 남긴다)

- [ ] **Step 1: 로컬 인프라와 앱을 띄운다**

```bash
cd /Users/jsh14/Work/simple-schedule-app/simple-schedule-app-be/.wt-ssa-analysis && docker compose up -d
```

```bash
cd /Users/jsh14/Work/simple-schedule-app/simple-schedule-app-be/.wt-ssa-analysis && memguard -- ./gradlew --max-workers=2 :app:bootRun > /tmp/ssa-bootrun.log 2>&1
```

두 번째 명령은 백그라운드로 띄우고, `curl -s -o /dev/null -w '%{http_code}' 'http://localhost:8080/lectures/search?keyword=x'`가 401을 줄 때까지 기다린다.

- [ ] **Step 2: 로컬 테스트 회원의 토큰을 얻는다**

로컬 DB에만 있는 테스트 계정을 쓴다. 계정이 없으면 `POST /students`로 로컬에서 만든다. 계정 값은 채팅과 커밋에 남기지 않고 셸 변수로만 쓴다.

```bash
LOGIN=$(curl -s -X POST http://localhost:8080/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SSA_LOCAL_USER\",\"password\":\"$SSA_LOCAL_PASS\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.accessToken'); MEMBER=$(echo "$LOGIN" | jq -r '.memberId')
```

- [ ] **Step 3: 같은 회원으로 스트림 둘을 3분 동안 연다 (탭 둘 흉내)**

```bash
curl -sN -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' http://localhost:8080/sse-stream > /tmp/sse-a.log &
A=$!
curl -sN -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' http://localhost:8080/sse-stream > /tmp/sse-b.log &
B=$!
```

3분 뒤 `grep -c 'event:heartbeat' /tmp/sse-a.log /tmp/sse-b.log`를 본다.
Expected: 두 파일 모두 17 이상(10초마다 하나). 수정 전 코드라면 한쪽은 0에 가깝다.

- [ ] **Step 4: 한쪽을 끊고 정리 지연을 잰다**

```bash
date '+%H:%M:%S'; kill $A
```

끊은 시각부터 `grep 'SSE 커넥션 해제' /tmp/ssa-bootrun.log | tail -1`에 `남은 커넥션 수: 1`이 찍힌 시각까지를 정리 지연으로 기록한다. 그동안 `/tmp/sse-b.log`의 하트비트는 계속 늘어야 한다.

- [ ] **Step 5: 알림이 남은 스트림에 닿는지 본다**

```bash
docker exec sse-redis redis-cli PUBLISH sse-notification "{\"senderId\":1,\"targetId\":$MEMBER,\"title\":\"측정\",\"body\":\"다중 연결\"}"
```

Expected: `/tmp/sse-b.log`에 `event:notification`이 한 번 찍힌다.

- [ ] **Step 6: 재연결 폭주에서 오래된 산 연결이 밀려나지 않는지 본다**

B를 살려 둔 채, 짧은 연결 12개를 1초 간격으로 열었다 끊는다.

```bash
for i in $(seq 1 12); do
  curl -sN -m 1 -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' http://localhost:8080/sse-stream > /dev/null
done
```

Expected: 앱 로그에 `상한을 넘어 가장 오래된 커넥션을 닫는다` WARN이 없거나, 있더라도 B의 하트비트가 끊기지 않는다. B가 밀려났다면 Step 4에서 잰 정리 지연과 함께 상한을 다시 정한다.

- [ ] **Step 7: 정리하고 PR을 연다**

`kill $B`, 앱 프로세스를 끄고 `docker compose down`은 하지 않는다(다른 세션이 쓸 수 있다). PR 본문에 Step 3~6의 결과(하트비트 수, 정리 지연 초, WARN 유무)를 적는다.

## 수용 기준

- 같은 회원의 스트림 둘이 3분 동안 모두 하트비트를 받는다(각 17개 이상, 15초 넘는 공백 없음).
- 한 스트림을 끊어도 다른 스트림은 계속 받는다. 정리 지연을 초 단위로 기록한다.
- 알림이 열린 스트림 모두에 닿는다.
- 재연결 폭주에서 오래된 산 스트림이 밀려나지 않는다.
- 운영 반영 뒤 오너 확인: 같은 계정으로 탭 둘과 Android 포그라운드를 10분 동안 열어도 어느 쪽에도 `No activity within 45000 milliseconds`가 찍히지 않는다.

## 남는 위험

- **백그라운드 기기 푸시:** 웹 탭이 열린 채 Android가 백그라운드면 휴대폰은 알림을 받지 못한다. 접속 표시를 기기 단위로 바꾸거나, 모바일에는 늘 FCM을 보내는 정책이 필요하다. 오너 결정 항목으로 따로 올린다.
- **죽은 연결의 집계:** 정리되기 전의 죽은 연결도 온라인으로 센다. 알림 팬아웃의 "1건 이상 전달"은 끊긴 소켓의 버퍼에 들어간 쓰기도 성공으로 센다. 수정 전과 같지만 다중 연결에서는 더 자주 생긴다.
- **접속 표시 순서 경쟁:** 마지막 연결의 해제와 새 연결이 겹치면 최대 10초 오프라인이다. 다음 하트비트의 SET으로 회복하고, 그동안 알림은 FCM으로 간다.
- **상한 10:** 실측 전 값이다. Task 4의 정리 지연이 길면 다시 정한다.
- **인스턴스가 둘 이상이 되면:** 접속 표시 해제가 다른 인스턴스의 연결을 모른다. 다음 하트비트의 SET으로 10초 안에 회복한다. 지금은 인스턴스가 하나다.

## 롤백

PR을 되돌린다. 스키마, Redis 키, 클라이언트 변경이 없어서 되돌리면 단일 연결 동작으로 돌아간다.
