/* 防止高层采集门面重新泄漏 PCM 或底层适配器监听合同。 */
package io.github.fongmi.adaudio.probe.tools;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;

import static org.junit.Assert.assertFalse;

public class PublicApiBoundaryTest {
    @Test
    public void collectorDoesNotExposePcmListener() {
        assertFalse(ProbeAdapter.Listener.class.isAssignableFrom(
                AudioFingerprintCollector.class));
        for (Method method : AudioFingerprintCollector.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertFalse(method.getReturnType() == ProbePcmFrame.class);
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == ProbePcmFrame.class);
            }
        }
    }
}
