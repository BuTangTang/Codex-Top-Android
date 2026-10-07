#!/usr/bin/env python3
"""只运行合成历史缓存/Runtime回归，不构建APK、不访问账号或设备。"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time

OWNERS = [
    "DesktopAttachment", "TranscriptText", "TranscriptWindow", "TranscriptStore",
    "IndexedTranscriptStore", "TranscriptBodyStore", "TranscriptCacheBudget",
    "TranscriptPersistenceToken", "OutboxStore", "TranscriptTailRecovery",
]
TESTS = [
    "TranscriptWindowTest", "TranscriptSegmentsTest", "TranscriptLazyWindowTest",
    "TranscriptBodyStoreTest", "IndexedTranscriptStoreTest", "TranscriptPersistenceTokenTest",
    "TranscriptCacheBudgetTest", "TranscriptStoreMigrationTest", "TranscriptStoreTest",
    "TranscriptStoreStreamingReadTest", "TranscriptStoreStreamingTest",
    "RuntimeLatestSegmentTest", "RuntimeDialogPreviewTest", "RuntimeLazyHistoryTest",
    "RuntimeFacadeIntegrationTest",
]
ORACLE_SHA256 = {
    "TranscriptStore.java": "552ded22495b0a45468319920ef6a636d6f855e1a50e77f891c53e21c0dc4164",
    "TranscriptWindow.java": "6c189d5b57fdaca79f446bea5f22e6c366966f70aa2432328f46b35d359f9e80",
}


def resolve_tool(name, java_home):
    """只查调用者指定或现有PATH中的JDK，不下载运行时。"""
    candidate = str(Path(java_home) / "bin" / name) if java_home else shutil.which(name)
    if not candidate or not Path(candidate).is_file():
        raise SystemExit("需要现有 JDK 17+；设置 JAVA_HOME 或传 --java-home")
    return candidate


def resolve_jar(explicit, group, artifact, version, flag):
    """复用既有依赖，离线测试不隐式拉取JAR。"""
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if path.is_file():
            return path
        raise SystemExit("指定的 " + artifact + " JAR 不存在")
    gradle_cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    folder = gradle_cache / "caches/modules-2/files-2.1" / group / artifact / version
    matches = sorted(folder.glob("*/" + artifact + "-" + version + ".jar"))
    if not matches:
        raise SystemExit("需要既有 " + artifact + " " + version + " JAR；传 " + flag)
    return matches[0].resolve()


def main():
    """固定测试集合及旧真实oracle，命令、退出码和哈希仅写临时结果目录。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--gson-jar", default=os.environ.get("GSON_JAR"))
    parser.add_argument("--javaparser-jar", default=os.environ.get("JAVAPARSER_JAR"))
    parser.add_argument("--output", help="新的空结果目录；默认在系统临时目录保留日志")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    tests = repo / "tests/codextop"
    sources = repo / "TMessagesProj/src/main/java/com/butang/codextop"
    java, javac = resolve_tool("java", args.java_home), resolve_tool("javac", args.java_home)
    gson = resolve_jar(args.gson_jar, "com.google.code.gson", "gson", "2.11.0", "--gson-jar")
    javaparser = resolve_jar(args.javaparser_jar, "com.github.javaparser", "javaparser-core", "3.25.4", "--javaparser-jar")
    oracle = tests / "fixtures/transcript-v2"
    for name, expected in ORACLE_SHA256.items():
        if hashlib.sha256((oracle / name).read_bytes()).hexdigest() != expected:
            raise SystemExit("冻结95 oracle内容已改变：" + name)
    if args.output:
        output = Path(args.output).expanduser().resolve()
        if output.exists() and any(output.iterdir()):
            raise SystemExit("--output 必须是新的空目录，避免覆盖已有证据")
        output.mkdir(parents=True, exist_ok=True)
    else:
        output = Path(tempfile.mkdtemp(prefix="transcript-history-tests-")).resolve()
    classes = output / "classes"
    classes.mkdir()
    owner_classes = output / "owner-classes"
    owner_classes.mkdir()
    data = output / "synthetic"
    data.mkdir()
    tracked = [sources / (name + ".java") for name in OWNERS]
    tracked += [sources / "CodexRuntime.java"]
    tracked += [tests / (name + ".java") for name in TESTS]
    tracked += [oracle / name for name in ORACLE_SHA256]
    hashes = {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest() for path in tracked}
    (output / "source-sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    commands = []

    def run(label, command):
        """逐步记录真实输出，失败立即退出，已成功/失败的日志都保留。"""
        start = time.monotonic()
        result = subprocess.run([str(value) for value in command], cwd=repo, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (output / (label + ".log")).write_text(result.stdout)
        commands.append({"label": label, "command": [str(value) for value in command],
                         "exit": result.returncode, "seconds": time.monotonic() - start})
        (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        print(label + ": exit=" + str(result.returncode), flush=True)
        if result.stdout:
            print(result.stdout, end="" if result.stdout.endswith("\n") else "\n", flush=True)
        if result.returncode:
            print("失败日志已保留：" + str(output), flush=True)
            raise SystemExit(result.returncode)

    run("compile-owners-java8", [javac, "--release", "8", "-encoding", "UTF-8", "-cp", gson,
                                "-d", owner_classes, *[sources / (name + ".java") for name in OWNERS]])
    classpath = os.pathsep.join(map(str, [classes, owner_classes, gson, javaparser]))
    runtime_classpath = os.pathsep.join(map(str, [classes, gson, javaparser]))
    run("compile-tests", [javac, "--release", "17", "-encoding", "UTF-8", "-cp", classpath,
                          "-d", classes, *[tests / (name + ".java") for name in TESTS]])
    for name in TESTS:
        extra = []
        if name in ("TranscriptBodyStoreTest", "TranscriptPersistenceTokenTest", "TranscriptCacheBudgetTest"):
            extra = [data]
        elif name in ("TranscriptStoreStreamingReadTest", "TranscriptStoreStreamingTest"):
            extra = [oracle]
        # Runtime提取器编译自己的真实owner；父classpath不能遮住临时epoch观察器。
        selected_classpath = runtime_classpath if name.startswith("Runtime") else classpath
        run(name, [java, "-Xmx256m", "-cp", selected_classpath, "com.butang.codextop." + name, *extra])
    print("PASS: 15 组合成回归；不代表 APK、Android 平台或手机验收。日志：" + str(output), flush=True)


if __name__ == "__main__":
    main()
