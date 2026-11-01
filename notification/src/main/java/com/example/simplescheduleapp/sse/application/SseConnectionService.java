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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final SseEmitterRegistry sseEmitterRegistry;
    private final SseClientPresence sseClientPresence;

    /**
     * 회원별 <b>현재</b> 커넥션. 한 논리적 커넥션의 자원은 emitter, Redis 구독, 하트비트 3가지이며,
     * 테어다운 시 <b>셋 다</b> 정리해야 한다. 하트비트 핸들을 보관하지 않으면 스케줄을 취소할 수 없어
     * 커넥션이 끝나도 하트비트가 영구히 남는다(커넥션당 1개씩 누적).
     *
     * <p><b>정리는 그 커넥션이 아직 현재일 때만 회원 단위 자원을 지운다.</b> 레지스트리와 접속 표시는
     * 회원 하나에 한 칸뿐이라, 같은 회원이 다시 연결하면 그 칸은 새 커넥션 차지가 된다. 밀려난 옛
     * 커넥션의 완료, 타임아웃, 에러 콜백이나 늦게 실패한 하트비트가 회원 ID만 보고 칸을 지우면, 살아 있는
     * 새 커넥션의 하트비트가 멈추고 알림 대상에서도 빠진다. 클라이언트에는 열린 채 침묵하는 스트림으로
     * 보인다. 2026-10-13 웹에서 관측된 45초 주기 끊김의 후보 경로 중 하나다({@code SseSupersededConnectionTest}).
     */
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();

    public void sendSseNotification(Long targetId, String title, String body) {
        SseEmitter emitter = sseEmitterRegistry.get(targetId);

        try {
            emitter.send(SseEmitter.event()
                    .name(NOTIFICATION_EVENT_NAME)
                    .data(Map.of("title", title, "body", body), MediaType.APPLICATION_JSON));
            log.info("SSE 이벤트 전송 성공 - targetId: {}, title: {}, body: {}",
                    targetId, title, body);
        } catch (IOException e) {
            log.error("targetId: {} 에게 SSE 이벤트 전송 실패: {}", targetId, body);
            // 실패한 emitter가 아직 현재 커넥션의 것일 때만 정리한다. 알림 스레드가 emitter를 꺼낸 뒤
            // 같은 회원이 다시 연결했다면 레지스트리의 칸은 이미 새 커넥션 차지다.
            Connection current = connections.get(targetId);
            if (current != null && current.emitter == emitter) {
                release(targetId, current);
            }
            throw new ApplicationException(SseExceptionCode.SSE_SEND_FAILED);
        }
    }

    public SseEmitter connect(Long memberId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        Connection connection = new Connection(emitter);

        // 재연결 시 이전 커넥션의 하트비트를 취소하고 새 커넥션을 등록한다. 취소하지 않으면 emitter만
        // 교체되고 이전 하트비트가 살아남아 커넥션마다 스케줄이 누적된다. 둘을 회원 단위로 원자적으로
        // 하는 이유는, 같은 회원의 연결 둘이 겹칠 때 한쪽의 하트비트가 아무도 취소하지 못한 채 남기 때문이다.
        connections.compute(memberId, (id, previous) -> {
            if (previous != null) {
                previous.stop();
            }
            sseEmitterRegistry.save(id, emitter);
            return connection;
        });
        sseClientPresence.subscribeClient(memberId);

        // 콜백은 회원이 아니라 이 커넥션을 정리한다 (release가 현재 커넥션인지 확인한다)
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

        return emitter;
    }

    /**
     * 커넥션 하나를 정리한다. 자기 하트비트는 언제나 멈추고, 회원 단위 자원(레지스트리 엔트리와 Redis 구독)은
     * 이 커넥션이 아직 현재일 때만 해제한다. 이미 밀려났다면 그 칸은 새 커넥션의 것이다.
     */
    private void release(Long memberId, Connection connection) {
        connection.stop();

        // 현재인지 확인과 레지스트리 삭제를 원자적으로 한다. 사이가 벌어지면 그 틈에 등록된 새 커넥션을 지운다.
        AtomicBoolean wasCurrent = new AtomicBoolean(false);
        connections.computeIfPresent(memberId, (id, current) -> {
            if (current != connection) {
                return current;
            }
            sseEmitterRegistry.delete(id);
            wasCurrent.set(true);
            return null;
        });

        if (!wasCurrent.get()) {
            log.debug("이미 교체된 SSE 커넥션이라 회원 단위 자원은 두고 간다 - memberId: {}", memberId);
            return;
        }
        sseClientPresence.unsubscribeClient(memberId);
        log.debug("SSE 자원 해제 완료 - memberId: {}", memberId);
    }

    private void startHeartbeat(Long memberId, Connection connection) {
        SseEmitter emitter = connection.emitter;
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("연결 끊김 방지"));
                sseClientPresence.refreshConnection(memberId);
            } catch (IOException | IllegalStateException e) {
                // IOException: 클라이언트 연결이 끊어져 전송 실패.
                // IllegalStateException: emitter가 이미 완료(완료/타임아웃/에러 콜백 등)되어
                //   "ResponseBodyEmitter has already completed"로 실패 — 재시도해도 영원히 실패하므로
                //   여기서 정리하지 않으면 스케줄이 10초마다 이 예외를 영구히 반복한다.
                emitter.complete();
                release(memberId, connection);
            }
            },
                Instant.now().plusSeconds(INITIAL_DELAY),
                Duration.ofSeconds(PERIOD));

        // 스케줄 핸들을 커넥션에 붙여야 테어다운(완료/타임아웃/에러/재연결)에서 취소할 수 있다.
        // 스케줄하는 사이에 이미 밀려났거나 정리된 커넥션이면 붙이는 쪽이 바로 멈춘다.
        connection.attachHeartbeat(heartbeat);
    }

    /**
     * 한 논리적 커넥션. 하트비트 핸들은 스케줄한 뒤에 붙고 다른 스레드가 멈출 수 있어, 두 필드 모두 volatile이다.
     *
     * <p>멈추는 쪽({@link #stop()})은 표시를 먼저 쓰고 핸들을 읽는다. 붙이는 쪽({@link #attachHeartbeat})은
     * 핸들을 먼저 쓰고 표시를 읽는다. volatile 접근은 하나의 전체 순서를 따르므로, 둘이 엇갈려도 적어도
     * 한쪽은 상대의 쓰기를 보고 하트비트를 멈춘다. 그래서 멈춘 커넥션에 하트비트가 살아남지 않는다.
     */
    private static final class Connection {

        private final SseEmitter emitter;
        private volatile ScheduledFuture<?> heartbeat;
        private volatile boolean stopped;

        private Connection(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /** 하트비트를 멈추고, 앞으로 붙을 하트비트도 바로 멈추도록 표시한다. 여러 번 불러도 된다. */
        private void stop() {
            stopped = true;
            ScheduledFuture<?> scheduled = heartbeat;
            if (scheduled != null) {
                // false: 실행 중인 하트비트를 인터럽트하지 않고 다음 주기부터 멈춘다.
                // (하트비트 자신의 실패 경로에서 호출될 수 있으므로 자기 인터럽트를 피한다)
                scheduled.cancel(false);
            }
        }

        /** 스케줄한 하트비트를 붙인다. 그 사이에 이미 멈춘 커넥션이면 붙이자마자 멈춘다. */
        private void attachHeartbeat(ScheduledFuture<?> scheduled) {
            heartbeat = scheduled;
            if (stopped) {
                scheduled.cancel(false);
            }
        }
    }
}
