package me.ayuilos.miffan.data.repository;

import androidx.test.platform.app.InstrumentationRegistry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Exercises installed Release code without linking against Kotlin APIs inlined by R8. */
public class SshKeyReleaseCompatibilityTest {
    private String asset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("ssh-fixtures/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        }
    }

    @Test public void importsUnencryptedDebugBackupWithSamePublicKey() throws Exception {
        // R8 can make this method static. Reflection tests the actual installed implementation
        // without changing production keep rules just to preserve a test-only call site.
        ClassLoader loader = InstrumentationRegistry.getInstrumentation().getTargetContext().getClassLoader();
        Class<?> codec = Class.forName("me.rerere.workspace.SshKeyCodec", true, loader);
        Method importer = codec.getDeclaredMethod("importPrivateKey", String.class, String.class);
        importer.setAccessible(true);
        Object receiver = Modifier.isStatic(importer.getModifiers()) ? null : codec.getField("INSTANCE").get(null);
        Object material = importer.invoke(receiver, asset("debug-ed25519.key"), null);
        assertEquals(asset("debug-ed25519.pub").trim(), material.getClass().getMethod("getPublicKey").invoke(material));
    }

    @Test public void installedSshLibraryGeneratesAndRoundTripsEncryptedAndPlainKeys() throws Exception {
        KeyPair original = KeyPair.genKeyPair(new JSch(), KeyPair.ED25519);
        try {
            for (String passphrase : new String[] {null, "synthetic-test-口令"}) {
                byte[] password = passphrase == null ? null : passphrase.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                original.writeOpenSSHv1PrivateKey(output, password);
                KeyPair restored = KeyPair.load(new JSch(), output.toByteArray(), null);
                try {
                    if (restored.isEncrypted()) assertTrue(restored.decrypt(password));
                    org.junit.Assert.assertArrayEquals(original.getPublicKeyBlob(), restored.getPublicKeyBlob());
                    byte[] challenge = "synthetic-signature-test".getBytes(StandardCharsets.UTF_8);
                    com.jcraft.jsch.Signature verifier = restored.getVerifier();
                    verifier.update(challenge);
                    assertTrue(verifier.verify(restored.getSignature(challenge)));
                } finally { restored.dispose(); }
            }
        } finally { original.dispose(); }
    }
}
