package io.github.augustinlr17.localhardwarebridge.services;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Tests for {@link DocumentService#verifyPublicHost(String)} via reflection.
 * This method is the SSRF mitigation guard that rejects private/reserved addresses.
 */
public class DocumentServiceVerifyPublicHostTest {

    private void verifyPublicHost(String host) throws Exception {
        Method m = DocumentService.class.getDeclaredMethod("verifyPublicHost", String.class);
        m.setAccessible(true);
        m.invoke(DocumentService.getInstance(), host);
    }

    @Test(expected = Exception.class)
    public void nullHostIsRejected() throws Throwable {
        try {
            verifyPublicHost(null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test(expected = Exception.class)
    public void emptyHostIsRejected() throws Throwable {
        try {
            verifyPublicHost("");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test(expected = Exception.class)
    public void loopbackAddressIsRejected() throws Throwable {
        try {
            verifyPublicHost("127.0.0.1");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test(expected = Exception.class)
    public void loopbackHostnameIsRejected() throws Throwable {
        try {
            verifyPublicHost("localhost");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test(expected = Exception.class)
    public void siteLocalAddressIsRejected() throws Throwable {
        try {
            verifyPublicHost("192.168.1.1");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test(expected = Exception.class)
    public void linkLocalAddressIsRejected() throws Throwable {
        try {
            verifyPublicHost("169.254.1.1");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test(expected = Exception.class)
    public void unresolvableHostIsRejected() throws Throwable {
        try {
            verifyPublicHost("this-host-does-not-exist-ever.invalid");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}