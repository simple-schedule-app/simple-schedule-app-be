package com.example.simplescheduleapp.sse.application;

import com.example.simplescheduleapp.sse.application.port.out.SseClientPresence;
import com.example.simplescheduleapp.support.UnitTest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SseConnectionServiceTest extends UnitTest {

    @InjectMocks
    private SseConnectionService sseConnectionService;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private SseConnectionRegistry sseConnectionRegistry;

    @Mock
    private SseClientPresence sseClientPresence;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    @Mock
    private ScheduledFuture<?> secondScheduledFuture;

    @DisplayName("emitter가 이미 완료된 상태에서 하트비트가 발사되면 예외를 삼키지 않고 스케줄을 취소 + SSE 자원을 정리한다")
    @Test
    void 완료된_emitter에_하트비트가_발사되면_스케줄을_취소하고_자원을_정리한다() {
        // given: connect()로 SSE 연결을 맺으면 하트비트가 스케줄러(mock)에 등록된다.
        Long memberId = 1L;
        ArgumentCaptor<Runnable> heartbeatTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduledFuture)
                .when(taskScheduler)
                .scheduleAtFixedRate(heartbeatTaskCaptor.capture(), any(Instant.class), any(Duration.class));
        // 이 연결이 그 회원의 마지막 연결이라고 등록부가 답한다
        given(sseConnectionRegistry.remove(eq(memberId), any(SseConnection.class))).willReturn(OptionalInt.of(0));

        SseEmitter emitter = sseConnectionService.connect(memberId);

        // 클라이언트 이탈 등으로 emitter가 하트비트보다 먼저 완료된 상황을 재현한다.
        // (실제 서블릿 요청 컨텍스트가 없는 단위 테스트라 emitter.complete()는 onCompletion 콜백을
        //  트리거하지 않고 "완료됨" 상태만 만든다 — 하트비트 실패 경로만 순수하게 검증하기 위함)
        emitter.complete();

        // when: 스케줄러가 다음 주기의 하트비트를 발사했다고 가정하고 캡처한 태스크를 직접 실행한다.
        // 수정 전에는 emitter.send()가 던지는 IllegalStateException("ResponseBodyEmitter has already
        // completed")이 IOException만 잡는 catch 밖으로 새어나가 Spring의 TaskUtils$LoggingErrorHandler가
        // 로그만 남기고 하트비트를 계속 반복시켰다 (실측: 10초마다 반복되어 305회 누적).
        // 수정 후에는 이 예외가 여기서 잡혀 스케줄이 취소되어야 한다.
        assertThatCode(() -> heartbeatTaskCaptor.getValue().run())
                .doesNotThrowAnyException();

        // then: 하트비트 스케줄이 취소되고, SSE 자원(등록부 엔트리, Redis 구독)이 해제되어야 한다.
        verify(scheduledFuture, times(1)).cancel(false);
        verify(sseConnectionRegistry, times(1)).remove(eq(memberId), any(SseConnection.class));
        verify(sseClientPresence, times(1)).unsubscribeClient(memberId);
        // send()가 이미 완료 상태에서 실패했으므로 refreshConnection까지는 도달하지 않는다.
        verify(sseClientPresence, never()).refreshConnection(memberId);
    }

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

    @DisplayName("알림은 event 이름이 'notification'으로 고정되고, 제목·본문은 JSON 데이터로 실린다")
    @Test
    void 알림_이벤트는_이름이_고정이고_제목은_데이터에_들어간다() throws IOException {
        // 이벤트 이름은 클라이언트가 리스너를 거는 타입 구분자다(addEventListener(name)).
        // 여기에 강의 제목처럼 런타임 값을 넣으면 클라이언트가 이름을 미리 알 수 없어
        // 어떤 리스너로도 알림을 받지 못한다 — 실제로 그런 상태였다.
        Long memberId = 3L;
        String title = "중등 수학 심화";
        String body = "수강신청이 승인되었습니다";
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(List.of(new SseConnection(emitter))).when(sseConnectionRegistry).connectionsOf(memberId);

        sseConnectionService.sendSseNotification(memberId, title, body);

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        Set<ResponseBodyEmitter.DataWithMediaType> parts = captor.getValue().build();

        String wireText = parts.stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());

        // 이름은 고정값이고, 제목이 이름 자리에 새어나오지 않는다
        assertThat(wireText).contains("event:notification");
        assertThat(wireText).doesNotContain("event:" + title);

        // 제목·본문은 JSON 데이터로 간다
        assertThat(parts)
                .anySatisfy(part -> {
                    assertThat(part.getData()).asInstanceOf(InstanceOfAssertFactories.MAP)
                            .containsEntry("title", title)
                            .containsEntry("body", body);
                    assertThat(part.getMediaType()).isEqualTo(MediaType.APPLICATION_JSON);
                });
    }
}
