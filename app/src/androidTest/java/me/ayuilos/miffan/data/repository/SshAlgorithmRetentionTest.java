package me.ayuilos.miffan.data.repository;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** No static references to algorithm classes: JSch resolves these names at runtime. */
public class SshAlgorithmRetentionTest {
    @Test public void algorithmFactoriesCanLoadFromInstalledApplication() throws Exception {
        ClassLoader loader = InstrumentationRegistry.getInstrumentation().getTargetContext().getClassLoader();
        for (String name : new String[] {
            "com.jcraft.jsch.DH25519", "com.jcraft.jsch.UserAuthPublicKey",
            "com.jcraft.jsch.UserAuthPassword", "com.jcraft.jsch.bc.KeyPairGenEdDSA",
            "com.jcraft.jsch.bc.SignatureEd25519", "com.jcraft.jsch.jce.KeyPairGenRSA",
            "com.jcraft.jsch.jce.KeyPairGenECDSA", "com.jcraft.jsch.jce.SignatureRSA",
            "com.jcraft.jsch.jce.SHA256", "com.jcraft.jsch.jce.AES256CTR",
            "com.jcraft.jsch.jbcrypt.JBCrypt"
        }) {
            java.lang.reflect.Constructor<?> constructor = Class.forName(name, true, loader).getDeclaredConstructor();
            constructor.setAccessible(true); // Some factories are package-private within JSch.
            constructor.newInstance();
        }
    }
}
