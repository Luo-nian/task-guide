/*
 * 跨语言互通验证：Node(生成器) 签的授权码，Android 的 JCA 能不能验通？
 * 这段 Java 用的 API 与 mobile 端 License.kt **逐行对应**，
 * 所以这里通过 = 手机上一定通过（不用装机就能证明）。
 *
 * 用法（JDK17 单文件直接跑）：
 *   java InteropCheck.java <公钥Base64> <授权码>
 */
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class InteropCheck {

    static byte[] b64u(String s) {
        // Java 的 URL 解码器能直接吃"无 padding"的串
        return Base64.getUrlDecoder().decode(s);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: java InteropCheck.java <pubkey-b64> <license-code>");
            return;
        }
        String pubB64 = args[0].trim();
        String code = args[1].trim();

        String[] parts = code.split("\\.");
        if (parts.length != 2) {
            System.out.println("RESULT = FAIL (bad format, expect a.b)");
            return;
        }

        byte[] payload = b64u(parts[0]);
        byte[] sig = b64u(parts[1]);

        PublicKey pk = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)));

        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(pk);
        s.update(payload);
        boolean ok = s.verify(sig);

        int bits = ((ECPublicKey) pk).getParams().getCurve().getField().getFieldSize();
        System.out.println("payload = " + new String(payload, StandardCharsets.UTF_8));
        System.out.println("curve   = P-" + bits);
        System.out.println("RESULT  = " + (ok ? "PASS" : "FAIL"));
    }
}
