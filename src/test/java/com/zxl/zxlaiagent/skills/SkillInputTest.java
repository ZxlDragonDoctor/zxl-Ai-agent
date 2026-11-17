package com.zxl.zxlaiagent.skills;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillInputTest {
    @Test void hardwareNeedsAnIdentifiedDeviceAndConcreteSymptom() {
        for (String question : new String[]{"Draft a ticket for a broken keyboard.",
                "Repair the conference projector.", "My office chair is damaged.",
                "I lost my access badge.", "The printer does not work.", "键盘损坏，需要更换",
                "投影仪无法开机", "办公椅坏了", "门禁卡丢失", "打印机无法打印"}) {
            assertTrue(SkillInput.ready(question), question);
        }
        for (String question : new String[]{"keyboard", "Tell me about the printer", "Something is broken",
                "VPN login failed", "The keyboard is working", "office chair"}) {
            assertFalse(SkillInput.ready(question), question);
        }
    }

    @Test void preservesSoftwareErrorCodeMinimum() {
        assertTrue(SkillInput.ready("VPN 401 access failed"));
        assertTrue(SkillInput.ready("SSO 403 无法登录"));
        assertFalse(SkillInput.ready("error 401"));
        assertFalse(SkillInput.ready("VPN"));
    }
}
