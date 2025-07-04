package com.zxl.zxlaiagent.app;


import com.zxl.zxlaiagent.advisor.LoveAppRagCustomAdvisorFactory;
import com.zxl.zxlaiagent.advisor.MyLoggerAdviser;
import com.zxl.zxlaiagent.annotation.RecordChat;
import com.zxl.zxlaiagent.chatmemory.FileChatBasedMemory;
import com.zxl.zxlaiagent.rag.transformar.QueryRewriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Component
@Slf4j
public class LoveApp {
    @Resource
    private SimpleVectorStore loveAppVectorStore;
    @Resource
    private Advisor loveAppRagCloudAdvisor;
    /**
     * chatClient 使用实例
     */
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "你扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。(身份表达古过后，不用在后续对话中继续表达)" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";
//    public LoveApp(ChatModel dashscopeChatModel){
//        ChatMemory chatMemory = new InMemoryChatMemory();//设置对话记忆保存在内存
//        chatClient = ChatClient.builder(dashscopeChatModel)
//                .defaultSystem(SYSTEM_PROMPT) //设置系统提示词
//                .defaultAdvisors(
//                        new MessageChatMemoryAdvisor(chatMemory),
//                        new SimpleLoggerAdvisor()
//                        new MyLoggerAdviser()
//                )
//                .build();
//    }

    /**
     * 使用自定义chat-memory,存储在磁盘文件里
     *
     * @param dashscopeChatModel
     */
    public LoveApp(ChatModel dashscopeChatModel,FileChatBasedMemory fileChatBasedMemory) {
        String baseDir = System.getProperty("user.dir") + "/chat-memory";
        fileChatBasedMemory.setBaseDir(baseDir);
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT) //设置系统提示词
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(fileChatBasedMemory), // 基于文件保存对话
                        new MyLoggerAdviser()
                )
                .build();
    }


    record LoveReport(String title, List<String> suggestions) {
    }



    @RecordChat
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call()
                .chatResponse();

        String content = chatResponse.getResult().getOutput().getText();
        log.info("content:{}", content);

        return content;
    }

    //普通对话方法
    public LoveReport doChatWithRePort(String message, String chatId) {
        LoveReport entity = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表\"")
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId) //设置会话Id
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))  //设置保存的最新会话数量
                .call()
                .entity(LoveReport.class);
        log.info("loveReport:{}", entity);
        return entity;
    }

    /**
     * 基于云端Rag知识库的会话
     */
    public String doChatWithRag1(String message,String chatId){
        ChatResponse chatResponse = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表\"")
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId) //设置会话Id
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))  //设置保存的最新会话数量
                //.advisors(new QuestionAnswerAdvisor(loveAppVectorStore)) // 应用知识库进行回答
                .advisors(loveAppRagCloudAdvisor) //基于应用检索增强
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}",content);
        return content;
    }

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 应用查询重写器
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithRag(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                .advisors(
                        LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(loveAppVectorStore, "已婚")
                        // 配置文档检索增强
                )
                .user(rewrittenMessage)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }


    @Resource
    private ToolCallback[] allTools;

    /**
     * 工具调用对话
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .tools(allTools)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * 测试mcp服务调用
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .tools(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
//        log.info("content: {}", content);
        return content;
    }


    /**
     * 流式调用
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .stream()
                .content();
    }

}


