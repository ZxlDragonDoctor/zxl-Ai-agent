package com.zxl.zxlaiagent.advisor;//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.model.ModelOptionsUtils;
import reactor.core.publisher.Flux;

/**
 * 自定义日志拦截器
 */
public class MyLoggerAdviser implements CallAroundAdvisor, StreamAroundAdvisor {
    public static final Function<AdvisedRequest, String> DEFAULT_REQUEST_TO_STRING = (request) -> request.toString();
    public static final Function<ChatResponse, String> DEFAULT_RESPONSE_TO_STRING = (response) -> ModelOptionsUtils.toJsonStringPrettyPrinter(response);
    private static final Logger logger = LoggerFactory.getLogger(MyLoggerAdviser.class);
    private final Function<AdvisedRequest, String> requestToString;
    private final Function<ChatResponse, String> responseToString;
    private int order;

    public MyLoggerAdviser() {
        this(DEFAULT_REQUEST_TO_STRING, DEFAULT_RESPONSE_TO_STRING, 0);
    }

    public MyLoggerAdviser(int order) {
        this(DEFAULT_REQUEST_TO_STRING, DEFAULT_RESPONSE_TO_STRING, order);
    }

    public MyLoggerAdviser(Function<AdvisedRequest, String> requestToString, Function<ChatResponse, String> responseToString, int order) {
        this.requestToString = requestToString;
        this.responseToString = responseToString;
        this.order = order;
    }

    public String getName() {
        return this.getClass().getSimpleName();
    }

    public int getOrder() {
        return this.order; //默认是是0
    }

    private AdvisedRequest before(AdvisedRequest request) {
        logger.info("Ai Request: {}",request.userText());
        return request;
    }

    private void observeAfter(AdvisedResponse advisedResponse) {
        logger.info("AI Response: {}", advisedResponse.response().getResult().getOutput().getText());
    }

    public String toString() {
        return SimpleLoggerAdvisor.class.getSimpleName();
    }

    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest);
        this.observeAfter(advisedResponse);
        return advisedResponse;
    }

    public Flux<AdvisedResponse> aroundStream(AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        advisedRequest = this.before(advisedRequest);
        Flux<AdvisedResponse> advisedResponses = chain.nextAroundStream(advisedRequest);
        return (new MessageAggregator()).aggregateAdvisedResponse(advisedResponses, this::observeAfter);
    }
}
