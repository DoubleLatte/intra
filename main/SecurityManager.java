package filesharing.main;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Base64;

public class SecurityManager {
    private static final String KEYSTORE_PATH = "keystore.jks";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final String ALGORITHM = "AES";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    public SecurityManager() {
        System.setProperty("javax.net.ssl.keyStore", KEYSTORE_PATH);
        System.setProperty("javax.net.ssl.keyStorePassword", KEYSTORE_PASSWORD);
        System.setProperty("javax.net.ssl.trustStore", KEYSTORE_PATH);
        System.setProperty("javax.net.ssl.trustStorePassword", KEYSTORE_PASSWORD);
    }

    public SSLServerSocket createSSLServerSocket(int port) throws IOException {
        SSLServerSocketFactory factory = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        return (SSLServerSocket) factory.createServerSocket(port);
    }

    public SSLSocket createSSLSocket(String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        return (SSLSocket) factory.createSocket(host, port);
    }

    public String encryptMessage(String message) throws Exception {
        // Simplified; use proper encryption in production
        return Base64.getEncoder().encodeToString(message.getBytes());
    }

    public String decryptMessage(String encrypted) throws Exception {
        // Simplified; use proper decryption in production
        return new String(Base64.getDecoder().decode(encrypted));
    }

    public boolean validateUUID(String uuid) {
        try {
            UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public boolean verifySignature(File file, String signature, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    sig.update(buffer, 0, len);
                }
            }
            return sig.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public PublicKey getPublicKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(KEYSTORE_PATH)) {
            ks.load(fis, KEYSTORE_PASSWORD.toCharArray());
        }
        return ks.getCertificate("main_dev_alias").getPublicKey();
    }

    public void testInSandbox(File jarFile) throws IOException {
        // Basic sandbox implementation using SecurityManager
        System.setSecurityManager(new SecurityManager());
        ProcessBuilder pb = new ProcessBuilder("java", "-Djava.security.manager", "-jar", jarFile.getAbsolutePath());
        try {
            Process process = pb.start();
            // Monitor process for limited time (e.g., 10 seconds)
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                throw new IOException("Sandbox test timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Sandbox test failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sandbox test interrupted", e);
        } finally {
            System.setSecurityManager(null); // Reset SecurityManager
        }
    }
}