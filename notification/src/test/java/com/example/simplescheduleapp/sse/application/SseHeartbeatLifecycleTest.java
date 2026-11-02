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
