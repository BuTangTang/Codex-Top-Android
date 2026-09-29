import com.butang.codextop.PasswordKeys;
import java.util.Arrays;

/** 可独立运行的跨语言协议向量，期望值由 Python hashlib/hmac 生成。 */
public final class PasswordKeysTest {
    /** 覆盖 ASCII、中文与原始空白、参数拒绝以及关闭后的密钥擦除。 */
    public static void main(String[] args) throws Exception {
        byte[] salt = new byte[32];
        byte[] credential = new byte[32];
        for (int i = 0; i < 32; i++) { salt[i] = (byte) i; credential[i] = (byte) (i + 32); }
        verify("codextop-fixture-only", salt, credential,
                "f8d88bd1446185d33eb6b42a00bd1382921d008e5186cae31c7b1e59fd543167",
                "feb08966976ca7d5daafe804cf1cba1b5c27407006525d94fb2f3d2086458264");
        verify(" 中文密码 ", salt, credential,
                "add92133eb5004c2f0b6e34b825f273586544d9e906dfc39f9225871d33e1537",
                "77523ba5cf1e86f7c29fbfcb7f9c76962857366e31d2a29b4a308c5a990ade74");
        try {
            PasswordKeys.derive("fixture", salt, credential, 1);
            throw new AssertionError("接受了错误的协议参数");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PasswordKeys: 协议向量、参数和擦除检查通过");
    }

    /** 检查独立期望值并确认关闭后不保留派生结果。 */
    private static void verify(String password, byte[] salt, byte[] credential,
                               String login, String wrapping) throws Exception {
        PasswordKeys keys = PasswordKeys.derive(password, salt, credential, 220000);
        try {
            if (!hex(keys.loginSecret).equals(login) || !hex(keys.wrappingKey).equals(wrapping)) {
                throw new AssertionError("派生结果与跨语言向量不一致");
            }
        } finally { keys.close(); }
        if (!Arrays.equals(keys.loginSecret, new byte[32])
                || !Arrays.equals(keys.wrappingKey, new byte[32])) throw new AssertionError("密钥未擦除");
    }

    /** 将合成测试结果转为十六进制用于断言，不输出真实凭据。 */
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
