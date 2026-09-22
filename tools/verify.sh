#!/usr/bin/env bash
# 构建/测试唯一通道。
# 为什么需要它：项目目录含中文，Kotlin 守护进程与 Gradle test worker 会把中文路径转义成 u77E5... ，
# 导致 ①编译期 "plugin classpath entry points to a non-existent location" ②测试期
# ClassNotFoundException: <测试类自身>。加 -Djava.io.tmpdir 无效；ASCII junction 指向项目也无效
# （Gradle 会规范化回真实路径）。唯一可靠办法：源码单向镜像到纯 ASCII 目录构建。
# 铁律：只在真目录改代码，镜像是临时工作区，永不手改。
set -euo pipefail

REAL_WIN='E:\AI Agent\work area\知我时光笺\zhiwo-android'
REAL="/e/AI Agent/work area/知我时光笺/zhiwo-android"
MIRROR_WIN='E:\AI Agent\zhiwo-build'
MIRROR="/e/AI Agent/zhiwo-build"
GRADLE_HOME="E:/AI Agent/zhiwo-gradle-home"
TASK="${1:-testDebugUnitTest}"
STAMP="$(date +%Y-%m-%d_%H%M)"
EVIDENCE="$REAL/docs/迭代计划/2026-09-21-任务体系与记忆整理重构/证据/$STAMP-$TASK"

export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"

rc_guard() {  # robocopy 退出码 <8 才算成功；吞掉错误码会造成"镜像不完整但构建通过"的假绿
  if [ "$1" -ge 8 ]; then echo "!! robocopy $2 失败，退出码 $1" >&2; exit 1; fi
  echo "OK robocopy $2 (rc=$1)"
}

# 1) 真目录 → 镜像：同步源码（排除各构建产物与缓存）
set +e
powershell -NoProfile -Command "robocopy '$REAL_WIN' '$MIRROR_WIN' /MIR /XD app\build app\schemas .gradle .gradle-home .gradle-ascii .idea tmp dist build /XF hs_err_pid*.log replay_pid*.log /NFL /NDL /NJH /NP | Out-Null; exit \$LASTEXITCODE"
SYNC_RC=$?
set -e
if [ "$SYNC_RC" -ge 8 ]; then echo "!! robocopy 同步到镜像失败，退出码 $SYNC_RC（镜像不完整，构建结果不可信）" >&2; exit 1; fi
echo "OK 同步到镜像 (rc=$SYNC_RC)"

cd "$MIRROR"
# 2a) 关键：镜像的 app/build 被 /XD 排除、不参与 /MIR 清理 → 上一次运行的 test-results 会留下
#     "幽灵 XML"（被删掉的测试类仍计入基线）。跑之前必须清空，并用时间戳文件做新鲜度判据
#     （Gradle 写进 XML 的 timestamp 是 UTC，拿本地日期比会把凌晨的真绿误判成 stale）。
rm -rf app/build/test-results app/build/reports/tests
mkdir -p app/schemas && cp -rf "$REAL/app/schemas/." "$MIRROR/app/schemas/" 2>/dev/null || true
touch app/build/.run-start 2>/dev/null || (mkdir -p app/build && touch app/build/.run-start)

# 2b) 构建 / 测试（--no-daemon --max-workers=1：本机内存偏紧，daemon 曾被 OOM 干掉）
./gradlew --no-daemon --max-workers=1 -g "$GRADLE_HOME" ":app:$TASK"

mkdir -p "$EVIDENCE"

# 3) Room schema：KSP 在源码内容未变时会 UP-TO-DATE 而不重新导出 → 缺产物就强制重跑一次
if [ -z "$(find app/schemas -name '*.json' 2>/dev/null | head -1)" ]; then
  echo "-- 镜像内无 schema 产物，强制重跑 KSP --"
  ./gradlew --no-daemon --max-workers=1 -g "$GRADLE_HOME" :app:kspDebugKotlin --rerun-tasks -q
fi
if [ -d "app/schemas" ]; then
  powershell -NoProfile -Command "robocopy '$MIRROR_WIN\app\schemas' '$REAL_WIN\app\schemas' /E /XF *.tmp /NFL /NDL /NJH /NP | Out-Null; exit 0" >/dev/null
  # 已提交的 schema 内容被改动 = SCHEMA DRIFT（12.json 永远不该变），只允许出现新文件
  if ! diff -rq "$MIRROR/app/schemas" "$REAL/app/schemas" >/dev/null 2>&1; then
    echo "!! SCHEMA DRIFT：Room 生成的 schema 与已提交 schema 不一致（只允许出现新文件）" >&2
    diff -rq "$MIRROR/app/schemas" "$REAL/app/schemas" | tee "$EVIDENCE/schema_drift.txt" || true
    exit 1
  fi
  echo "OK schemas 一致"
fi

# 4) APK 回传
if [ "$TASK" != "testDebugUnitTest" ]; then
  mkdir -p "$REAL/dist"
  cp -f app/build/outputs/apk/*/*.apk "$REAL/dist/" 2>/dev/null || true
  ls -l app/build/outputs/apk/*/*.apk 2>/dev/null | tee "$EVIDENCE/apk.txt" || true
fi

# 5) 测试证据：写进 git 跟踪的 docs/quality/（tmp/ 与 **/build/ 都在 .gitignore 里，
#    落在那些地方的数字等于没有证据）
if [ "$TASK" = "testDebugUnitTest" ]; then
  GATE_DIR="$REAL/docs/quality"
  mkdir -p "$GATE_DIR"
  cp -r app/build/test-results/testDebugUnitTest "$EVIDENCE/" 2>/dev/null || true
  {
    echo "task=$TASK  本地运行时间=$(date +%F' '%H:%M)"
    echo "--- 新鲜度：不是本次运行产生的 XML 数量（必须为 0）---"
    find app/build/test-results/testDebugUnitTest -name 'TEST-*.xml' ! -newer app/build/.run-start | wc -l
    echo "--- XML 内 timestamp（Gradle 写的是 UTC，别拿本地日期判）---"
    grep -ho 'timestamp="[^"]*"' app/build/test-results/testDebugUnitTest/TEST-*.xml | sort -u
    echo "--- 汇总 ---"
    grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
      app/build/test-results/testDebugUnitTest/TEST-*.xml |
      awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END{printf "tests=%d skipped=%d failures=%d errors=%d\n",t,s,f,e}'
    echo "--- 类级用例数（与上一里程碑 diff，删除类必须在白名单里说明）---"
    for f in app/build/test-results/testDebugUnitTest/TEST-*.xml; do
      sed -n 's/.*name="\([^"]*\)" tests="\([0-9]*\)".*/\2 \1/p' "$f"
    done | sort
  } | tee "$GATE_DIR/GATE-$TASK-$(date +%Y%m%d%H%M).txt" "$EVIDENCE/summary.txt"
  cp "$GATE_DIR/GATE-$TASK-$(date +%Y%m%d%H%M).txt" "$EVIDENCE/gate.txt" 2>/dev/null || true
  echo "证据：$GATE_DIR 与 $EVIDENCE"
fi
