package filesharing.main;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class SecurityManager {
    private static SSLContext sslContext;
    private static SecretKeySpec aesKey;

    public SecurityManager() {
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream("keystore.jks"), "password".toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "password".toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            aesKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public SSLServerSocket createSSLServerSocket(int port) throws java.io.IOException {
        return (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(port);
    }

    public SSLSocket createSSLSocket(String address, int port) throws java.io.IOException {
        return (SSLSocket) sslContext.getSocketFactory().createSocket(address, port);
    }

    public SSLSocket createSSLSocket() throws java.io.IOException {
        return (SSLSocket) sslContext.getSocketFactory().createSocket();
    }

    public String encryptMessage(String message) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
            byte[] encrypted = cipher.doFinal(message.getBytes());
            return java.util.Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return java.util.Base64.getEncoder().encodeToString(message.getBytes());
        }
    }

    public String decryptMessage(String encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, aesKey);
            byte[] decrypted = cipher.doFinal(java.util.Base64.getDecoder().decode(encrypted));
            return new String(decrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return new String(java.util.Base64.getDecoder().decode(encrypted));
        }
    }

    public boolean validateUUID(String uuid) {
        return uuid != null && uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}