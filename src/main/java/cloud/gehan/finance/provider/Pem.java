package cloud.gehan.finance.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Builds a client-certificate SSLContext straight from the PEM files Teller hands out. */
final class Pem {

    private Pem() {
    }

    static SSLContext clientContext(Path certificatePem, Path privateKeyPem) throws IOException, GeneralSecurityException {
        Collection<? extends Certificate> chain = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(certificatePem)));
        PrivateKey key = privateKey(Files.readString(privateKeyPem));

        char[] password = new char[0];
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("client", key, password, chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        if (pkcs1) {
            der = wrapPkcs1(der);
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (GeneralSecurityException notRsa) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }

    /** PKCS#1 "RSA PRIVATE KEY" inside a PKCS#8 envelope, which is what Java reads. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] rsaAlgorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octet = concat(new byte[] {0x04}, length(pkcs1.length), pkcs1);
        byte[] body = concat(version, rsaAlgorithm, octet);
        return concat(new byte[] {0x30}, length(body.length), body);
    }

    private static byte[] length(int len) {
        if (len < 0x80) {
            return new byte[] {(byte) len};
        }
        if (len < 0x100) {
            return new byte[] {(byte) 0x81, (byte) len};
        }
        if (len < 0x10000) {
            return new byte[] {(byte) 0x82, (byte) (len >> 8), (byte) len};
        }
        return new byte[] {(byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
