# 터널 출시 경로의 연결 지점: 검증 결과와 계획

> **상태:** 계획만. 코드와 런북은 아직 바꾸지 않았다. 오너 승인 대기.
> **요청:** 오케스트레이터가 `orchestration/LAUNCH-RUNSHEET.md`를 쓰다 찾은 연결 지점 넷(①~④)의 검증. ⑤는 검증 중에 BE가 새로 찾았고, ⑥은 오케스트레이터가 이 계획을 검토하다 찾았다.
> **개정 (같은 날):** 오케스트레이터 검토를 반영했다. 사전 확인 명령에 대조를 붙였고, ⑤를 1차 출시 비차단으로 내렸고, ⑥과 토큰이 필요한 주소의 프리플라이트 검사를 더했다.
> **기준:** develop `1c1fbb7`. 운영 main `e53fa71`과 `deploy/` 아래 차이는 없다.

## 한눈에 보기

| # | 항목 | 판정 | 출시 영향 | 할 일 |
|---|---|---|---|---|
| ① | 터널 뒤에서 nginx 속도 제한이 사용자 전체의 한 통이 된다 | 설정은 사실. 헤더가 cloudflared를 거쳐 오는지는 가정 | 출시 전 수정 권고 | 서버 사전 확인 넷, `nginx.conf` 세 곳, 런북 |
| ② | 서버에 넣을 출처 값이 둘이다 | 사실. 다만 GeekChat 쪽은 다시 둘로 갈린다 | 웹 정식 출처를 먼저 정해야 한다 | 런북 CORS 행 보강, 런시트 정정 전달 |
| ③ | `.env` 반영은 Deploy 수동 실행으로 | 읽기 맞음, 보충 셋 | 없음 | 런시트 보충 전달 |
| ④ | 채팅 WebSocket 하트비트 | **없음으로 확정** | 출시 비차단 | 런북 두 곳 정정 |
| ⑤ | OAuth 콜백 주소 (신규) | 코드는 사실. 1차 범위(웹과 BE)에는 OAuth 진입점이 없다 | 1차 출시 비차단. 공개 주소에서 OAuth를 쓰게 될 때의 조건 | 런북에 조건으로 기록 |
| ⑥ | 채팅 토큰이 nginx 접근 로그에 남는다 (오케스트레이터 발견) | 사실. 터널과 무관하게 지금도 그렇다 | 출시 비차단 | ①과 같은 자리라 함께 처리 제안 |

---

## ① 터널 뒤에서 속도 제한이 사용자 전체의 한 통이 된다

### 확인한 사실

- `deploy/nginx/nginx.conf:30` 제한 키가 `$binary_remote_addr`(초당 20회)이고, `:31`에서 초과분은 429를 받는다.
- `deploy/nginx/conf.d/ssa.conf`의 `limit_req zone=api_limit burst=40 nodelay`는 33행(`/sse-stream`), 50행(`/fcm/`), 77행(`/chat/`), 91행(`/`)에 있다. `/chat/ws`에는 없다.
- `deploy/` 전체에 `real_ip`와 `set_real_ip_from`이 0건이다. 같은 검색에서 `limit_req` 줄이 잡히므로 검색 자체는 동작한다.
- 접근 로그 형식(`nginx.conf:14`)도 `$remote_addr`를 쓴다. 터널을 붙이면 로그의 클라이언트 주소도 전부 터널 컨테이너 주소가 된다.
- 앱은 클라이언트 IP를 읽지 않는다. SSA와 GeekChat 본 코드에서 `X-Real-IP`, `X-Forwarded-For`, `getRemoteAddr`, `forward-headers-strategy`가 0건이다(양성 대조: `HttpServletRequest`는 잡힌다). 그래서 영향은 nginx 속도 제한과 접근 로그에 그친다.
- 터널 컨테이너는 `app_network`에 붙는다(`docker-compose.tunnel.yml:43`, 외부 네트워크 선언 `:57-59`). 이 네트워크는 서브넷을 고정하지 않은 bridge다(`docker-compose.prod.yml:139-141`).

Cloudflare와 nginx 문서:

| 출처 | 내용 (요약) |
|---|---|
| [Cloudflare Tunnel FAQ](https://developers.cloudflare.com/cloudflare-one/faq/cloudflare-tunnels-faq/) "Does Cloudflare Tunnel send visitor IPs to my origin?" | 아니라고 답한다. 오리진으로 가는 요청은 cloudflared가 내부에서 만든다. 방문자 IP가 필요하면 별도 방법을 쓰라며 아래 문서로 안내한다 |
| [Restoring original visitor IPs](https://developers.cloudflare.com/support/troubleshooting/restoring-visitor-ips/restoring-original-visitor-ips/) | 방문자 IP는 `CF-Connecting-IP` 헤더로 붙는다. nginx는 realip 모듈의 `set_real_ip_from`과 `real_ip_header CF-Connecting-IP`로 복원한다. 헤더가 오리진에 안 보이면 Transform Rules와 Managed Transforms를 확인하라고 한다 |
| [Cloudflare HTTP headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/#cf-connecting-ip) | `CF-Connecting-IP`는 엣지에서 오리진으로 가는 트래픽에 실린다 |
| [ngx_http_realip_module](https://nginx.org/en/docs/http/ngx_http_realip_module.html) | 기본 빌드에는 없는 모듈이다. `$realip_remote_addr`에 바꾸기 전 주소가 남는다 |
| `nginx/pkg-oss` `alpine/Makefile:88`, `nginx/docker-nginx` `mainline/alpine-slim/Dockerfile:42-47` | nginx.org 패키지의 Alpine 빌드 인자에 `--with-http_realip_module`이 있고, 공식 이미지는 이 패키지를 쓴다 |
| [ngx_http_limit_req_module](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html) | 키가 빈 요청은 세지 않는다 (아래 대안에 쓴다) |

### 가정: 헤더가 cloudflared를 거쳐 nginx까지 온다

문서 사슬은 이를 전제한다(FAQ가 헤더 복원 문서로 안내한다). 하지만 터널 문서에 그 문장 자체는 없다.

**이 가정은 틀려도 해가 없다.** 헤더가 없으면 realip는 주소를 바꾸지 않으므로 지금과 똑같이 동작한다. 그래서 터널 개통 전에 반영해도 되고, 판정은 개통 날 실측으로 한다(아래 검증 3).

### 오너 사전 확인 (서버, 읽기 전용)

```bash
# 1) realip 모듈이 있는가. 기대: 둘 다 1
#    첫 줄이 대조다. 컨테이너가 없거나 exec가 실패해도 둘째 줄은 0을 내므로,
#    첫 줄이 1이어야 둘째 줄의 0을 "모듈 없음"으로 읽을 수 있다
docker exec ssa-nginx nginx -V 2>&1 | grep -c 'configure arguments'
docker exec ssa-nginx nginx -V 2>&1 | grep -c with-http_realip_module
```

```bash
# 2) 앱 네트워크의 실제 이름과 서브넷. 기대: 172.16.0.0/12 안 (예: 172.18.0.0/16)
docker network ls --filter name=app_network --format '{{.Name}}'
docker network inspect <위에서 나온 이름> --format '{{range .IPAM.Config}}{{.Subnet}}{{end}}'
```

```bash
# 3) 서버의 LAN과 WireGuard 주소(도커 인터페이스 제외). 기대: 172.16.0.0/12와 겹치지 않음
ip -4 -o addr show | grep -v -E ' (docker0|br-)' | awk '{print $2, $4}'
```

```bash
# 4) LAN이나 VPN에서 80으로 직접 들어온 요청의 출발지가 보존되는가: 최근 접근 로그의 주소 분포
#    먼저 노트북에서 VPN을 켜고 서버로 요청을 한 번 보낸다. 판정 창 안에 VPN 요청이 없으면
#    분포만으로는 보존 여부를 가를 수 없다
docker logs --since 1h ssa-nginx 2>/dev/null | awk '{print $1}' | sort | uniq -c | sort -rn | head
```

4의 판정: 방금 보낸 노트북의 VPN 주소가 보이면 보존된다. `172.x.0.1` 같은 게이트웨이 주소만 보이면 보존되지 않는다. CD 스모크는 서버 자신이 보내므로 원래 게이트웨이 주소로 찍힌다.

`deploy.yml` 주석에 적힌 서브넷 값들은 "예"로 표기돼 있어 근거로 쓰지 않는다.

### 결과별 분기

| 결과 | 판단 |
|---|---|
| 1이 1, 2가 대역 안, 3이 겹치지 않음, 4에서 보존됨 | 아래 변경을 그대로 적용한다 |
| 2가 `172.16.0.0/12` 밖 (예: `192.168.x`) | 신뢰 범위를 실제 서브넷으로 좁힌다 |
| 3이 겹침 | 신뢰 범위를 실제 서브넷으로 좁힌다. 그래도 겹치면 직결 포트를 닫는 결정이 먼저다 |
| 4에서 보존 안 됨 | 대역을 신뢰하면 LAN 사용자 누구나 헤더를 위조할 수 있다. 런북 미해결 항목인 직결 포트(`80:80`)를 닫는 결정을 먼저 내린다 |
| 1이 0 | realip를 쓸 수 없다. 다시 계획한다 |

### 바꿀 내용 (구현 단계, 오너 승인 뒤)

`deploy/nginx/nginx.conf`의 http 블록 세 곳:

```nginx
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" rt=$request_time uct=$upstream_connect_time '
                    'src=$realip_remote_addr';

    # Cloudflare 터널이 넘기는 실제 클라이언트 주소를 복원한다. 신뢰 범위는 도커 브리지 대역뿐이다.
    # LAN과 VPN에서 80으로 직접 들어온 요청은 이 대역 밖이라, 헤더를 위조해도 무시된다.
    set_real_ip_from 172.16.0.0/12;
    real_ip_header CF-Connecting-IP;
```

`src=$realip_remote_addr`는 realip가 바꾸기 전의 접속 주소다. 이 필드가 있으면 로그에서 터널 경로(src가 도커 대역)와 직결 경로가 갈리고, realip가 실제로 동작했는지(맨 앞 주소와 src가 다른지)도 보인다.

경로별 효과:

| 경로 | 접속 주소 | 헤더 | 바꾼 뒤 `$remote_addr` | 제한 키 |
|---|---|---|---|---|
| 터널 | cloudflared (도커 대역) | 있음 | 방문자 공인 IP | 방문자별 |
| 터널, 가정이 틀린 경우 | cloudflared | 없음 | cloudflared | 모두가 한 통 (지금과 같음) |
| LAN, VPN 직결 | LAN이나 VPN 주소 | 위조 가능 | 접속 주소 그대로 (헤더 무시) | 접속 주소별 |
| 서버 자신 (CD 스모크) | 브리지 게이트웨이 | 없음 | 게이트웨이 | 지금과 같음 |

### 검증

1. **로컬 (구현 단계).** 이미지 두 개(`nginx:1.27-alpine`, 받은 요청 헤더를 그대로 돌려주는 `traefik/whoami`)를 받아야 하므로 다운로드는 오너 확인 뒤에 한다.
   - 네트워크를 둘 만든다. `trusted`는 도커 기본 대역(cloudflared 역할), `untrusted`는 서브넷을 `10.99.0.0/24`로 지정한다(LAN 역할). nginx는 둘 다에 붙이고, whoami에는 `app`과 `geekchat` 별칭을 준다.
   - 판정 A: trusted에서 `CF-Connecting-IP: 203.0.113.7`을 실어 보내면 whoami가 받은 `X-Real-Ip`가 `203.0.113.7`이다.
   - 판정 B: untrusted에서 같은 헤더를 실어 보내면 `X-Real-Ip`가 `10.99.0.x`다(위조 무시).
   - 판정 C: trusted에서 헤더 값 둘로 각각 60회를 한꺼번에 보내면 값마다 41회 안팎이 통과한다. 값 하나로 120회를 보내면 41회 안팎만 통과한다.
   - 음성 대조: realip 두 줄을 뺀 설정으로 C를 다시 돌리면 두 값이 한 통을 나눠 합계 41회 안팎이어야 한다. 이 차이가 안 나오면 시험이 대상을 못 보는 것이다.
2. **배포 직후 (터널 전).** 직결 트래픽의 로그에서 맨 앞 주소와 `src`가 같다. 아무것도 바뀌지 않았다는 뜻이다.
3. **개통 날.**
   - Cloudflare 대시보드의 Managed Transforms에서 "Remove visitor IP headers"가 꺼져 있는지 본다.
   - 외부 회선(휴대폰 LTE)에서 한 번 요청하고 `docker logs --tail 5 ssa-nginx`를 본다. 맨 앞 주소가 그 회선의 공인 IP이고 `src`가 도커 대역이면 통과다. 맨 앞도 도커 대역이면 가정이 틀린 것이다.

### 가정이 틀렸을 때

터널에서 온 요청은 제한 키를 비워 nginx 제한에서 뺀다. `geo`로 도커 대역을 가려 키를 빈 값으로 만들면 된다(키가 빈 요청은 세지 않는다). 속도 제한은 Cloudflare 엣지 규칙으로 옮긴다. 무료 플랜에서 쓸 수 있는 규칙 수와 조건은 그때 문서로 확인한다.

### 남는 위험

- **같은 공인 IP를 쓰는 사용자들:** 학교 와이파이나 통신사 CGNAT 뒤의 사용자는 수정 뒤에도 한 통을 나눠 쓴다. 특강 오픈 시각처럼 요청이 몰리는 순간 429가 날 수 있다. 출시 후 접근 로그의 429 비율을 관측한다. 제한 값 조정은 이번 범위가 아니다.
- **서브넷 이동:** 네트워크를 다시 만들어 서브넷이 신뢰 범위 밖으로 바뀌면 realip가 조용히 꺼진다. 로그에서 `src`와 맨 앞 주소가 같아지는 것으로 드러난다.
- **서버 자신의 요청:** 브리지 게이트웨이는 신뢰 범위 안이라, 서버에서 보내는 요청은 헤더를 위조할 수 있다. 서버 셸 권한자만 해당한다.

### 롤백

세 곳을 되돌려 다시 배포한다. CD의 nginx 재시작 단계는 `nginx -t`가 통과해야 재시작한다(`deploy.yml:186-187`). 그래서 설정 오류가 있으면 재시작 전에 멈추고, 기존 프로세스는 옛 설정을 메모리에 든 채 계속 돈다. 다만 디스크의 설정 파일은 이미 새 것이므로 즉시 되돌려야 한다.

---

## ② 출처 값은 둘이고, GeekChat 쪽은 다시 둘로 갈린다

### 확인한 사실

- **SSA:** `app/src/main/resources/application-prod.yml:65`가 `CORS_ALLOWED_ORIGINS`를 받는다. `CorsConfig.java:19-21`은 `allowedOrigins`에 `allowCredentials(true)`를 함께 쓴다. 그래서 와일드카드를 쓸 수 없고 정확한 출처를 적어야 한다.
- **GeekChat:** `docker-compose.prod.yml:74`의 `FRONTEND_URL`은 `GEEKCHAT_FRONTEND_URL`에서 오고, `:78`의 `APP_FRONTEND_ORIGIN_PATTERNS`는 `GEEKCHAT_FRONTEND_ORIGIN_PATTERNS`에서 온다(`geekchat/app/src/main/resources/application.yml:78`, `:82`).
- **허용 목록은 둘의 합집합이다.** REST CORS(`SecurityConfig.kt:59-62`)와 WebSocket 핸드셰이크(`WebSocketConfig.kt:22-27`) 모두 `allowedOriginPatterns`라 와일드카드도 받는다.
- **OAuth 결과 리다이렉트는 `FRONTEND_URL`로만 간다**(`AuthController.kt:100-124`).

### 정정

런시트는 `GEEKCHAT_FRONTEND_URL`을 그대로 두고 패턴 쪽에 웹 출처를 더한다고 적었다. 그런데 웹의 정식 출처가 지금 `FRONTEND_URL`과 다르면, 그대로 둘 경우 OAuth 로그인 뒤 사용자가 옛 출처로 돌아간다.

정할 것은 **웹 정식 출처 하나**다. 정해지면 이렇게 넣는다.

| 변수 | 값 | 이유 |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | 정식 출처 (리허설 중에는 `http://localhost:3000`도) | 자격 증명을 허용하므로 정확한 값만 된다 |
| `GEEKCHAT_FRONTEND_URL` | 정식 출처 | OAuth 귀환지이자 허용 목록의 첫 값 |
| `GEEKCHAT_FRONTEND_ORIGIN_PATTERNS` | 그 밖에 허용할 출처 (리허설, 프리뷰) | 와일드카드 가능 |

런시트 D의 진단도 고쳐야 한다. 둘째 줄이 비면 "`GEEKCHAT_FRONTEND_URL`과 패턴 어느 쪽에도 그 출처가 없거나, C가 빠진 것"이다.

### 공개 검증에 더할 검사: 토큰이 필요한 주소의 프리플라이트

`/login` 프리플라이트는 공개 주소라서, 인증 검사가 OPTIONS를 막는 사고가 나도 실패하지 않는다. 그래서 오케스트레이터가 런시트 D에 넣은 검사를 런북의 공개 검증 절에도 넣는다.

```bash
# 기대: 200, 그리고 access-control-allow-origin과 access-control-allow-headers(authorization 포함)
curl -s -i -X OPTIONS "https://api.<도메인>/lectures/search" \
  -H "Origin: <웹 정식 출처>" -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Headers: authorization" | grep -i -E '^HTTP|^access-control-allow-(origin|headers)'
```

소스로는 통과한다.
- 등록된 인터셉터 둘(`AuthConfig.java:31-34`)은 모두 핸들러가 `HandlerMethod`가 아니면 바로 통과시킨다. `AuthenticationInterceptor.java:34-37`과 `RoleInterceptor.java:33-35`이다. 프리플라이트는 컨트롤러 메서드가 아닌 핸들러로 처리된다.
- 서블릿 필터는 둘이다. `InternalApiKeyFilter`는 `/internal/`만 보고(`:42`), `MdcLoggingFilter`는 로그 문맥만 다룬다.
- SSA에는 Spring Security가 없다(대조: 같은 범위에서 `@Configuration`은 20건).
- `CorsConfig.java:18-22`는 허용 헤더를 지정하지 않아 Spring 기본값을 쓴다.
- nginx에는 OPTIONS와 CORS 헤더 처리가 없다.

---

## ③ `.env` 반영은 Deploy 수동 실행으로

### 확인 결과

- **읽기는 맞다.** `deploy.yml`의 `workflow_dispatch`는 `environment`(prod, stage)를 받는다. deploy 잡 단계에는 이벤트를 가르는 조건이 없다(`if:`는 빌드 단계의 matrix 조건과 Teardown의 `always()`뿐이다). 그래서 수동 실행에서도 Sync deploy bundle, Deploy containers, Restart nginx, Smoke test가 모두 돈다.

### 보충

1. **이미지도 다시 빌드한다.** 수동 실행도 build-and-push 잡부터 돈다. main HEAD를 다시 빌드해 그 SHA 태그로 배포한다(`deploy.yml:92`). 실행 직전에 main HEAD가 의도한 커밋인지 확인한다.
2. **터널은 건드리지 않는다.** Deploy 워크플로는 앱 스택만 다룬다. 터널 프로젝트(`ssa-tunnel`)는 따로 떠 있으므로, `CF_TUNNEL_TOKEN`을 바꿨다면 터널 쪽 `up -d`를 따로 해야 한다.
3. **WireGuard:** 이유는 지금도 유효할 수 있다. 근거는 다음과 같다.
   - `deploy/README.md:74`는 현재 상태를 "CI 러너가 같은 피어 키를 쓴다"고 적는다.
   - `README.md:12`, `:45`와 `deploy.yml:4-5`는 CI 전용 피어 발급을 해법으로 적고 있다.
   - 실제로 발급됐는지는 저장소로 알 수 없으므로 런시트의 "미확인" 표시를 유지한다. 서버 관리자에게 묻거나 서버에서 `wg show`의 피어 목록으로 확인한다.
   - 끄는 것을 잊어도 안전하게 실패한다. 연결 확인 단계(`deploy.yml:147`)가 서버를 건드리기 전(Sync 단계 `:161`보다 앞)에 멈추므로, WireGuard를 끄고 다시 실행하면 된다.

---

## ④ 채팅 WebSocket 하트비트: 없음으로 확정

### 확인한 사실

- **서버:** `geekchat/websocket` 본 코드에 ping, pong, heartbeat, SockJS, 유휴 시간 설정, 스케줄러가 0건이다. 검색에 걸린 `ping` 세 줄은 `typing`과 `bookkeeping`의 부분 일치였다. 양성 대조로 `WebSocketSession`은 28건 잡힌다.
- **모르는 프레임:** `ChatWebSocketHandler.kt:117`이 `UNKNOWN_EVENT` 오류로 답한다. 그래서 클라이언트가 JSON ping을 보내는 방식은 서버 변경 없이는 안 된다.
- **웹:** `lib/chat-socket.ts:35-40`에는 재연결 백오프(2.5초에서 시작해 최대 30초)만 있고 주기 신호가 없다. 재연결 뒤 놓친 메시지를 다시 불러오지 않는다는 점은 오케스트레이터가 확인했다(BE는 직접 확인하지 않았다).
- **nginx:** `/chat/ws`는 `proxy_read_timeout 3600s`다(`ssa.conf:70`). Cloudflare 엣지의 유휴 종료 시간은 문서에 수치가 없다(런북 표 그대로).

### 런북 정정 문안

183행 문단을 다음으로 바꾼다.

> nginx는 `proxy_read_timeout 3600s`로 잡아 뒀지만, **Cloudflare 엣지의 유휴 종료는 그것과 별개다.** **하트비트는 없다(2026-10-04 확인).** 서버(`geekchat/websocket`)는 ping을 보내지 않고, 웹(`lib/chat-socket.ts`)은 끊기면 2.5초부터 최대 30초 간격으로 다시 붙기만 한다. 그래서 조용한 대화는 엣지에서 끊긴 뒤 재연결로 이어진다. 출시를 막지는 않는다. 개선은 서버가 주기적으로 ping 제어 프레임을 보내는 것이다. 엣지는 어느 방향이든 데이터가 흐르면 유휴로 보지 않고(위 표), 브라우저는 ping에 pong을 자동으로 돌려주므로 클라이언트를 바꿀 필요가 없다. 클라이언트가 JSON ping을 보내는 방식은 서버가 모르는 프레임을 `UNKNOWN_EVENT`로 거절하므로(`ChatWebSocketHandler.kt:117`) 서버 변경 없이는 안 된다.

191행 표 행을 다음으로 바꾼다.

> | 채팅 WS 하트비트 | **없음(2026-10-04 확인).** 조용한 대화는 엣지에서 끊긴 뒤 웹이 재연결한다. 출시 비차단. 개선(서버의 주기 ping 제어 프레임)은 백로그 |

---

## ⑤ (신규) OAuth 콜백 주소

### 확인한 사실

- `docker-compose.prod.yml:79`의 `OAUTH_CALLBACK_URL`은 `GEEKCHAT_OAUTH_CALLBACK_URL`에서 오고, 기본값은 `http://localhost/chat/auth/callback`이다. 앱은 `geekchat/app/src/main/resources/application.yml:84`에서 받는다.
- 이 값은 인가 요청의 `redirect_uri`(`AuthController.kt:73`, `:86`)와 토큰 교환의 `redirect_uri`(`OAuthClientAdapter.kt:58`)에 그대로 쓰인다.
- 런시트와 런북 어디에도 이 항목이 없다(런시트에서 `OAUTH`, `callback`, `콜백` 검색 0건. 같은 검색에서 `CORS` 줄은 잡힌다).

### 뜻

공개 도메인으로 열면 이 값은 `https://api.<도메인>/chat/auth/callback`이 되어야 한다. Google과 Naver 개발자 콘솔에 등록한 콜백 주소도 같은 값이어야 한다. 공급자는 등록되지 않은 `redirect_uri`를 거절한다.

### 판정: 1차 출시 비차단

1차 범위(웹과 BE)에는 OAuth 진입점이 없다. 웹 `origin/main` 본 코드에서 `oauth|naver|/auth/google|/auth/callback` 검색이 0건이다(오케스트레이터가 찾고 BE가 다시 돌렸다. 같은 범위의 대조 `/auth/login`은 5건). 서버에 공급자 ID가 남아 있어도 이 경로를 타는 사용자가 없다. 그래서 런북에는 "공개 주소에서 OAuth를 쓰게 될 때 바꿔야 하는 것"으로 적는다.

### 그래도 남기는 확인 (값은 찍히지 않는다)

```bash
# 첫 줄이 대조다(.env를 읽을 수 있고 변수가 있는가). 0이면 둘째 줄의 0도 믿을 수 없다
grep -c -E '^[A-Z0-9_]+=.' /opt/ssa/.env
# 운영에 OAuth 공급자가 설정돼 있는가
grep -c -E '^(GOOGLE|NAVER)_CLIENT_ID=.' /opt/ssa/.env
```

---

## ⑥ 채팅 토큰이 nginx 접근 로그에 남는다 (오케스트레이터 발견)

### 확인한 사실

- 웹은 `<채팅 주소>/ws?token=<액세스 토큰>`으로 붙는다(웹 `lib/chat-client.ts:59`). 서버도 쿼리에서 토큰을 읽는다(`ChatWebSocketHandler.kt:171-172`).
- 접근 로그 형식은 `"$request"`를 적어 쿼리까지 남긴다(`nginx.conf:14, 17`). `/chat/ws` 블록(`ssa.conf:61-73`)에 로그 예외가 없다.
- 액세스 토큰 수명은 15분이다(`geekchat/app/src/main/resources/application.yml:76`).
- nginx 컨테이너에는 로그 회전 설정이 없다(`docker-compose.prod.yml`의 nginx 서비스에 `logging:` 없음). 도커 데몬 기본값에 따라 오래 남을 수 있다.
- SSE는 해당하지 않는다. 웹은 헤더를 실을 수 있는 `EventSourcePolyfill`을 쓰고(웹 `lib/api/notification.ts:8`), SSA는 쿼리에서 토큰을 읽지 않는다.

### 제안 (①과 같은 구현에 넣을지 오너가 정한다)

`/chat/ws`에서만 쿼리를 뺀 로그 형식을 쓴다. 다른 경로의 로그는 그대로 둔다.

```nginx
# nginx.conf http 블록: main과 같고 "$request" 대신 쿼리 없는 요청 줄을 적는다
    log_format ws_noquery '$remote_addr - $remote_user [$time_local] "$request_method $uri $server_protocol" '
                          '$status $body_bytes_sent "$http_referer" '
                          '"$http_user_agent" rt=$request_time uct=$upstream_connect_time '
                          'src=$realip_remote_addr';
```

```nginx
# ssa.conf의 location /chat/ws 안
        access_log /var/log/nginx/access.log ws_noquery;
```

대안은 `main` 형식 자체에서 쿼리를 빼는 것이다. 한 줄로 끝나지만 검색어 같은 다른 쿼리도 함께 사라진다. 토큰만 지우는 `map` 치환도 가능하지만 정규식 관리 부담에 비해 얻는 것이 적다. 이미 로그에 남은 토큰은 기록된 지 15분이 지나면 만료되므로, 과거 로그를 따로 지울 필요는 낮다. 다만 최근 15분 사이의 기록은 아직 유효한 토큰을 담고 있다.

---

## 구현 단계 범위 (승인 뒤)

| 파일 | 변경 |
|---|---|
| `deploy/nginx/nginx.conf` | realip 두 줄, 로그 형식에 `src` (①). ⑥을 넣으면 `ws_noquery` 형식 하나 더 |
| `deploy/nginx/conf.d/ssa.conf` | ⑥을 넣을 때만: `/chat/ws`에 `access_log` 한 줄 |
| `deploy/CLOUDFLARE-TUNNEL.md` | 미해결 표에 ① 추가, CORS 행 보강(②), OAuth 콜백을 조건으로 기록(⑤), WS 하트비트 정정(④), 개통 검증에 "Remove visitor IP headers" 확인, 외부 회선 판정, 토큰이 필요한 주소의 프리플라이트 추가(①, ②) |

⑥을 넣으면 세 파일이 바뀌어 승인 게이트(3개 이상) 대상이다. 이 계획의 승인으로 함께 받는다.

런시트(`orchestration/LAUNCH-RUNSHEET.md`)는 오케스트레이터의 문서다. BE는 정정 내용만 전달한다(② `FRONTEND_URL`과 D의 진단, ③ 보충, ⑤).

## 오너가 정하거나 확인할 것

1. 웹 정식 출처 (②). 오케스트레이터 권고는 `https://app.<도메인>`이다
2. 서버 사전 확인 넷 (①)
3. ⑥을 이번 구현에 함께 넣을지
4. 구현 단계 로컬 검증용 이미지 두 개의 다운로드 (①)
5. CI 전용 WireGuard 피어가 발급됐는지 (③, 서버 관리자 확인)
6. 이 계획의 구현 승인
