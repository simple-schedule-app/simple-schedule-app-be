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
