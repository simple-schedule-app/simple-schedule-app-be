# Oracle Cloud Always Free(A1, arm64)로 옮길 경우: 선택지와 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **상태:** 계획만. 오너가 Oracle로 가기로 정하기(O) 전에는 실행하지 않는다.
> **요청:** 오케스트레이터(2026-10-22). 새 VM `10.64.212.40`은 22번만 열린 채 비어 있고, 관리자 계정 요청은 아직 보내지 않았다. 오너가 외부 공개 무료 호스팅을 물었고, 지속 무료로 지금 compose를 올릴 수 있는 후보로 Oracle Always Free A1이 남았다.
> **기준:** develop `61699f8`, main `e53fa71`(마지막 성공 배포 2026-10-02).

**Goal:** 오너가 Oracle A1을 고르면, 같은 이미지와 같은 CD로 arm64 공인 IP 서버에 배포할 수 있게 한다. 기존 VM(amd64, WireGuard) 경로는 그대로 남긴다.

**Architecture:** 이미지를 amd64와 arm64 두 플랫폼으로 한 태그에 만든다. CD는 `prod` 환경 변수 `DEPLOY_NETWORK`로 WireGuard를 쓸지 정하고, 값이 없으면 지금처럼 WireGuard를 쓴다. 공개 접점은 저장소에 이미 있는 Cloudflare Tunnel이라 서버에 인바운드 포트를 열지 않는다.

**Tech Stack:** GitHub Actions(`docker/setup-qemu-action@v4`, `docker/setup-buildx-action@v4`, `docker/build-push-action@v7`), Docker Compose, Ubuntu 24.04 aarch64, Cloudflare Tunnel(`cloudflare/cloudflared:2026.8.3`).

**Spec:** 위 요청 5항목, [deploy/README.md](../../../deploy/README.md), [deploy/CLOUDFLARE-TUNNEL.md](../../../deploy/CLOUDFLARE-TUNNEL.md), [deploy/docker-compose.tunnel.yml](../../../deploy/docker-compose.tunnel.yml).

## 한눈에 보기 (오너 결정용)

| # | 결정 | 추천 | 드는 것 | 이유 |
|---|---|---|---|---|
| 1 | 홈 리전 | **도쿄(Japan East, `ap-tokyo-1`)로 확정**(2026-10-24 정정. 처음 추천은 서울이었으나 가입 화면의 무료 계층 목록에 없었다) | 없음 | A1은 춘천(South Korea North)에서 만들 수 없고, 홈 리전은 가입 뒤 바꿀 수 없다. 도쿄에서는 A1을 만들 수 있다. 다만 DB(Supabase)가 서울이라 쿼리마다 리전 간 왕복이 더해진다 |
| 2 | 인스턴스 | Ubuntu 24.04 aarch64, `VM.Standard.A1.Flex` 2 OCPU / 12 GB, 부트 볼륨 50 GB | 무료 한도 안 | 무료 한도가 2 OCPU와 12 GB에 해당한다. 블록 스토리지는 부트 포함 200 GB까지 |
| 3 | arm64 이미지 | **QEMU + geekchat 빌드 단계 고정**(안 c) | `deploy.yml` 몇 줄, `geekchat/Dockerfile` 1줄 | 한 태그에 두 아키텍처가 들어가 VM과 Oracle을 오갈 수 있고, Gradle은 에뮬레이션 없이 돈다 |
| 4 | CD 접속 | **환경 변수 `DEPLOY_NETWORK=direct`** | `deploy.yml`의 `if` 두 곳과 안내 문구 | 값이 없으면 지금 동작 그대로다. `vars`는 단계 `if`에서 쓸 수 있고 `secrets`는 쓸 수 없다 |
| 5 | 공개 접점 | **Cloudflare Tunnel**(이미 있음) | 없음 | 보안 목록에서 80을 열면 TLS가 없어 토큰과 비밀번호가 평문으로 지나간다 |
| 6 | 유휴 회수 | **첫 배포 뒤 측정, 사용률 25% 미만이면 JVM 힙 고정** | compose 4줄 | `mem_limit` 합 5,120MB는 상한이지 사용량이 아니다 |
| 7 | 배포 계정 | 전용 `ssa-deploy`(docker 그룹) | 계정 하나 | 오너 키(`ubuntu`)와 CI 키를 따로 회수할 수 있다 |

비용은 Always Free 한도 안이면 0이다. 한도 밖 과금은 유료 계정으로 올렸을 때만 생긴다(Oracle 문서: 업그레이드 뒤에도 Always Free 자원은 과금하지 않고 한도 초과분만 과금).

---

## 1. 확인한 사실 (공식 문서와 저장소)

| 항목 | 내용 | 출처 |
|---|---|---|
| A1 무료 한도 | 월 1,500 OCPU 시간과 9,000 GB 시간. Always Free 테넌시에서는 2 OCPU, 12 GB에 해당 | [Always Free Resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm) |
| 스토리지, 송신 | 블록 볼륨(부트 포함) 합계 200 GB, 송신 월 10 TB | 같은 문서 |
| 리전 | Always Free 인스턴스는 홈 리전에만 만든다. A1은 춘천(South Korea North)을 뺀 모든 가용 도메인에서 만들 수 있다 | 같은 문서 |
| 홈 리전 변경 | 가입 때 정해지고 바꿀 수 없다 | [Managing Regions](https://docs.oracle.com/en-us/iaas/Content/Identity/regions/managingregions.htm) |
| 용량 부족 | "out of host capacity"는 일시적 부족이다. 다른 가용 도메인이나 나중에 다시 시도하라고 한다 | Always Free Resources |
| 유휴 회수 | 7일 동안 CPU 95퍼센타일 20% 미만, 네트워크 20% 미만, 메모리 20% 미만(A1만)이면 유휴로 본다. 유료 전환이 회수를 막는다는 문장은 없다 | Always Free Resources |
| Ubuntu 이미지 | 기본 사용자 `ubuntu`. 문서 예시는 `iptables -I INPUT 6 ... --dport 80 -j ACCEPT`와 `netfilter-persistent save`, 그리고 보안 목록 인그레스 규칙 | [Oracle Apache on Ubuntu 튜토리얼](https://docs.oracle.com/en-us/iaas/developer-tutorials/tutorials/apache-on-ubuntu/01oci-ubuntu-apache-summary.htm) |
| Docker와 호스트 방화벽 | Docker는 컨테이너 트래픽을 `nat` 테이블에서 라우팅해 방화벽 규칙보다 먼저 보낸다. 그래서 ufw 설정이 공개 포트에는 사실상 적용되지 않는다 | [Docker packet filtering](https://docs.docker.com/engine/network/packet-filtering-firewalls/) |
| Docker CE | Ubuntu 22.04, 24.04, 26.04의 arm64를 지원한다 | [Install on Ubuntu](https://docs.docker.com/engine/install/ubuntu/) |
| 베이스 이미지 | `eclipse-temurin:21-jre-jammy`(app)와 `21-jre-alpine`(geekchat)에 arm64 v8이 있다 | Docker Hub 태그 API(2026-10-22 조회) |
| 의존 이미지 | cp-kafka 7.3.2, cp-zookeeper 7.3.2, redis 7.2, nginx 1.27-alpine, cloudflared 2026.8.3의 arm64 매니페스트 | 오케스트레이터가 `docker manifest inspect`로 확인(이 계획에서 다시 보지 않음) |
| GitHub arm64 러너 | `ubuntu-24.04-arm` 등. 공개 저장소에서 무료, 4 CPU / 16 GB | [GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners). 이 저장소는 PUBLIC(`gh repo view`) |
| 단계 `if`의 컨텍스트 | `vars`는 쓸 수 있고 `secrets`는 목록에 없다 | [Contexts](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts) |
| 멀티 플랫폼 빌드 | QEMU 에뮬레이션은 네이티브보다 훨씬 느릴 수 있다. `FROM --platform=$BUILDPLATFORM`으로 빌드 단계를 빌더의 네이티브 플랫폼에 고정하면 에뮬레이션을 피한다 | [Multi-platform builds](https://docs.docker.com/build/building/multi-platform/) |
| 현재 이미지 빌드 | `deploy.yml`의 build-push-action에 `platforms`도 buildx 설정 단계도 없다(amd64만). app은 jar를 러너에서 만들어 복사하고(`app/Dockerfile`, 런타임 단계에 `RUN` 한 줄), geekchat은 이미지 안에서 Gradle을 돌린다(`geekchat/Dockerfile:4`, 런타임 단계에 `RUN` 없음) | 저장소 |
| 현재 빌드 시간 | 2026-10-02 Deploy: geekchat 3분 2초, app 4분 22초, deploy 1분 36초 | `gh run view` |
| 공개 접점 | 터널은 별도 compose 프로젝트 `ssa-tunnel`로, CD와 수명이 분리돼 있다. 오너가 한 번 띄운다 | `deploy/docker-compose.tunnel.yml`, `deploy/CLOUDFLARE-TUNNEL.md` |
| 네이티브 의존 | 빌드 파일에 `osdetector`, `epoll`, `tcnative`, `classifier` 선언이 0건이다(같은 검색으로 `spring`이 든 `.kts` 14개가 잡혀 검색은 동작한다) | 저장소 |

## 2. 결정별 선택지

### 2.1 arm64 이미지

| 안 | 바꿀 것 | 장점 | 단점 |
|---|---|---|---|
| (a) QEMU로 그대로 | `deploy.yml`에 QEMU와 Buildx 설정, `platforms` | 변경이 가장 작다 | geekchat의 Gradle 빌드가 arm64 에뮬레이션으로 돈다. 지금 3분인 빌드가 몇 배로 늘 수 있다(Docker 문서의 경고, 실측 전) |
| (b) arm64 호스티드 러너 | 빌드 잡의 `runs-on: ubuntu-24.04-arm` | 에뮬레이션이 없다. 공개 저장소라 무료 | 그대로면 arm64 전용 이미지가 된다. VM(amd64)으로 돌아갈 길을 남기려면 러너 두 종류로 나눠 빌드하고 매니페스트를 합쳐야 해서 워크플로가 커진다 |
| **(c) 추천: QEMU + 빌드 단계 고정** | (a)에 더해 `geekchat/Dockerfile:4`를 `FROM --platform=$BUILDPLATFORM ...`으로 | Gradle은 러너 네이티브로 돌고 jar는 아키텍처와 무관하다. geekchat 런타임 단계엔 `RUN`이 없어 에뮬레이션이 필요 없고, app은 `RUN groupadd/useradd` 한 줄만 에뮬레이션한다. 한 잡에 한 태그로 amd64와 arm64가 같이 나온다 | QEMU와 Buildx 설정 단계가 는다 |

오케스트레이터가 낸 (c) "jar는 러너에서 만들고 이미지는 복사만"은 app이 이미 그렇게 하고 있다. geekchat도 같은 효과를 Dockerfile 한 줄로 얻는다.

### 2.2 CD 접속 경로

| 안 | 내용 | 판단 |
|---|---|---|
| **A 추천: 환경 변수** | `prod` 환경 변수 `DEPLOY_NETWORK`가 `direct`면 WireGuard 설정과 해제를 건너뛴다. 없거나 그 밖의 값이면 지금처럼 WireGuard | 경로는 서버의 성질이라 실행마다 고르는 입력보다 환경에 두는 것이 맞다. 기본값이 지금 동작이라 VM 경로가 깨지지 않는다 |
| B: 시크릿 유무로 추론 | WG 시크릿이 비면 건너뛴다 | `secrets`는 단계 `if`에 쓸 수 없어 잡 환경 변수로 우회해야 한다. 시크릿을 지우는 일이 경로 전환이 되는 암묵적인 설계라 실수하기 쉽다 |
| C: `workflow_dispatch` 입력 | 실행 때 고른다 | main 푸시 자동 배포에는 입력이 없어 기본값이 필요하고, 결국 A와 같은 판단이 남는다 |

공인 IP라 22번이 인터넷에 열린다. GitHub 러너의 IP는 넓고 바뀌어 허용 목록으로 좁히기 어렵다. 키 인증만 허용하는지 `sshd -T`로 확인한다(§3 Task 3). 터널을 통한 SSH는 나중의 선택지로 남긴다.

### 2.3 공개 접점

터널을 쓰면 보안 목록은 기본값(22)만 두고 80과 443을 열지 않는다. 지금 nginx는 80만 듣고 TLS가 없다(`docker-compose.prod.yml`의 "TLS 도입 시: 443" 주석). 옛 서버는 VPN 뒤라 평문이 허용됐지만, 공인 IP에서 80을 열면 로그인 비밀번호와 JWT가 평문으로 지나간다. 직접 공개하려면 TLS부터 넣어야 하고, 이는 이 계획의 범위 밖이다.

Oracle 튜토리얼의 iptables 단계는 호스트에서 직접 듣는 프로세스용이다. Docker로 연 포트는 호스트 방화벽 규칙보다 먼저 라우팅되므로(Docker 문서), 우리 구성에서 실제 관문은 보안 목록이다. 이미지 기본 규칙과 Docker 규칙이 어떻게 겹치는지는 서버에서 `sudo iptables -S`로 확인한다.

### 2.4 유휴 회수

트래픽이 적으면 CPU와 네트워크는 20% 아래일 가능성이 크다. 그러면 메모리 하나가 회수를 막는 유일한 기준이 된다.

`mem_limit` 합 5,120MB는 상한일 뿐이다. app과 geekchat은 `MaxRAMPercentage`만 있고 초기 힙은 JVM 기본값이라 쓰는 만큼만 커진다(`docker-compose.prod.yml:45`, `:86`). Kafka는 `-Xms256m`, ZooKeeper는 `-Xms128m`이다(`:135`, `:118`). 그래서 한가할 때 실제 사용량이 12 GB의 20%(약 2.4 GB)를 넘는다고 장담할 수 없다. **오케스트레이터 메시지의 "상한 합 약 5 GB라 20%를 넘길 것"은 근거가 맞지 않는다.** 결론은 측정해야 나온다.

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **M1 추천: 측정 뒤 힙 고정** | 첫 배포 뒤 사용률이 25% 미만이면 JVM 초기 힙을 최대와 같게 하고 `AlwaysPreTouch`, Kafka와 ZooKeeper도 `Xms`를 `Xmx`와 같게(Task 4) | 상주 메모리가 정해진다. 힙만 합쳐 약 2.7 GB이고, 비힙과 OS를 더하면 약 3.5 GB(12 GB의 29%, 추정)다. VM에서도 해가 없다 | 기동이 조금 느려지고 메모리를 늘 잡아 둔다. 회수 기준을 넘기려는 목적이 섞여 있다는 점은 오너가 알고 고른다 |
| M2 인스턴스 메모리를 줄여 만든다 | 예: 2 OCPU / 8 GB(문턱 1.6 GB) | 설정 변경이 없다 | 사용량이 문턱을 넘는지 역시 측정 전이다. 나중에 모양을 바꾸면 재시작이 필요하고, A1은 용량 부족 오류가 잦다 |
| M3 유료 계정 전환 | 결제 수단 등록 | 용량 부족 때 도움이 된다고 문서가 말한다 | 회수를 막는다는 근거는 문서에서 찾지 못했다. 한도 초과 과금 위험이 생긴다 |

---

## Global Constraints

- 기존 VM(amd64, WireGuard) 경로를 깨지 않는다. `DEPLOY_NETWORK`가 없으면 지금과 같은 단계가 돈다.
- 이미지 태그 규칙(`<sha>`, `latest`)과 deploy 잡의 compose 명령은 바꾸지 않는다.
- 액션 메이저 버전: `docker/setup-qemu-action@v4`, `docker/setup-buildx-action@v4`(둘 다 2026-09 최신 v4.4.x), `docker/build-push-action@v7`, `docker/login-action@v4`.
- 빌드 플랫폼: `linux/amd64,linux/arm64`.
- `DEPLOY_NETWORK`의 값은 소문자 `direct` 하나다. 다른 값은 모두 WireGuard로 본다.
- 서버의 인바운드 80과 443을 열지 않는다.
- 시크릿 값은 문서, 로그, 커밋에 남기지 않는다. 변수와 시크릿의 이름만 적는다.
- develop → main 머지(운영 배포)는 오너 확인 뒤에만 한다.

## Review Focus

1. **arm64 서버가 amd64 전용 이미지를 받는다.** 컨테이너가 `exec format error`로 뜨지 않는다. Task 1의 매니페스트 확인 단계와 Task 5의 순서로 막는다.
2. **보안 목록에서 80을 연다.** 평문 HTTP가 공개된다. Task 3에 외부에서 80 접속이 실패하는지 보는 확인을 둔다.
3. **`DEPLOY_NETWORK` 오타(`Direct`, `DIRECT`).** WireGuard 경로로 가서 WG 단계에서 실패한다. 실패가 시끄럽게 드러나는 쪽이라 받아들이고, Task 2의 머리 주석에 정확한 값을 적는다.
4. **유휴 회수.** 7일 기준이라 첫 주에 드러난다. Task 4의 측정과 콘솔 지표를 일주일 본다.
5. **arm64에서 전이 의존성의 네이티브 라이브러리**(Netty epoll, gRPC 등)가 없거나 다르다. 대개 순수 Java로 물러나지만 확인 전이다. Task 3의 첫 배포 확인에 로그 검사를 둔다.

---

## 3. 구현 (오너가 O를 준 뒤)

### Task 1: 이미지를 amd64와 arm64로 함께 만든다

**Files:**
- Modify: `geekchat/Dockerfile:4`
- Modify: `.github/workflows/deploy.yml`(build-and-push 잡, `Login to GHCR` 78행과 `Build & Push image` 85~93행 근처)

**Interfaces:**
- Produces: GHCR `ssa-app:<sha>`, `ssa-geekchat:<sha>`가 `linux/amd64`와 `linux/arm64`를 담은 매니페스트 목록이 된다. 태그 이름과 deploy 잡은 그대로라 서버는 자기 아키텍처를 받는다.

- [ ] **Step 1: geekchat 빌드 단계를 빌더 플랫폼에 고정한다**

`geekchat/Dockerfile:4`를 `FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder`로 바꾼다. 런타임 단계(`FROM eclipse-temurin:21-jre-alpine`)는 그대로 둔다.

- [ ] **Step 2: 로컬에서 두 플랫폼 이미지를 만들어 아키텍처를 확인한다**

베이스 이미지를 받아야 하므로 실행 전에 오너에게 내려받기를 확인받는다. 이 맥은 arm64라 arm64는 네이티브, amd64는 에뮬레이션으로 돈다.

```bash
docker buildx build --platform linux/arm64 --load -t ssa-geekchat:arm64-check geekchat
docker run --rm --entrypoint uname ssa-geekchat:arm64-check -m        # 기대: aarch64
docker buildx build --platform linux/amd64 --load -t ssa-geekchat:amd64-check geekchat
docker run --rm --entrypoint uname ssa-geekchat:amd64-check -m        # 기대: x86_64
memguard -- ./gradlew --max-workers=2 :app:bootJar
docker buildx build --platform linux/arm64 --load -f app/Dockerfile -t ssa-app:arm64-check .
docker run --rm --entrypoint uname ssa-app:arm64-check -m             # 기대: aarch64
docker run --rm --entrypoint id ssa-app:arm64-check                   # spring의 uid. #89에서 가정으로 둔 값을 확정한다
```

- [ ] **Step 3: CD 빌드 잡에 QEMU, Buildx, 플랫폼을 넣는다**

`Login to GHCR` 앞에 `docker/setup-qemu-action@v4`(입력 `platforms: arm64`)와 `docker/setup-buildx-action@v4` 단계를 둔다. `Build & Push image`의 `with:`에 `platforms: linux/amd64,linux/arm64`를 더한다. 그 뒤에 `Show image platforms` 단계를 두고 `docker buildx imagetools inspect ${{ env.REGISTRY }}/${{ github.repository_owner }}/ssa-${{ matrix.module }}:${{ github.sha }}`를 돌린다. 이 출력에 `linux/amd64`와 `linux/arm64`가 둘 다 있어야 한다.

- [ ] **Step 4: 워크플로 문법을 검사한다**

`deploy.yml`은 main 푸시와 수동 실행에서만 돌아서, 문법 오류가 릴리스 때 처음 드러난다. 그래서 머지 전에 actionlint로 본다(이미지 내려받기는 오너 확인).

```bash
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12 -color .github/workflows/deploy.yml
```

기대: 출력 없음, 종료 코드 0. `1.7.12`는 2026-10-22 기준 최신 릴리스(2026-03-30)다. 실행 때 더 새 판이 있으면 그 값으로 고정하고 커밋 메시지에 남긴다.

- [ ] **Step 5: Commit**

```bash
git add geekchat/Dockerfile .github/workflows/deploy.yml
git commit -m "build(deploy): 이미지를 amd64와 arm64로 함께 만든다"
```

첫 실증은 릴리스 Deploy 실행이다. `Show image platforms` 로그에 두 플랫폼이 나오는지, 빌드 시간이 기준(geekchat 3분 2초, app 4분 22초)보다 얼마나 늘었는지 PR에 남긴다.

### Task 2: CD가 WireGuard 없이도 배포한다

**Files:**
- Modify: `.github/workflows/deploy.yml` 머리 주석(3~14행), `Set up WireGuard tunnel`(105행), `Verify tunnel connectivity (fail fast)`(147~159행), `Teardown WireGuard`(207~209행)

**Interfaces:**
- Consumes: GitHub 환경 `prod`의 변수 `DEPLOY_NETWORK`(`vars.DEPLOY_NETWORK`). 시크릿이 아니라 변수다.

- [ ] **Step 1: 머리 주석에 변수를 적는다**

필요한 시크릿 목록 아래에 `DEPLOY_NETWORK`(환경 변수): `direct`면 공인 IP로 바로 SSH(WireGuard 단계 건너뜀), 없거나 다른 값이면 WireGuard. `direct`일 때 `WG_*` 시크릿은 필요 없다.

- [ ] **Step 2: WireGuard 설정과 해제에 조건을 단다**

`Set up WireGuard tunnel`에 `if: vars.DEPLOY_NETWORK != 'direct'`, `Teardown WireGuard`의 `if: always()`를 `if: always() && vars.DEPLOY_NETWORK != 'direct'`로 바꾼다.

- [ ] **Step 3: 접속 확인 단계의 이름과 안내를 경로에 맞춘다**

이름을 `Verify SSH connectivity (fail fast)`로 바꾼다. 재시도 루프는 그대로 두고, 마지막 `::error::` 안내만 경로별로 나눈다.
- WireGuard: 지금 문구(같은 피어 키를 쓰는 다른 기기)에 "키, 계정, `authorized_keys`, 호스트 키 고정이 틀려도 같은 증상"을 더한다.
- `direct`: "공인 IP SSH 불가: 보안 목록의 22, 배포 키, 계정, 호스트 키 고정(`DEPLOY_SSH_KNOWN_HOSTS`)을 확인".

- [ ] **Step 4: 검사한다**

Task 1 Step 4의 actionlint를 다시 돌린다(기대: 출력 없음). `git diff`에서 변수가 없을 때 바뀌는 것이 안내 문구뿐인지 본다.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "build(deploy): DEPLOY_NETWORK=direct면 WireGuard 없이 공인 IP로 배포한다"
```

### Task 3: 런북에 Oracle 변형을 더한다

**Files:**
- Modify: `deploy/README.md`. §1 뒤, `## 2. GitHub 설정 (1회)`(82행) 앞에 `## 1B. Oracle Cloud Always Free(A1) 서버 세팅`. §2에 Oracle 항목 한 줄.

**Interfaces:**
- Consumes: Task 2의 `DEPLOY_NETWORK`. 기존 `CLOUDFLARE-TUNNEL.md` 절차.

- [ ] **Step 1: §1B를 쓴다.** 담을 값은 아래와 같다.
  - 가입: 홈 리전은 **Japan East(Tokyo, `ap-tokyo-1`)**(2026-10-24 정정. 서울은 가입 화면의 무료 계층 목록에 없었다). 춘천은 A1을 만들 수 없고 홈 리전은 바꿀 수 없다.
  - 인스턴스: `VM.Standard.A1.Flex` 2 OCPU / 12 GB, Ubuntu 24.04(aarch64), 부트 볼륨 50 GB, 공인 IP, 오너 SSH 공개키. "out of host capacity"면 다른 가용 도메인이나 나중에 다시 시도한다.
  - 보안 목록: 기본(22)만 둔다. 80과 443은 열지 않는다(§2.3).
  - OS 방화벽: 이미지의 iptables는 건드리지 않는다. Docker가 뜬 뒤에 `netfilter-persistent save`를 돌리지 않는다. 그러면 Docker가 만든 규칙까지 파일에 저장된다.
  - Docker CE: Docker 공식 Ubuntu 명령을 그대로 옮긴다(apt 저장소 설정과 `docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin`).
  - 배포 계정: `sudo useradd -m -s /bin/bash ssa-deploy`(비밀번호는 두지 않는다), `sudo usermod -aG docker ssa-deploy`. 그 계정의 `~/.ssh/authorized_keys`(디렉터리 700, 파일 600, 소유자 `ssa-deploy`)에 CI 배포 키 공개키를 넣는다. 오너는 `ubuntu` 계정을 쓴다. 키 로그인이 되는지는 §2의 사전 점검이 확인한다.
  - `/opt/ssa`: §1과 같다(`secrets` 700, `.env` 600, FCM 키 644). Ubuntu라 SELinux 라벨 작업은 없다.
  - sshd 확인: `sudo sshd -T | grep -i -e '^passwordauthentication' -e '^permitrootlogin'`. 비밀번호 로그인이 `no`인지 본다.
  - 공개 접점: `CLOUDFLARE-TUNNEL.md` 절차 그대로 한다.
  - 노출 확인: 서버가 아닌 곳에서 `curl -m 5 -s -o /dev/null -w '%{http_code}\n' "http://<공인 IP>/"`. 기대는 연결 실패(`000`)다. 응답이 오면 보안 목록에 80이 열린 것이다.
- [ ] **Step 2: §2에 Oracle 항목을 더한다.** 넣을 값은 다음과 같다. `WG_*`는 필요 없다.
  - 시크릿: `DEPLOY_HOST`=공인 IP, `DEPLOY_USER`=`ssa-deploy`, `DEPLOY_SSH_KEY`, `DEPLOY_SSH_KNOWN_HOSTS`(`ssh-keyscan <공인 IP>`. 지문은 오너가 `ubuntu`로 붙어 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`로 본 값과 대조).
  - 환경 변수: `DEPLOY_NETWORK`=`direct`.
  - 사전 점검: §2의 3 명령을 VPN 없이 그대로 돌린다.
- [ ] **Step 3: 첫 배포 확인과 유휴 회수 측정을 적는다.**
  - 스모크 401을 확인한다.
  - `docker logs ssa-app 2>&1 | grep -i -e UnsatisfiedLinkError -e 'native'`과 `docker logs ssa-geekchat 2>&1 | grep -i UnsatisfiedLinkError`가 비어 있는지 본다.
  - `free -m`의 used / total과 `docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'`를 기록한다.
  - 콘솔의 인스턴스 Metrics에서 Memory Utilization을 첫 주 동안 본다.
- [ ] **Step 4: 검사한다.** 링크한 상대 경로가 실재하는지, em dash와 가운뎃점이 0건인지(같은 검사가 기존 줄의 em dash를 잡는지 역검증) 본다.
- [ ] **Step 5: Commit** `docs(deploy): Oracle Always Free(A1) 서버 세팅 변형을 런북에 더한다`

### Task 4 (조건부): 메모리 상주량을 고정한다

**조건:** 첫 배포 뒤 `free -m`의 used가 total의 25% 미만이거나, 콘솔 Memory Utilization이 사흘 동안 25% 미만이다. 둘 다 아니면 이 Task는 하지 않는다.

**Files:**
- Modify: `deploy/docker-compose.prod.yml:45`(app `JAVA_OPTS`), `:86`(geekchat `JAVA_OPTS`), `:118`(ZooKeeper `KAFKA_HEAP_OPTS`), `:135`(Kafka `KAFKA_HEAP_OPTS`)

- [ ] **Step 1: 값을 바꾼다.** 정확한 값은 아래와 같다.
  - app: `-XX:+UseG1GC -XX:InitialRAMPercentage=65.0 -XX:MaxRAMPercentage=65.0 -XX:+AlwaysPreTouch`
  - geekchat: `-XX:+UseG1GC -XX:InitialRAMPercentage=60.0 -XX:MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=192m -XX:+AlwaysPreTouch`
  - ZooKeeper: `-Xmx256m -Xms256m`
  - Kafka: `-Xmx512m -Xms512m`
- [ ] **Step 2: 배포 뒤 확인한다.**
  - `docker stats --no-stream`에서 ssa-app이 약 1.3 GiB 이상, ssa-geekchat이 약 0.6 GiB 이상인지 본다.
  - `free -m`의 used / total이 25% 이상인지 본다.
  - 스모크 401을 확인한다. 기동이 늦어 스모크(10초 간격 6회)를 넘기면 `docker logs ssa-app | grep 'Started'`의 기동 시간을 PR에 남긴다.
- [ ] **Step 3: Commit** `build(deploy): JVM과 Kafka 힙을 시작부터 잡아 상주 메모리를 고정한다`

### Task 5: 릴리스 순서 (PR #90)

- #90은 열어 둔 채로 둔다. head가 develop이라, Task 1~3이 develop에 들어가면 #90에 자동으로 실린다.
- **Oracle 첫 배포에는 Task 1이 반드시 들어 있어야 한다.** 없으면 arm64 서버가 amd64 이미지를 받는다.
- #90 본문의 "머지 전" 1~3번을 §1B로 바꾸고, 포함 목록에 Task 1~3의 PR을 더한다.
- 특강 정원 키 확인(`HANDOFF.md` §5의 2)은 서버가 바뀌어도 그대로 필요하다. 새 서버의 Redis는 어느 쪽이든 비어 있다.

## 4. 실행 순서와 역할

1. 오너: O 결정 → 도쿄 리전으로 가입(2026-10-24 완료. 서울은 가입 화면 목록에 없었다) → 인스턴스 생성(용량 부족이면 재시도. 도쿄는 가용 도메인이 하나다)
2. BE: Task 1, 2, 3을 develop에 넣는다. 서버 준비와 동시에 할 수 있다.
3. 오너: §1B 서버 세팅 → 터널 → 시크릿과 `DEPLOY_NETWORK` → 노트북 사전 점검
4. 오너 확인 → #90 머지(배포) → 스모크와 §1B 첫 배포 확인
5. 측정 결과에 따라 Task 4

## 5. 확인하지 못한 것

- Oracle의 메모리 사용률 지표가 페이지 캐시를 "사용"으로 세는지 모른다. 첫 주 콘솔 지표로 본다.
- 유료 전환이 유휴 회수를 막는지 모른다. 문서에 그런 문장이 없다.
- arm64에서 전이 의존성의 네이티브 라이브러리가 정상인지 모른다. 첫 배포 로그로 본다.
- Oracle Ubuntu 이미지의 sshd 기본값과 iptables 기본 규칙을 확인하지 않았다. 서버에서 `sshd -T`와 `iptables -S`로 본다.
- QEMU로 늘어나는 빌드 시간은 실측 전이다(추정: app은 `RUN` 한 줄이라 작다).
- 의존 이미지 다섯의 arm64 매니페스트는 오케스트레이터 확인에 기댄다.
