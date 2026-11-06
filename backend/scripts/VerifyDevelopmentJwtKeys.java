import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Base64;

/** JDK-only local validation. Never generates, writes or prints keys. */
class VerifyDevelopmentJwtKeys {
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException();
            String privatePem=Files.readString(Path.of(args[0])),publicPem=Files.readString(Path.of(args[1]));
            if(!privatePem.contains("-----BEGIN PRIVATE KEY-----") || !publicPem.contains("-----BEGIN PUBLIC KEY-----"))
                throw new IllegalArgumentException();
            var factory=KeyFactory.getInstance("RSA");
            var privateKey=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(decode(privatePem,"PRIVATE KEY")));
            var publicKey=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(decode(publicPem,"PUBLIC KEY")));
            if(privateKey.getModulus().bitLength()<2048 || !privateKey.getModulus().equals(publicKey.getModulus())
                    || !privateKey.getPublicExponent().equals(publicKey.getPublicExponent()))throw new IllegalArgumentException();
            byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
            var signature=Signature.getInstance("SHA256withRSA");signature.initSign(privateKey);signature.update(challenge);
            byte[] signed=signature.sign();signature.initVerify(publicKey);signature.update(challenge);
            if(!signature.verify(signed))throw new IllegalArgumentException();
            System.out.println("Existing "+publicKey.getModulus().bitLength()+"-bit RSA pair verified: PKCS#8 private, X.509 public, RS256 sign/verify PASS. No keys modified.");
        } catch(Exception failure) {
            System.err.println("Development JWT key verification failed. Check external PKCS#8/X.509 RSA files; no keys were modified or displayed.");
            System.exit(1);
        }
    }
    private static byte[] decode(String pem,String label){
        return Base64.getMimeDecoder().decode(pem.replace("-----BEGIN "+label+"-----","").replace("-----END "+label+"-----",""));
    }
}
