"""本地发布工具的真实文件/摘要/原子写入测试；Android工具输出是合成外部边界。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("update_publish", ROOT / "scripts/codextop/create-update-manifest.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AppUpdatePublishTest(unittest.TestCase):
    """不接触真实签名私钥、设备或发布服务器。"""

    def setUp(self):
        """每项只拥有临时APK与清单。"""
        self.temp = tempfile.TemporaryDirectory(prefix="codextop-update-publish-")
        self.root = Path(self.temp.name)
        self.apk = self.root / "test.apk"
        self.apk.write_bytes(b"synthetic APK bytes")
        self.signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.badging = "package: name='com.butang.codextop.nativepreview.beta' versionCode='71060' versionName='1.0.1'\nsdkVersion:'21'\nnative-code: 'arm64-v8a'\n"
        self.manifest = "E: application\n"

    def tearDown(self):
        """清理本项全部合成产物，不保存大型复制件。"""
        self.temp.cleanup()

    def tool(self, argv):
        """替代apksigner/aapt2进程输出，工具内部文件hash与生成逻辑是真实实现。"""
        if argv[0] == "apksigner":
            return self.signature
        return self.badging if "badging" in argv else self.manifest

    def inspect(self, installed=71059):
        """用同一正式函数检查当前合成外部事实。"""
        with patch.object(module, "run_tool", self.tool):
            return module.inspect_apk(self.apk, "aapt2", "apksigner", "com.butang.codextop.nativepreview.beta", "a" * 64, installed)

    def test_manifest_hash_and_atomic_output(self):
        """hash来自APK真实字节，完整清单成功才替换旧文件。"""
        data = self.inspect()
        self.assertEqual(data["sha256"], module.hashlib.sha256(self.apk.read_bytes()).hexdigest())
        output = self.root / "manifest.json"
        module.write_manifest(output, data)
        self.assertEqual(json.loads(output.read_text()), data)
        self.assertEqual(list(self.root.glob(".manifest-*")), [])

    def test_aapt36_min_sdk_version(self):
        """aapt2 36真实字段名minSdkVersion与旧sdkVersion含义一致。"""
        self.badging = ("package: name='com.butang.codextop.nativepreview.beta' versionCode='71060' versionName='12.10.6'\n"
                        "minSdkVersion:'21'\ntargetSdkVersion:'36'\nnative-code: 'arm64-v8a'\n")
        data = self.inspect()
        self.assertEqual(data["minSdk"], 21)
        self.assertEqual(data["versionCode"], 71060)
        self.assertEqual(data["versionName"], "12.10.6")

    def test_aapt36_multiple_abis_still_rejected(self):
        """辅助so也会进入native-code；兼容字段名不能放宽单ARM64发布门。"""
        self.badging = (self.badging.replace("sdkVersion:", "minSdkVersion:")
                        .replace("native-code: 'arm64-v8a'", "native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'"))
        with self.assertRaisesRegex(ValueError, "ARM64"):
            self.inspect()

    def test_wrong_signer(self):
        """原证书不一致时不给出可发布清单。"""
        self.signature = self.signature.replace("a" * 64, "b" * 64)
        with self.assertRaises(ValueError):
            self.inspect()

    def test_same_or_downgrade(self):
        """相同及更低code都拒绝。"""
        for installed in (71060, 71061):
            with self.assertRaises(ValueError):
                self.inspect(installed)

    def test_wrong_package_or_abi(self):
        """不能把另一应用或未验证ABI包发布给当前手机。"""
        original = self.badging
        for value in (original.replace("nativepreview.beta", "other"), original.replace("arm64-v8a", "x86")):
            self.badging = value
            with self.assertRaises(ValueError):
                self.inspect()

    def test_testonly_rejected(self):
        """系统普通安装器不能安装testOnly，清单生成前拒绝。"""
        self.manifest = "A: android:testOnly(0x01010272)=(type 0x12)0xffffffff\n"
        with self.assertRaises(ValueError):
            self.inspect()

    def test_failed_replace_preserves_old(self):
        """原子替换失败保留原清单并清理临时文件。"""
        output = self.root / "manifest.json"
        output.write_text("old")
        with patch.object(module.os, "replace", side_effect=OSError("synthetic")):
            with self.assertRaises(OSError):
                module.write_manifest(output, self.inspect())
        self.assertEqual(output.read_text(), "old")
        self.assertEqual(list(self.root.glob(".manifest-*")), [])


if __name__ == "__main__":
    unittest.main()
