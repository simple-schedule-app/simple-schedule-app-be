# SSA 배포 가이드

운영 서버: **Oracle Cloud Always Free A1(arm64)**. 2026-10-23 오너가 정했고, 세팅은 §1B다.
예비 서버: Rocky Linux 10.2, 12 vCPU / 24 GiB / 디스크 1 TiB, WireGuard VPN 뒤 (`10.64.212.40`, 호스트명 `agent-worker`). 2026-10-18 새로 프로비저닝됐고 서버 관리자의 에이전트 작업과 같은 VM을 쓴다(공유 호스트). 세팅은 §1이다.
옛 서버(`10.64.212.20`)는 내려갔고, 그 위의 `.env`와 FCM 키와 볼륨은 남아 있지 않다.
전략: GitHub Actions → GHCR 이미지 푸시(amd64와 arm64를 한 태그에) → SSH(VM은 WireGuard 터널 경유, Oracle은 공인 IP)로 `docker compose pull && up -d`.

## 0. 보안 선행 조치 (배포 전 필수)

> ⚠ 아래 자격증명들은 채팅/저장소에 노출된 이력이 있으므로 **전부 재발급** 후 사용한다.

| 항목 | 상태 | 조치 |
|---|---|---|
| WireGuard 개인키/PSK (개인 노트북용) | 채팅에 노출됨 | 서버 관리자(DokaDev)에게 요청해 **피어 재발급**. CI용 피어는 별도로 신규 발급 |
| SSH 비밀번호 (`1234`) | 채팅에 노출됨 | 비밀번호 로그인 비활성화, **ed25519 키 인증 전환**, `PasswordAuthentication no` |
| JWT 시크릿 (`application-common-local.yml`) | git 히스토리에 커밋됨 | 운영용 신규 발급(`openssl rand -base64 64`), 로컬용도 교체 권장 |
| DB root/1234 | git 히스토리에 커밋됨 | 운영은 `.env`의 앱 전용 계정 + 강력한 비밀번호 |
| FCM 서비스 계정 JSON | 로컬에만 존재(커밋 안 됨, gitignore 처리됨) | Firebase 콘솔에서 키 로테이션 후 서버 `/opt/ssa/secrets/`에만 배치 |

## 1. 서버 최초 세팅 (1회)

계정은 서버 관리자에게 요청한다. 배포 계정(`DEPLOY_USER`)은 sudo 없이 `docker` 그룹만 가진 전용 계정을 권장한다(`deploy.yml` 상단 주석). 아래 `sudo` 명령은 관리자나 sudo 권한이 있는 계정이 한 번 실행한다. `<배포 계정>`은 관리자가 만든 계정 이름으로 바꾼다.

이 서버는 관리자의 작업과 함께 쓰는 공유 호스트다. sshd 설정과 방화벽은 관리자와 맞춘 뒤 바꾸고, 관리자가 쓰는 서비스(22의 ssh, 9090의 Cockpit)는 닫지 않는다.

```bash
# Docker CE와 compose 플러그인 (Docker 공식 RHEL 설치 문서 기준. 지원 목록은 RHEL 8, 9, 10이고 Rocky는 따로 적혀 있지 않다)
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker <배포 계정>

# 배포 전용 디렉토리 (배포 계정 소유). nginx 디렉터리를 미리 만드는 이유는 아래 SELinux 절 참고
sudo mkdir -p /opt/ssa/secrets /opt/ssa/nginx && sudo chown -R <배포 계정> /opt/ssa
sudo chmod 700 /opt/ssa/secrets

# 배포 키 등록 (로컬에서 새 키 발급 후 공개키만 서버로)
#   새 서버에는 옛 공개키가 없고, 옛 개인키는 GitHub 시크릿에서 다시 꺼낼 수 없다.
#   새로 발급해 공개키는 서버에, 개인키는 DEPLOY_SSH_KEY 시크릿에 넣는다(§2).
#   배포 계정에 비밀번호가 없어 ssh-copy-id가 안 되면, sudo 계정이 그 계정의 ~/.ssh/authorized_keys에 넣는다.
ssh-keygen -t ed25519 -f ~/.ssh/ssa_deploy -C "ssa-deploy"
ssh-copy-id -i ~/.ssh/ssa_deploy.pub <배포 계정>@10.64.212.40

# sshd 강화 (서버에서, 관리자와 맞춘 뒤)
#   /etc/ssh/sshd_config: PasswordAuthentication no, PermitRootLogin no
#   RHEL 계열은 sshd_config 위쪽에서 sshd_config.d/*.conf를 먼저 불러오고, sshd는 처음 읽은 값을 쓴다.
#   그래서 파일을 고친 것으로 끝내지 않고 실제로 적용된 값을 확인한다.
sudo systemctl restart sshd
sudo sshd -T | grep -i -e '^passwordauthentication' -e '^permitrootlogin'   # 둘 다 no 인가

# 방화벽: 80과 443을 연다. 기존 ssh와 cockpit 서비스는 그대로 둔다
#   DB/Redis/Kafka는 compose가 호스트 포트로 열지 않는다(호스트에 여는 것은 nginx의 80뿐)
sudo firewall-cmd --permanent --add-service=http --add-service=https
sudo firewall-cmd --reload
```

`.env` 구성: `deploy/.env.example`을 `/opt/ssa/.env`로 복사해 값 채움 (`chmod 600`). 2026-10 서버 교체로 옛 값은 남아 있지 않다. 노출 이력이 있는 시크릿(§0)은 이번에 새로 발급한다.
FCM JSON: `/opt/ssa/secrets/fcm-service-account.json` 배치 (`chmod 644`). 600으로 두면 안 된다. 앱 컨테이너는 이미지 안의 시스템 계정 `spring`으로 돌고(`app/Dockerfile`), 그 uid는 배포 계정과 다르다. 그러면 컨테이너가 키를 못 읽고, `FcmConfig`가 `IllegalStateException`을 던져 앱이 기동하지 못한다. 키는 상위 디렉터리 `secrets`의 700 권한이 지킨다. 서버의 다른 계정은 그 디렉터리를 지나갈 수 없고, 컨테이너는 디렉터리를 거치지 않고 파일만 마운트로 받는다.

**SELinux (Rocky 기본은 enforcing). 키를 넣은 뒤에 한다.** nginx 설정(`./nginx/...`)과 FCM 키(`./secrets/fcm-service-account.json`)는 바인드 마운트로 컨테이너에 들어간다. SELinux가 막으면 nginx는 설정을 못 읽어 뜨지 않고, 앱은 위와 같은 이유로 기동하지 못한다. Docker가 SELinux 라벨을 쓰는지는 데몬 설정에 달려 있으므로, 기본값을 가정하지 않고 서버에서 확인한다.

```bash
getenforce                                    # Enforcing 인가
docker info --format '{{.SecurityOptions}}'   # name=selinux 가 들어 있는가
```

둘 다 해당하면 두 디렉터리에 컨테이너용 라벨 규칙을 등록하고 적용한다. `semanage`가 없으면 `sudo dnf install -y policycoreutils-python-utils`로 설치한다.

```bash
sudo semanage fcontext -a -t container_file_t '/opt/ssa/(nginx|secrets)(/.*)?'
sudo restorecon -Rv /opt/ssa/nginx /opt/ssa/secrets
```

- **`chcon` 대신 규칙으로 등록하는 이유:** `chcon`으로 붙인 라벨은 `restorecon`을 돌리거나 디렉터리를 다시 만들면 사라진다.
- **`/opt/ssa/nginx`를 첫 배포 전에 만들어 두는 이유:** 이 디렉터리는 원래 첫 배포 때 CD가 복사하며 만든다. 그렇게 새로 생긴 디렉터리는 등록한 규칙이 아니라 상위 디렉터리의 라벨을 받는다. 미리 만들어 라벨을 붙여 두면, 이후 배포마다 복사되는 파일이 그 라벨을 물려받는다.
- **키를 넣은 뒤에 하는 이유:** 같은 파일시스템 안에서 `mv`로 옮긴 파일은 원래 자리의 라벨을 그대로 가져온다. 나중에 키를 바꿔 넣을 때도 `restorecon`을 다시 돌린다.

하나라도 해당하지 않으면 할 일이 없다.

**확인.** 첫 배포의 스모크(401)가 통과하면 두 마운트 모두 읽힌 것이다. nginx가 떠 있어야 응답이 오고, 앱은 FCM 키를 읽어야 기동하기 때문이다. 실패하면 `docker logs ssa-nginx`에서 `Permission denied`를, `docker logs ssa-app`에서 `Failed to initialize FirebaseApp`을 찾는다. 파일 권한 문제인지 SELinux 문제인지는 `sudo ausearch -m avc -ts recent`에 거부 기록이 있는지로 가른다.

## 1B. Oracle Cloud Always Free(A1) 서버 세팅 (1회)

선택지와 근거는 [Oracle 호스팅 계획](../docs/superpowers/plans/2026-10-22-oracle-a1-hosting.md)에 있다.

**가입과 인스턴스 (오너, Oracle 콘솔)**

- 홈 리전은 **Japan East(Tokyo, `ap-tokyo-1`)**다. 2026-10-24에 가입했는데, 가입 화면의 무료 계층 리전 목록에 South Korea Central(Seoul)이 없어 도쿄로 정했다. 서울을 다시 찾지 않는다. A1은 South Korea North(Chuncheon)에서 만들 수 없고, 홈 리전은 가입 뒤 바꿀 수 없으며, Always Free 인스턴스는 홈 리전에만 만든다.
- 인스턴스: shape `VM.Standard.A1.Flex` 2 OCPU / 12 GB, 이미지 Ubuntu 24.04(aarch64), 부트 볼륨 50 GB, 공인 IP, 오너 SSH 공개키. "out of host capacity"가 나오면 나중에 다시 시도한다. 도쿄는 가용 도메인이 하나라 다른 가용 도메인으로 피할 수 없다(Oracle Regions 문서).
- 보안 목록(Security List)은 기본 인그레스(22)만 둔다. **80과 443은 열지 않는다.** nginx는 TLS 없이 80만 듣는다. 열면 로그인 비밀번호와 토큰이 평문으로 지나간다. 공개는 아래 Cloudflare Tunnel로만 한다.
- nginx는 `docker-compose.prod.yml`의 `ports: "80:80"`로 호스트의 80에 열려 있고, 지금은 이 매핑을 그대로 둔다. 그래서 공인 IP에서 80을 막는 관문은 보안 목록 하나다(호스트 방화벽은 Docker가 연 포트를 막지 못한다). 아래 "첫 배포 뒤 확인"의 노출 확인으로 검증한다. 매핑을 `127.0.0.1`에만 묶어 관문을 둘로 만드는 일은 아직 하지 않았다(후속 과제, [CLOUDFLARE-TUNNEL.md](CLOUDFLARE-TUNNEL.md)의 "기존 `ports: 80:80`" 항목).

**서버 (오너 계정 `ubuntu`로 접속)**

```bash
# OS 방화벽(iptables)은 건드리지 않는다. Docker로 연 포트는 호스트 방화벽 규칙보다 먼저 라우팅되므로
# 실제 관문은 보안 목록이다. Docker가 뜬 뒤에 netfilter-persistent save를 돌리지 않는다
# (Docker가 만든 규칙까지 파일에 저장된다).

# sshd: passwordauthentication은 no여야 한다. permitrootlogin은 no, prohibit-password, without-password 중
# 하나면 된다(뒤의 둘은 같은 뜻으로 Ubuntu 기본값이고, sshd -T는 without-password로 찍을 수 있다. root 비밀번호 로그인은 막힌다)
sudo sshd -T | grep -i -e '^passwordauthentication' -e '^permitrootlogin'
#   다르면 맨 먼저 읽히는 드롭인으로 덮는다. sshd는 처음 읽은 값을 쓰고 sshd_config.d/*.conf는 이름순으로 읽는다
#   printf 'PasswordAuthentication no\nPermitRootLogin no\n' | sudo tee /etc/ssh/sshd_config.d/00-ssa-hardening.conf
#   sudo systemctl reload ssh      # Ubuntu 24.04의 서비스 이름은 ssh다(§1 Rocky의 sshd와 다르다)
#   그 뒤 위 sshd -T를 다시 본다

# Docker CE (Docker 공식 Ubuntu 설치 문서 그대로. arm64 지원)
sudo apt update
sudo apt install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# CI 전용 배포 계정. 비밀번호 없이 키로만 들어온다. 오너 키(ubuntu)와 따로 회수할 수 있다
sudo useradd -m -s /bin/bash ssa-deploy
sudo usermod -aG docker ssa-deploy
sudo install -d -m 700 -o ssa-deploy -g ssa-deploy /home/ssa-deploy/.ssh
sudo install -m 600 -o ssa-deploy -g ssa-deploy /dev/null /home/ssa-deploy/.ssh/authorized_keys
#   이 파일에 CI 배포 키의 공개키(§1에서 새로 발급한 ~/.ssh/ssa_deploy.pub) 한 줄을 넣는다

# 배포 디렉터리. Ubuntu라 §1의 SELinux 라벨 작업은 없다
sudo mkdir -p /opt/ssa/secrets /opt/ssa/nginx && sudo chown -R ssa-deploy:ssa-deploy /opt/ssa
sudo chmod 700 /opt/ssa/secrets
```

`.env`와 FCM 키는 §1과 같다(`.env` 600, FCM 키 644, 소유자 `ssa-deploy`). `.env`의 `GHCR_OWNER`는 `simple-schedule-app`이다.

**공개 접점 (Cloudflare Tunnel).** [CLOUDFLARE-TUNNEL.md](CLOUDFLARE-TUNNEL.md) 절차를 그대로 따른다. 터널 compose는 앱 스택이 만든 네트워크(`ssa_app_network`)에 올라타므로, 첫 배포가 끝난 뒤에 띄운다.

**첫 배포 뒤 확인**

- **노출 확인.** 서버 안에서는 응답이 오고, 바깥에서는 막혀야 한다. 첫 배포 전에는 80에서 듣는 것이 없어 바깥 결과가 언제나 `000`이라 아무것도 증명하지 못한다. 그래서 배포 뒤에 둘을 함께 본다.
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' "http://localhost/"        # 서버 안에서. 기대: 000이 아닌 코드(nginx가 듣고 있다는 양성 대조)
  curl -m 5 -s -o /dev/null -w '%{http_code}\n' "http://<공인 IP>/"   # 서버가 아닌 곳에서. 기대: 000(보안 목록이 막음)
  ```
  바깥에서 응답 코드가 나오면 보안 목록에 80이 열린 것이다. 바로 닫는다.
- Deploy 잡의 스모크(401)가 통과했는지 본다.
- **DB 왕복 지연.** 서버는 도쿄, DB(Supabase)는 서울(`ap-northeast-2`, `.env.example`의 풀러 호스트)이라 쿼리마다 리전 간 왕복이 더해진다. 로그인으로 토큰을 받아 자주 쓰는 조회 API(예: `GET /me/schedules`)의 응답 시간을 재 두고(`curl -s -o /dev/null -w '%{time_total}\n' -H "Authorization: Bearer <토큰>" "http://localhost/me/schedules"`), 느리면 그 API의 쿼리 수부터 본다.
- arm64에서 네이티브 라이브러리가 빠지지 않았는지 본다. 아래가 비어 있어야 하고, 무언가 나오면 읽어 본다.
  ```bash
  docker logs ssa-app 2>&1 | grep -i -e UnsatisfiedLinkError -e 'native'
  docker logs ssa-geekchat 2>&1 | grep -i UnsatisfiedLinkError
  ```
- 유휴 회수에 대비해 메모리를 잰다. Oracle은 7일 동안 CPU(95퍼센타일), 네트워크, 메모리 사용률이 모두 20% 미만이면 유휴로 보고 회수할 수 있다. 우리 트래픽이면 메모리 하나가 이 조건을 막는다.
  ```bash
  free -m                                                      # used / total을 기록한다
  docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'
  ```
  콘솔의 인스턴스 Metrics에서 Memory Utilization을 첫 주 동안 본다. `free -m`의 used가 total의 25% 미만이거나, 콘솔 Memory Utilization이 사흘 동안 25% 미만이면 JVM 힙 고정(계획의 Task 4)을 한다. 회수 판정은 Oracle 지표로 하므로 뒤의 조건을 빼먹지 않는다.

## 2. GitHub 설정 (1회)

1. **Environments**: `prod` 환경 생성(required reviewers 권장).
2. **Secrets** 등록 — `.github/workflows/deploy.yml` 상단 주석의 목록 참고.
   - WireGuard 피어는 **CI 전용으로 신규 발급** (개인 노트북 피어 재사용 금지, IP 충돌 + 키 공유 문제)
   - `WG_ALLOWED_IPS`는 반드시 서버 서브넷만 (예: `10.64.212.0/24`). `0.0.0.0/0` 금지.
   - `DEPLOY_SSH_KNOWN_HOSTS`: VPN을 켜고 로컬에서 `ssh-keyscan 10.64.212.40` 결과 등록 (호스트키 고정). 지문은 서버 콘솔에서 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`로 본 값과 대조한다. 노트북 `~/.ssh/known_hosts`에 남은 옛 `10.64.212.20` 줄은 `ssh-keygen -R 10.64.212.20`으로 지운다.
   - 2026-10 서버 교체로 바꿀 시크릿: `DEPLOY_HOST`(`10.64.212.40`), `DEPLOY_USER`, `DEPLOY_SSH_KEY`(§1에서 새로 발급한 배포 키의 개인키), `DEPLOY_SSH_KNOWN_HOSTS`. `WG_*`는 그대로다.
   - **Oracle(§1B)로 배포할 때:** 시크릿은 `DEPLOY_HOST`=공인 IP, `DEPLOY_USER`=`ssa-deploy`, `DEPLOY_SSH_KEY`(§1에서 새로 발급한 배포 키의 개인키), `DEPLOY_SSH_KNOWN_HOSTS`=`ssh-keyscan <공인 IP>` 결과다. 지문은 오너가 `ubuntu`로 붙어 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`로 본 값과 대조한다. 그리고 `prod` 환경 변수(시크릿이 아니다) `DEPLOY_NETWORK`=`direct`를 넣는다. 이 값이 있으면 CD가 WireGuard 단계를 건너뛰고 `WG_*` 시크릿을 쓰지 않는다.
3. **첫 배포 전 접속 점검** (노트북에서 한 번. VM이면 VPN을 켜고, Oracle이면 VPN 없이). CI와 같은 키, 계정, 호스트 키 고정으로 붙어 본다.
   ```bash
   ssh -i ~/.ssh/ssa_deploy -o BatchMode=yes -o StrictHostKeyChecking=yes \
       -o UserKnownHostsFile=<DEPLOY_SSH_KNOWN_HOSTS에 넣은 내용을 그대로 저장한 파일> \
       <배포 계정>@<서버 주소> \
       'docker ps >/dev/null && ls -ldZ /opt/ssa/nginx /opt/ssa/secrets && ls -lZ /opt/ssa/.env /opt/ssa/secrets/fcm-service-account.json'
   ```
   - 이 점검이 필요한 이유: CI의 접속 확인 단계(`deploy.yml`의 `Verify SSH connectivity`)는 SSH가 왜 실패했는지 가려 주지 못한다. 키, 계정, `authorized_keys`, 호스트 키 고정, (VM이면) 피어 키 충돌이 모두 같은 단계의 실패로 나온다.
   - 키 인증이 거부되고 관리자가 `authorized_keys`를 손으로 만들었다면(VM), SELinux 라벨도 확인한다(`sudo restorecon -Rv ~<배포 계정>/.ssh`).
   - VM이면 점검이 끝난 뒤 VPN을 끈다. CI 러너가 같은 피어 키를 쓴다(§5의 1).

## 3. 배포

- 수동: GitHub Actions → `Deploy` 워크플로 → `Run workflow` (환경 선택)
- 자동화하려면 `deploy.yml`의 `on:`에 `push: tags: [ 'v*' ]` 추가

## 4. 과거 "배포 차단 요소" — 현황 (2026-07-30 재확인)

| 항목 | 상태 |
|---|---|
| FcmConfig가 외부 마운트 키를 못 읽음 | ✅ 해소 — `ResourceLoader`로 교체됨(`FcmConfig.java:29,72`) |
| `application-prod.yml` 미작성 | ✅ 해소 — `app/src/main/resources/application-prod.yml` |
| actuator 미포함 | ✅ 해소 — course/notification `build.gradle:12` |
| `ddl-auto` | ⚠️ **미해소** — 운영은 여전히 `update`. Flyway는 도입됐고 정착 후 `validate`로 전환(prod yml에 TODO) |

## 5. develop → main 릴리스 런북 (⚠️ 다음 릴리스는 대점프)

`main` push는 **즉시 자동 배포**다. 현재 `main`은 develop보다 50커밋 이상 뒤처져 있고, 한 번의 병합으로 아래가 **동시에** 나간다:

- 프로세스 토폴로지 교체: 3-프로세스(`ssa-course` + `ssa-notification` + `ssa-geekchat`) → **2-프로세스**(`ssa-app` + `ssa-geekchat`). CD가 `up -d --remove-orphans`로 올리므로 compose에서 사라진 `ssa-course`/`ssa-notification` 컨테이너는 **자동 제거된다**(`deploy.yml:172`) — 수동 정리 불필요.
- Spring Boot 3.4.3 → **4.1** (Hibernate 7 / Jackson 3)
- ADR-0004 도메인 순수화, ADR-0005 인증 기본 차단
- **Flyway 최초 실행** — V1~V3가 운영에서 한 번도 돈 적이 없다

### 병합 전 (순서대로)

1. **노트북 WireGuard 터널 Deactivate.** CI 러너가 같은 피어 키를 쓰므로 켜둔 채 배포하면 터널 개설 단계에서 실패한다. (근본 해결: CI 전용 피어 신규 발급 — §0)
2. **Supabase 스키마 생성** (SQL Editor에서 1회):
   ```sql
   CREATE SCHEMA IF NOT EXISTS ssa;
   CREATE SCHEMA IF NOT EXISTS geekchat;
   ```
3. **서버 `.env` 갱신** — CD는 `docker-compose.prod.yml`만 `scp`하고 **`.env`는 복사하지 않는다.** `/opt/ssa/.env`에 직접 반영:
   - `SSA_DB_URL` / `SSA_DB_USER` / `SSA_DB_PASSWORD` (세션 풀러 호스트 + `currentSchema=ssa`)
   - `GEEKCHAT_FRONTEND_ORIGIN_PATTERNS` (CORS/WS 다중 origin — 없으면 채팅이 403)
   - `CORS_ALLOWED_ORIGINS` (운영 프론트 도메인)
4. `.env.example`과 실제 `.env`의 키 목록을 대조해 누락이 없는지 확인한다.

### 병합 후 검증

```bash
# 1) 새 토폴로지가 떴는지 (ssa-app + ssa-geekchat + nginx/redis/kafka)
docker compose -f /opt/ssa/docker-compose.prod.yml ps

# 2) Flyway가 실제로 돌았는지 — 이 릴리스의 최대 미검증 지점
docker logs ssa-app 2>&1 | grep -i flyway
#    Supabase SQL Editor: SELECT version, success FROM ssa.flyway_schema_history ORDER BY installed_rank;
#    기대: V1(baseline) + V2 + V3 전부 success

# 3) 인증 게이트 (ADR-0005) — 무토큰은 401이어야 한다
curl -s -o /dev/null -w '%{http_code}\n' https://<host>/lectures/search?keyword=test   # 401 기대
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://<host>/login                   # 400/401 (게이트 통과 = 공개)

# 4) 구 컨테이너가 정말 사라졌는지 확인 (--remove-orphans가 처리하지만 검증한다)
docker ps -a --filter name=ssa-course --filter name=ssa-notification --format '{{.Names}}'
#    빈 출력이 정상. 남아 있으면 포트/네트워크를 물고 있을 수 있으므로 docker rm -f 로 정리

# 5) nginx가 실제로 백엔드에 닿는지 (아래 ⚠️ nginx 함정 참고)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/lectures/search?keyword=a
#    401 = 정상(게이트 도달). 502/504면 nginx가 옛 upstream IP를 물고 있는 것 → docker restart ssa-nginx
```

> ### ⚠️ nginx는 배포로 갱신되지 않는다 (2026-08-02 릴리스에서 두 번 물림)
>
> `docker compose up -d`는 **이미지와 환경이 그대로면 nginx 컨테이너를 재생성하지 않는다.** 설정은 볼륨 마운트라 파일만 새것으로 바뀌고, 프로세스는 옛 설정을 메모리에 들고 있다. 여기서 두 가지가 각각 터졌다:
>
> | 증상 | 원인 |
> |---|---|
> | **전면 502/504** | nginx는 `upstream`의 호스트명을 **기동 시 1회만** 해석한다(`resolver` 미지정). 토폴로지가 바뀌어 앱이 새 IP로 뜨면 사라진 옛 IP로 계속 연결을 시도한다 |
> | **특정 경로만 이상 동작** | 설정 파일은 갱신됐는데 프로세스가 재적용하지 않음. 이번엔 `location /sse-stream/`(트레일링 슬래시) 수정이 먹지 않아 새 SSE 경로가 301로 리다이렉트됐다 |
>
> `deploy.yml`에 **nginx restart + 스모크 판정**을 추가해(2026-08-02) 자동화했다. `reload`가 아니라 `restart`인 이유는 upstream DNS 재해석까지 필요하기 때문이다. 수동 배포 시에는 직접:
> ```bash
> docker exec ssa-nginx nginx -t && docker restart ssa-nginx
> ```
>
> 교훈: **nginx conf를 고치는 변경은 배포만으로 반영됐다고 가정하지 말고 반드시 실제 요청으로 확인**할 것. 설정 파일을 `docker exec ... grep`으로 보면 새 내용이 보이므로 파일 확인만으로는 구별되지 않는다.

3. **서버 `.env`는 CD가 건드리지 않는다** — `scp`는 `docker-compose.prod.yml`과 `nginx/`만 복사한다(`deploy.yml:163`). 위 §병합 전 3의 값들이 반영돼 있지 않으면 컨테이너가 부팅 실패하거나(DB URL 없음) 조용히 오작동한다(CORS 패턴 없음 → 채팅 403).

> **Flyway가 이 릴리스의 최대 리스크다.** V2·V3는 실 PostgreSQL 16으로 두 pre-state(빈 스키마 / 오타 컬럼 존재)를 검증했지만, **Supabase 세션 풀러의 `search_path`는 로컬에서 재현할 수 없다.** prod Flyway 설정에 `schemas`가 없어 `current_schema()`가 JDBC URL의 `currentSchema=ssa`에 의존하는데, 풀러가 다르게 주면 V2·V3는 **성공을 보고하면서 아무것도 하지 않는다.** 그래서 위 2)의 `flyway_schema_history` 확인이 형식적 절차가 아니다 — 적용 여부를 실제 스키마로 교차 확인할 것:
> ```sql
> SELECT column_name FROM information_schema.columns
>  WHERE table_schema='ssa' AND table_name='consultation_attendee';
> -- consultation_id 가 있어야 한다 (consultaition_id 가 남아 있으면 V2가 no-op으로 지나간 것)
> ```

### 롤백

`docker-compose.prod.yml`의 `IMAGE_TAG`를 이전 성공 SHA로 되돌려 `up -d`. 단 **Flyway 마이그레이션은 자동으로 되돌아가지 않는다** — V2는 컬럼 rename이므로 구버전 앱(오타 컬럼 기대)과 맞지 않는다. 구버전으로 되돌리려면 rename을 수동 역적용해야 한다:
```sql
ALTER TABLE ssa.consultation_attendee RENAME COLUMN consultation_id TO consultaition_id;
```

**arm64 서버(Oracle)에서 롤백할 때**는 되돌릴 SHA에 arm64 이미지가 있는지 먼저 본다. 다중 아키텍처 빌드가 들어간 뒤의 SHA만 해당한다. 그 전 SHA는 amd64 전용이라 컨테이너가 `exec format error`로 뜨지 않고, `restart: always` 아래에서 재시작만 반복한다. 그 SHA의 Deploy 실행 로그에 `Check image platforms` 단계가 있고 통과했는지로 가린다(그 전 실행에는 이 단계가 없다). Oracle 첫 배포에는 되돌릴 SHA가 없다. 조직 이전(2026-10-23) 전 SHA의 이미지는 `ghcr.io/jungsehui`에 있으므로, VM에서 그 SHA로 되돌리려면 `.env`의 `GHCR_OWNER`도 잠시 바꿔야 한다.

## 6. 운영 점검

```bash
docker compose -f /opt/ssa/docker-compose.prod.yml ps
docker logs -f ssa-app --tail 100          # 단일 JVM (구 3-프로세스에서는 ssa-course)
curl -s 'localhost/lectures/search?keyword=a' -o /dev/null -w '%{http_code}\n'   # nginx 경유 — 무토큰이면 401
```

메모리 예산(2-프로세스 기준, 옛 서버 8GB에서 잡은 값. 새 서버는 24 GiB지만 서버 관리자의 다른 작업과 공유한다): app 2G + geekchat 1G + Kafka 1G + ZK 0.5G + Redis 0.4G + nginx 0.13G ≈ 5G. DB는 외부 관리형(Supabase)이라 서버 메모리를 쓰지 않는다. 구 3-프로세스 + MySQL×2 구성 대비 여유가 늘었다.
