package pl.ignix.launcher.core;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Same public key as the client: only files signed with the Ignix private key are accepted. */
public final class Signatures {

    private static final String PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmskC6kYG9uSOQ+r429A3bvNzvNzb1KPLYEwPOEdgIIPuWCsFCA02bXAbfrPpSmaT/qmGFKACCXOccg0uGqkF7A==";

    private Signatures() {
    }

    public static boolean verify(byte[] data, String signatureBase64) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))));
            s.update(data);
            return s.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception e) {
            return false;
        }
    }
}
