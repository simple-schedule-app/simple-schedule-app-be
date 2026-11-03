package com.example.simplescheduleapp.sse.presentation;

import com.example.simplescheduleapp.common.auth.BearerTokenExtractor;
import com.example.simplescheduleapp.common.auth.TokenService;
import com.example.simplescheduleapp.common.exception.ApplicationException;
import com.example.simplescheduleapp.sse.application.SseConnection;
import com.example.simplescheduleapp.sse.application.SseConnectionRegistry;
import com.example.simplescheduleapp.sse.application.SseConnectionService;
import com.example.simplescheduleapp.sse.application.port.out.SseClientPresence;
import com.example.simplescheduleapp.sse.exception.SseExceptionCode;
import org.junit.jupiter.api.AfterEach;
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
@Import({SseConnectionService.class, SseConnectionRegistry.class})
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

    /**
     * 등록부는 이 클래스의 테스트들이 공유하는 싱글턴 빈이고, MockMvc는 비동기 요청을 스스로 끝내지 않는다.
     * 그래서 한 테스트가 남긴 커넥션이 다음 테스트의 등록부에 그대로 남는다. 테스트마다 빈 등록부에서
     * 시작하도록 남은 커넥션을 모두 뺀다.
     */
    @AfterEach
    void tearDown() {
        for (SseConnection leftover : sseConnectionRegistry.connectionsOf(MEMBER_ID)) {
            leftover.stop();
            sseConnectionRegistry.remove(MEMBER_ID, leftover);
        }
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
