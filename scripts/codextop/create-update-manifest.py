#!/usr/bin/env python3
"""本地验签并生成静态更新清单；不上传、不修改APK、不读取签名私钥。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit

MAX_APK_BYTES = 256 * 1024 * 1024


def run_tool(arguments):
    """只执行调用者指定的Android只读工具，失败即停止生成发布清单。"""
    result = subprocess.run(arguments, check=False, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise ValueError("Android校验工具失败")
    return result.stdout


def inspect_apk(apk, aapt2, apksigner, expected_package, expected_signer, installed_code):
    """核对真实APK签名、身份和安装条件，不相信文件名声明的版本。"""
    size = apk.stat().st_size
    if size <= 0 or size > MAX_APK_BYTES:
        raise ValueError("安装包超出大小范围")
    signature = run_tool([apksigner, "verify", "--verbose", "--print-certs", str(apk)])
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    if len(signers) != 1 or signers[0].lower() != expected_signer.lower():
        raise ValueError("安装包签名与原安装证书不一致")
    badging = run_tool([aapt2, "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    # aapt2 36将旧sdkVersion字段名写为minSdkVersion，二者都来自真实APK最低SDK。
    sdk = re.search(r"^(?:sdkVersion|minSdkVersion):'([0-9]+)'", badging, re.M)
    abis = re.search(r"^native-code: (.+)$", badging, re.M)
    if not package or package[1] != expected_package or not sdk or not abis:
        raise ValueError("安装包名、SDK或ABI不匹配")
    code = int(package[2])
    if not installed_code < code <= 2100000000:
        raise ValueError("versionCode必须高于已发布/安装版本")
    if re.findall(r"'([^']+)'", abis[1]) != ["arm64-v8a"]:
        raise ValueError("本发布契约仅接受已验证的ARM64单APK")
    manifest = run_tool([aapt2, "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"])
    if re.search(r"android:testOnly[^\n]*(?:0xffffffff|\btrue\b)", manifest):
        raise ValueError("testOnly包不能交给系统普通安装入口")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(16 * 1024), b""):
            digest.update(chunk)
    return {"schema": 1, "packageName": expected_package, "versionCode": code,
            "versionName": package[3], "minSdk": int(sdk[1]), "abi": "arm64-v8a",
            "sizeBytes": size, "sha256": digest.hexdigest()}


def write_manifest(output, data):
    """清单仅在完整写入并fsync后原子替换；发布上传仍由主任务执行。"""
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=".manifest-", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(data, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n"); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, output)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def main():
    """从明确的发布URL和已验签APK生成清单，不发现或猜测任何线上地址。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--expected-package", default="com.butang.codextop.nativepreview.beta")
    parser.add_argument("--expected-signer-sha256", required=True)
    parser.add_argument("--installed-version-code", type=int, required=True)
    parser.add_argument("--base-url", required=True, help="已确定发布目录，必须HTTPS且以/结尾")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if (url.scheme != "https" or not url.hostname or url.username or url.password or url.query
            or url.fragment or not url.path.endswith("/") or not re.fullmatch(r"/[A-Za-z0-9/_.-]+/", url.path)
            or any(part in (".", "..") for part in url.path.split("/"))):
        parser.error("发布目录必须是明确的HTTPS地址，不允许查询/片段/点路径")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_signer_sha256):
        parser.error("原签名SHA256无效")
    try:
        data = inspect_apk(args.apk, args.aapt2, args.apksigner, args.expected_package,
                           args.expected_signer_sha256, args.installed_version_code)
        filename = f"{data['versionCode']}-{data['sha256']}.apk"
        data["apkUrl"] = args.base_url + filename
        # 字段顺序与客户端落盘一致，便于发布回核，不依赖此顺序解析。
        data = {key: data[key] for key in ("schema", "packageName", "versionCode", "versionName", "minSdk", "abi", "apkUrl", "sizeBytes", "sha256")}
        write_manifest(args.output, data)
        print(json.dumps({"manifestGenerated": True, "apkFilename": filename, "versionCode": data["versionCode"],
                          "sha256": data["sha256"], "sizeBytes": data["sizeBytes"], "uploaded": False}, ensure_ascii=False))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, "更新清单未生成：" + str(error) + "\n")


if __name__ == "__main__":
    main()
