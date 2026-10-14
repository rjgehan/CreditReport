package cloud.gehan.finance.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import cloud.gehan.finance.config.AppProperties;

/**
 * AES-256-GCM for the provider credentials: a SimpleFIN access URL has your
 * bridge username and password in it, and reads every account on the bridge.
 * It should not sit in the database as plain text.
 *
 * <p>The key comes from APP_ENCRYPTION_KEY. Without it, one is generated into the
 * data directory on first start, which still keeps tokens out of a copied or
 * backed-up database file on its own, but not out of a copy of the whole volume.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(AppProperties props) {
        this.key = new SecretKeySpec(loadKey(props), "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith("v1:")) {
            throw new IllegalStateException("Unrecognised secret format");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Could not decrypt a stored credential. Has APP_ENCRYPTION_KEY changed since it was saved?", e);
        }
    }

    private static byte[] loadKey(AppProperties props) {
        if (props.encryptionKey() != null && !props.encryptionKey().isBlank()) {
            byte[] key = Base64.getDecoder().decode(props.encryptionKey().trim());
            if (key.length != 32) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY must be 32 bytes, base64 encoded (openssl rand -base64 32)");
            }
            return key;
        }
        Path file = Path.of(props.dataDir(), "secret.key");
        try {
            if (Files.exists(file)) {
                return Base64.getDecoder().decode(Files.readString(file).trim());
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(key));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows, during local development.
            }
            log.warn("APP_ENCRYPTION_KEY is not set; generated {}. Back it up with the database, "
                    + "or set APP_ENCRYPTION_KEY to its contents.", file.toAbsolutePath());
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read or create " + file, e);
        }
    }
}
