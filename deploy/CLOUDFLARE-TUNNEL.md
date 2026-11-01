# Cloudflare Tunnel 런북: VPN 없이 공개 HTTPS로 열기

> 지금 서버는 WireGuard 뒤에 있어 VPN을 켜야만 닿는다. 이 문서는 그것을 공개 HTTPS 주소로 바꾸는 절차다.
> **아직 실행하지 않았다.** 도메인 구매와 터널 생성은 오너만 할 수 있고, 그 뒤에 이 문서대로 띄운다.

## 무엇을 하는 것인가

Cloudflare Tunnel은 **서버가 Cloudflare로 바깥을 향해 연결을 걸고, 그 연결을 되받아 트래픽을 넣어 주는** 방식이다. 서버 쪽에서 포트를 열지 않는다.

정확히 말하면 `cloudflared`라는 데몬이 서버 안에서 Cloudflare 엣지로 아웃바운드 터널을 유지하고, 엣지가 `https://api.<도메인>`으로 들어온 요청을 그 터널로 흘려 컨테이너 네트워크 안의 nginx에 전달한다.

**포트 포워딩이나 공인 IP, 인증서 발급이 필요 없다.** 방화벽에 인바운드 구멍을 내지 않고, TLS는 Cloudflare 엣지가 끝낸다.

## 왜 앱 스택과 분리했나

터널은 `docker-compose.tunnel.yml`이라는 **별도 파일, 별도 프로젝트**로 뜬다. 앱의 `docker-compose.prod.yml`에 넣지 않았다.

CD는 배포마다 이렇게 돈다.

```
docker compose -f docker-compose.prod.yml up -d --remove-orphans
```

`--remove-orphans`는 그 프로젝트의 현재 설정에 없는 컨테이너를 지운다. 터널을 같은 파일에 `profiles:`로 넣어 두면 **프로파일 비활성 서비스를 지우는 Compose 버전이 있어** 앱을 배포할 때마다 공개 접점이 끊긴다. 프로젝트를 나누면 `--remove-orphans`가 경계를 넘지 않는다.

**앱은 하루에도 몇 번 재배포되지만 터널은 그대로 있어야 한다.** 수명이 다르면 파일을 나눈다.

## 선행 조건

| # | 항목 | 담당 |
|---|---|---|
| 1 | 도메인 구매, Cloudflare에 네임서버 위임 | 오너 |
| 2 | Zero Trust 대시보드에서 터널 생성, 토큰 발급 | 오너 |
| 3 | Public hostname 매핑 (아래) | 오너 |

## 절차

### 1. 터널 생성과 hostname 매핑 (Cloudflare 대시보드)

Zero Trust → Networks → Tunnels → Create a tunnel → **Cloudflared** 선택.

터널을 만들면 **Public hostname**을 추가한다. 여기가 중요하다.

| 칸 | 값 |
|---|---|
| Subdomain | `api` |
| Domain | 구매한 도메인 |
| Type | `HTTP` |
| URL | `ssa-nginx:80` |

**`nginx:80`이 아니라 `ssa-nginx:80`이다.** 터널 컨테이너는 앱 스택과 다른 Compose 프로젝트라, 서비스명(`nginx`)이 아니라 컨테이너명(`ssa-nginx`)으로 찾는 것이 확실하다.

TLS는 엣지가 끝내므로 여기는 `HTTP`가 맞다. 서버 안쪽 구간은 컨테이너 네트워크다.

### 2. 토큰을 서버에 둔다

대시보드가 주는 설치 명령에 긴 토큰이 들어 있다. **그 값을 채팅, 커밋, 이슈, 로그에 붙이지 않는다.** 서버에서 직접 넣는다.

```bash
ssh <배포서버>
cd /opt/ssa
# 편집기로 .env를 열어 아래 줄을 추가한다. 값은 대시보드에서 복사한다.
#   CF_TUNNEL_TOKEN=<대시보드가 준 토큰>
```

`.env`는 버전 관리에 들어가지 않는다. 변수 **이름**만 `.env.example`에 있다.

### 3. 네트워크 이름을 확인한다

터널은 앱이 이미 만든 네트워크에 올라탄다. 기본값은 `ssa_app_network`인데, **추론이라 한 번 확인해야 한다.**

```bash
docker network ls --filter name=app_network
```

출력이 `ssa_app_network`가 아니면 `.env`에 실제 이름을 넣는다.

```
SSA_NETWORK_NAME=<위에서 본 이름>
```

### 4. 이미지 태그 (이미 고정돼 있다)

`docker-compose.tunnel.yml`은 `cloudflare/cloudflared:2026.8.3`으로 고정돼 있다. 2026-09-10에 실측한 값이다.

```
$ docker run --rm cloudflare/cloudflared:latest --version
cloudflared version 2026.8.3 (built 2026-08-31-10:17 UTC)
```

`latest`로 두면 재시작할 때마다 다른 바이너리가 뜰 수 있고, 장애 때 무엇이 돌았는지 알 수 없어 롤백이 성립하지 않는다.

**올릴 때는 같은 방법으로 새 버전을 확인해 태그를 바꾸고 커밋한다.** 컨테이너 안에서 자기를 갱신하지 못하도록 `--no-autoupdate`가 걸려 있다.

### 5. compose 파일을 서버로 옮긴다

**CD는 이 파일을 서버에 복사하지 않는다.** 배포 번들 동기화가 `docker-compose.prod.yml`과
`nginx/`만 옮기기 때문이다(`deploy.yml`의 Sync deploy bundle 스텝). 저장소에서 직접 옮긴다.

```bash
# 로컬 저장소에서
scp deploy/docker-compose.tunnel.yml <배포서버>:/opt/ssa/
```

> 후속 과제: 이 파일을 CD의 동기화 목록에 추가하면 손으로 옮기지 않아도 된다.
> 지금 넣지 않은 이유는 릴리스가 임박한 시점에 배포 워크플로를 건드리지 않기 위해서다.
> 터널은 도메인 구매에 막혀 있어 급하지 않다.

### 6. 띄운다

```bash
cd /opt/ssa
docker compose -f docker-compose.tunnel.yml --env-file .env up -d
docker compose -f docker-compose.tunnel.yml --env-file .env logs -f cloudflared
```

로그에 `Registered tunnel connection` 이 여러 줄(보통 4개) 뜨면 엣지에 붙은 것이다.

**토큰이 없으면 컨테이너가 뜨지 않고 compose가 즉시 이유를 말한다.** 크래시 루프에 빠져 원인이 로그에 묻히지 않도록 `${CF_TUNNEL_TOKEN:?...}`로 막아 뒀다.

## 검증 (VPN을 끄고 한다)

이게 이 작업의 완료 조건이다. **VPN이 켜져 있으면 아무것도 증명하지 못한다.** 기존 WireGuard 경로로 닿는 것일 수 있다.

```bash
# WireGuard를 끈 상태에서, 서버가 아닌 곳에서 실행한다
curl -s -o /dev/null -w '%{http_code}\n' "https://api.<도메인>/lectures/search?keyword=test"
#   기대: 401  (인증 게이트까지 도달했다는 뜻. 200이면 게이트가 빠진 것이라 즉시 중단)

curl -s -o /dev/null -w '%{http_code}\n' -X POST "https://api.<도메인>/login"
#   기대: 400  (공개 엔드포인트이고 본문이 없어서)

curl -s -o /dev/null -w '%{http_code}\n' "https://api.<도메인>/chat/"
#   기대: 5xx가 아닐 것 (geekchat 경로가 살아 있는지)
```

`000`이나 타임아웃이면 터널이 안 붙은 것이고, `502`면 터널은 붙었는데 nginx에 못 닿는 것이다. **둘을 구분해서 봐야 어디를 고칠지 정해진다.**

### SSE는 따로 본다

```bash
curl -N -H 'Accept: text/event-stream' "https://api.<도메인>/sse-stream"
#   기대: 401 + JSON 본문 (토큰이 없으므로)
```

**SSE는 프록시 버퍼링에 민감하다.** nginx 쪽은 `proxy_buffering off`로 잡혀 있지만 Cloudflare 엣지가 또 버퍼링할 수 있다. 토큰을 가지고 실제 스트림을 열었을 때 하트비트(10초 간격)가 제때 도착하는지 확인해야 완결된다. **이건 로그인 가능한 계정이 있어야 하므로 출시 스모크에서 함께 본다.**

## 호스트네임은 하나면 된다

터널을 `api.<도메인>` 하나로 열고 그 뒤는 nginx가 경로로 가른다. 호스트를 나눌 이유가 없다.

| 경로 | 도착지 | 특성 |
|---|---|---|
| `/chat/ws` | geekchat | WebSocket. `Upgrade` 헤더 전달, `proxy_read_timeout 3600s`, 버퍼링 끔 |
| `/chat/` | geekchat | 일반 REST |
| `/sse-stream` | ssa | SSE. **버퍼링 끔**, `proxy_read_timeout 1h` |
| `/internal/` | (차단) | 서버 간 API. 외부 노출 금지 |
| `/` | ssa | 나머지 전부 |

근거: `deploy/nginx/conf.d/ssa.conf`. `server_name _`이라 호스트를 가리지 않고 경로만 본다.

**단일 호스트가 계약과도 맞는다.** 프론트 CORS는 `allowCredentials`라 정확한 오리진이 필요한데, 호스트를 쪼개면 오리진이 늘어 설정과 프리플라이트가 함께 복잡해진다.

## Cloudflare 무료 플랜의 연결 제한 (공식 문서 확인, 2026-09-10)

| 항목 | 사실 | 출처 |
|---|---|---|
| WebSocket 지원 | "WebSockets are supported on all Cloudflare plans" (무료 포함) | [Network / WebSockets](https://developers.cloudflare.com/network/websockets/) |
| WebSocket 유휴 종료 | "Cloudflare will close a WebSocket connection when no data is transmitted in either direction for a period of time." **구체적 시간은 문서에 없다.** Enterprise만 커스텀 설정 가능 | 같은 문서 |
| 524 타임아웃 | 오리진이 **125초** 안에 HTTP 응답을 시작하지 않으면 524. Enterprise만 6,000초까지 상향 가능 | [Error 524](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/) |

### 우리 SSE에 어떤 뜻인가

`SseConnectionService`는 **10초마다 하트비트**를 보낸다(`PERIOD = 10L`). 스트림 타임아웃은 60분(`DEFAULT_TIMEOUT`).

- **524는 걸리지 않는다.** 524는 "응답을 *시작*하지 않은" 경우다. SSE는 헤더를 즉시 내보내고 스트리밍한다.
- **유휴 종료도 걸리지 않을 것으로 본다.** 10초 간격은 문서가 언급하는 어떤 유휴 임계값보다도 짧다. 다만 **정확한 임계값이 문서에 없으므로 단정하지 않는다.**

**확인 필요**: 실계정으로 스트림을 열어 몇 분간 하트비트가 끊기지 않는지 본다. 엣지가 버퍼링하면 하트비트가 뭉쳐서 도착하거나 늦게 온다. 문서로는 답이 안 나오는 항목이라 실측이 유일한 방법이다.

### 채팅 WebSocket

nginx는 `proxy_read_timeout 3600s`로 잡아 뒀지만, **Cloudflare 엣지의 유휴 종료는 그것과 별개다.** geekchat 클라이언트에 ping/pong 하트비트가 있는지 확인이 필요하다. 없으면 조용한 대화가 끊긴다. 공식 문서의 권고도 클라이언트 하트비트다.

## 알려진 미해결 항목

| 항목 | 상태 |
|---|---|
| 네트워크 이름 | **추론값.** 절차 3번에서 확인 필요 |
| SSE 엣지 버퍼링 | **미검증.** 실계정 스트림으로 하트비트 확인 필요 (위 절 참고) |
| 채팅 WS 하트비트 | **미확인.** geekchat 클라이언트에 ping/pong이 있는지 봐야 한다. 없으면 조용한 대화가 엣지에서 끊긴다 |
| CORS | 도메인이 정해져야 `cors.allowed-origins`에 Vercel 오리진을 넣을 수 있다. `allowCredentials`라 와일드카드가 안 되고 정확한 오리진이 필요하다 |
| 기존 `ports: 80:80` | 터널이 붙어도 nginx의 80 포트 노출은 그대로다. 서버가 공인 IP를 갖고 있다면 터널을 우회하는 경로가 남는다. 공인 IP 노출 여부를 확인하고, 노출돼 있으면 이 포트 매핑을 걷어낼지 판단해야 한다. **Oracle(공인 IP) 서버의 처분은 [README §1B](README.md)**: 지금은 매핑을 두고 보안 목록이 80을 막으며, 첫 배포 뒤 노출 확인으로 검증한다. `127.0.0.1`에만 묶는 변경은 후속 과제다 |

마지막 항목이 보안상 가장 중요하다. **터널을 여는 것과 다른 경로를 닫는 것은 별개다.**

## 되돌리기

```bash
docker compose -f docker-compose.tunnel.yml --env-file .env down
```

앱 스택은 영향받지 않는다. 프로젝트가 다르기 때문이다. 대시보드에서 터널을 지우면 DNS 레코드도 함께 사라진다.
