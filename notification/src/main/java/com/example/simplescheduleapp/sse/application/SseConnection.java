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
