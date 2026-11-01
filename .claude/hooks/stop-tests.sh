#!/usr/bin/env bash
# 턴이 끝날 때 도는 테스트(Stop 훅). 기기 메모리를 지키며 돌리고, 결과가 사용자 눈에 보이게 남긴다.
#
# 왜 이렇게 생겼나 (2026-10-04, 오너 결정: 1분 기다리고 건너뜀)
#
# memguard로 감싼다
#   기기 전체에서 무거운 작업을 한 번에 하나만 돌리고, 여유 메모리가 모자라면 기다린다.
#   다만 Gradle(JVM)의 힙과 워커는 정해 주지 못한다. jest가 아닌 명령에는 힙 상한을 NODE_OPTIONS로
#   주는데 JVM은 이 값을 읽지 않고, 워커 수 조절은 jest 전용이다. 그래서 Gradle 워커는
#   --max-workers=2로 따로 묶는다(이 기기 기본값은 8, --info의 worker leases로 실측).
#
# 절대 경로로 부른다
#   훅은 Claude Code의 환경을 물려받는다. 그 PATH에 ~/.local/bin이 없으면 command -v로는 못 찾고,
#   보호 없이 조용히 돌게 된다.
#
# 60초 기다리고, 넘기면 건너뛴다
#   memguard는 잠금이나 메모리를 기다리다 시간을 넘기면 명령을 실행하지 않고 75로 끝난다.
#   실패가 아니라 건너뜀이므로 systemMessage로 알리고 0으로 끝낸다.
#
# 종료 코드와 출력 (공식 문서 hooks의 Exit code output)
#   0     stdout의 JSON을 읽는다. systemMessage는 사용자에게 보이고, stderr는 디버그 로그로만 간다.
#   2     Stop을 막아 Claude가 멈추지 못한다. 그래서 2는 내지 않는다.
#   그 밖 stdout에 JSON 객체가 있으면 종료 코드를 무시해 오류로 표시하지 않는다.
#         stdout이 비어 있으면 오류 알림과 함께 stderr 첫 줄만 보인다.
#   그래서 명령 출력은 파일에 모아 두고, 실패하면 stdout을 비운 채 stderr 첫 줄에 요약을 둔다.
#
# 출처: https://code.claude.com/docs/en/hooks , ~/.local/bin/memguard 24~25행(워커와 힙),
#       457~477행(75), 488~489행(NODE_OPTIONS)

set -u

MEMGUARD="${MEMGUARD_BIN:-$HOME/.local/bin/memguard}"
GRADLE=(./gradlew -q --max-workers=2 :common:test :course:test :notification:test)

# 사용자에게 보이는 안내. 0으로 끝낼 때만 부른다.
notice() {
  local s=${1//\\/\\\\}
  s=${s//\"/\\\"}
  printf '{"systemMessage": "%s"}\n' "$s"
}

# 실패 보고. stdout은 비워 둔다. 2는 1로 바꾼다.
fail() {
  local rc=$1 what
  what=$(grep -m1 -o "Execution failed for task '[^']*'" "$LOG")
  printf '[테스트 훅] 테스트가 통과하지 못했다 (exit %s%s) %s\n' "$rc" "$2" "$what" >&2
  cat "$LOG" >&2
  [ "$rc" -eq 2 ] && rc=1
  exit "$rc"
}

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 1
LOG=$(mktemp "${TMPDIR:-/tmp}/ssa-stop-tests.XXXXXX") || exit 1
trap 'rm -f "$LOG"' EXIT

if [ ! -x "$MEMGUARD" ]; then
  "${GRADLE[@]}" >"$LOG" 2>&1
  rc=$?
  [ "$rc" -eq 0 ] || fail "$rc" ", memguard 없이 워커 2개로 돌림"
  cat "$LOG" >&2
  notice "[테스트 훅] memguard가 없어($MEMGUARD) 메모리 보호 없이 워커 2개로만 제한해 돌렸다. 테스트는 통과했다"
  exit 0
fi

MEMGUARD_WAIT_SEC=60 "$MEMGUARD" -- "${GRADLE[@]}" >"$LOG" 2>&1
rc=$?
case "$rc" in
  0)
    cat "$LOG" >&2
    exit 0 ;;
  75)
    cat "$LOG" >&2
    notice "[테스트 훅] 다른 무거운 검증이 돌거나 메모리가 모자라 이번 턴의 테스트를 건너뛰었다 (1분 기다린 뒤 memguard 75)"
    exit 0 ;;
  *)
    fail "$rc" "" ;;
esac
