package com.zxl.zxlaiagent.skills;

import java.util.regex.Pattern;

/** Minimum deterministic intake fields before knowledge lookup. */
final class SkillInput {
    private static final Pattern SOFTWARE = Pattern.compile(
            "(?i)vpn|sso|邮箱|email|mail|网络|network|客户端|client|登录|login|权限|permission|office|打印|printer");
    private static final Pattern CODE = Pattern.compile(
            "(?i)(?<![a-z0-9])[a-z]{0,8}[-_ ]?\\d{2,6}(?![a-z0-9])");
    private static final Pattern HARDWARE = Pattern.compile(
            "(?i)\\b(keyboard|projector|chair|badge|printer)\\b|键盘|投影仪|办公椅|门禁卡|打印机");
    private static final Pattern SYMPTOM = Pattern.compile(
            "(?i)\\b(broken|damaged|missing|lost|repair|unresponsive)\\b|does(?:n't| not) work|"
                    + "won't (?:start|print|respond)|损坏|坏了|丢失|无法(?:开机|打印|使用)|不响应|维修|修理");
    static final String CLARIFICATION =
            "请补充产品、错误码及现象，或设备名称和具体故障。 / Provide product and error code, or device and a concrete fault.";

    private SkillInput() {}

    static boolean ready(String question) {
        if (question == null) return false;
        return (SOFTWARE.matcher(question).find() && CODE.matcher(question).find())
                || (HARDWARE.matcher(question).find() && SYMPTOM.matcher(question).find());
    }
}
