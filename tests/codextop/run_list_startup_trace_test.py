#!/usr/bin/env python3
"""离线运行启动观测真实helper专项；不构建APK、不访问设备或账号。"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time


def resolve_tool(name, java_home):
    """仅查既有JDK，不下载工具。"""
    candidate = str(Path(java_home) / "bin" / name) if java_home else shutil.which(name)
    if not candidate or not Path(candidate).is_file():
        raise SystemExit("需要现有 JDK 17+；设置 JAVA_HOME 或传 --java-home")
    return candidate


def main():
    """固定两owner及一个专项，输出命令/哈希/结果；失败立即停止且保留证据。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--javaparser-jar", default=os.environ.get("JAVAPARSER_JAR"))
    parser.add_argument("--repo", help="仅用于验证隔离源码根；默认此runner所在仓库")
    parser.add_argument("--output", help="新的空结果目录；默认保留系统临时目录")
    args = parser.parse_args()
    repo = Path(args.repo).expanduser().resolve() if args.repo else Path(__file__).resolve().parents[2]
    java, javac = resolve_tool("java", args.java_home), resolve_tool("javac", args.java_home)
    if args.javaparser_jar:
        jar = Path(args.javaparser_jar).expanduser().resolve()
        if not jar.is_file():
            raise SystemExit("指定JavaParser JAR不存在")
    else:
        cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        folder = cache / "caches/modules-2/files-2.1/com.github.javaparser/javaparser-core/3.25.4"
        jars = sorted(folder.glob("*/javaparser-core-3.25.4.jar"))
        if not jars:
            raise SystemExit("需要既有JavaParser 3.25.4；传 --javaparser-jar，不会自动下载")
        jar = jars[0].resolve()
    output = Path(args.output).expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix="list-startup-trace-"))
    if output.exists() and any(output.iterdir()):
        raise SystemExit("--output必须是新的空目录，避免覆盖证据")
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    classes.mkdir()
    test = repo / "tests/codextop/RuntimeListStartupTraceTest.java"
    tracked = [repo / "TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java",
               repo / "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java", test]
    hashes = {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest() for path in tracked}
    (output / "source-sha256.json").write_text(json.dumps(hashes, indent=2) + "\n")
    commands = []
    for label, command in [
        ("compile-test", [javac, "--release", "17", "-encoding", "UTF-8", "-cp", str(jar), "-d", str(classes), str(test)]),
        ("real-helper", [java, "-Xmx256m", "-cp", str(classes) + os.pathsep + str(jar), "com.butang.codextop.RuntimeListStartupTraceTest", str(repo)]),
    ]:
        start = time.monotonic()
        try:
            result = subprocess.run(command, cwd=repo, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
            text, code = result.stdout, result.returncode
        except subprocess.TimeoutExpired as error:
            text = "timeout after 60s\n" + (error.stdout.decode() if isinstance(error.stdout, bytes) else error.stdout or "")
            code = 124
        (output / (label + ".log")).write_text(text)
        commands.append({"label": label, "command": command, "seconds": time.monotonic() - start, "exit": code})
        (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        print(label + ": exit=" + str(code), flush=True)
        if text:
            print(text, end="" if text.endswith("\n") else "\n", flush=True)
        if code:
            raise SystemExit(code)
    after = {str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest() for path in tracked}
    if after != hashes:
        raise SystemExit("测试期间源码变动，结果不能冻结")
    print("PASS: 默认关闭/配额/发布/占位门禁；不是Android像素或耗时验收。日志：" + str(output))


if __name__ == "__main__":
    main()
