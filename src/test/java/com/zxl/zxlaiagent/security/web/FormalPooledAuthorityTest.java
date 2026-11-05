package com.zxl.zxlaiagent.security.web;

import com.zxl.zxlaiagent.security.SecurityDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormalPooledAuthorityTest {
    @Test void formalMainUsesExplicitPooledFactoryAndOwnsItsClose() throws Exception {
        var expected = mock(SecurityDatabase.class);
        try (var factory = mockStatic(SecurityDatabase.class)) {
            factory.when(SecurityDatabase::pooledFromEnvironment).thenReturn(expected);
            var config = new FormalSecurityConfiguration(new MockEnvironment().withProperty("agent.security.mode", "required"));
            assertSame(expected, config.securityDatabase());
            factory.verify(SecurityDatabase::pooledFromEnvironment);
            factory.verify(SecurityDatabase::fromEnvironment, never());
        }
        var bean = FormalSecurityConfiguration.class.getDeclaredMethod("securityDatabase").getAnnotation(Bean.class);
        assertEquals("close", bean.destroyMethod(), "Formal application context must own the pool lifecycle");
    }
}
