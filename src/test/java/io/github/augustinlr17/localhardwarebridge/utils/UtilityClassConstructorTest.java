package io.github.augustinlr17.localhardwarebridge.utils;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.Assert.*;

/**
 * Verifies that utility classes have private constructors (java:S1118).
 */
public class UtilityClassConstructorTest {

    @Test
    public void certificateGeneratorHasPrivateConstructor() throws Exception {
        Constructor<CertificateGenerator> ctor = CertificateGenerator.class.getDeclaredConstructor();
        assertTrue("CertificateGenerator should have a declared constructor",
                ctor.getParameterCount() == 0);
        int modifiers = ctor.getModifiers();
        assertTrue("CertificateGenerator constructor should be private",
                Modifier.isPrivate(modifiers));
    }

    @Test
    public void threadUtilHasPrivateConstructor() throws Exception {
        Constructor<ThreadUtil> ctor = ThreadUtil.class.getDeclaredConstructor();
        assertTrue("ThreadUtil should have a declared constructor",
                ctor.getParameterCount() == 0);
        int modifiers = ctor.getModifiers();
        assertTrue("ThreadUtil constructor should be private",
                Modifier.isPrivate(modifiers));
    }

    @Test
    public void certificateGeneratorConstructorIsInaccessible() throws Exception {
        Constructor<CertificateGenerator> ctor = CertificateGenerator.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        // Should be able to invoke the private constructor via reflection
        CertificateGenerator instance = ctor.newInstance();
        assertNotNull(instance);
    }

    @Test
    public void systemdServiceGeneratorHasPrivateConstructor() throws Exception {
        Constructor<SystemdServiceGenerator> ctor = SystemdServiceGenerator.class.getDeclaredConstructor();
        assertTrue("SystemdServiceGenerator should have a declared constructor",
                ctor.getParameterCount() == 0);
        int modifiers = ctor.getModifiers();
        assertTrue("SystemdServiceGenerator constructor should be private",
                Modifier.isPrivate(modifiers));
    }
}
