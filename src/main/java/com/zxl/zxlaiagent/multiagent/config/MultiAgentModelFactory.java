package com.zxl.zxlaiagent.multiagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.*;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.zxl.zxlaiagent.multiagent.runtime.RuntimeSettings;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/** M6.1 的同步 RestClient 路径：有限网络超时，无隐式重试，无工具递归。 */
public final class MultiAgentModelFactory {
    private MultiAgentModelFactory() {}
    public static DashScopeChatModel create(DashScopeConnectionProperties connection, DashScopeChatProperties chat, RuntimeSettings limits) {
        limits.validate();
        String baseUrl = choose(chat.getBaseUrl(), connection.getBaseUrl());
        String key = choose(chat.getApiKey(), connection.getApiKey());
        String workspace = choose(chat.getWorkspaceId(), connection.getWorkspaceId());
        JdkClientHttpRequestFactory requests = requestFactory(limits);
        DashScopeApi api = new DashScopeApi(baseUrl, key, workspace, RestClient.builder().requestFactory(requests),
                WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        // 应用只提供一次调用；重试策略由第八课显式管理，避免底层继续偷偷发起请求。
        return new DashScopeChatModel(api, chat.getOptions(), null, List.of(), RetryTemplate.builder().maxAttempts(1).build());
    }
    public static JdkClientHttpRequestFactory requestFactory(RuntimeSettings limits) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(limits.getConnectTimeoutMs())).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(limits.getRequestTimeoutMs()));
        return factory;
    }
    private static String choose(String specific, String fallback) { return specific != null && !specific.isBlank() ? specific : fallback; }
}
