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
