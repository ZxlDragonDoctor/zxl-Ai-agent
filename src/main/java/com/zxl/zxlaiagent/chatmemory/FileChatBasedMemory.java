package com.zxl.zxlaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;


import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FileChatBasedMemory implements ChatMemory {

   private final String BASE_DIR;
   private static  final Kryo kryo =  new Kryo();

    static {
        kryo.setRegistrationRequired(false);
        // 设置实例化策略
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    // 构造函数时，指定文件保存目录
    public FileChatBasedMemory(String dir){
        this.BASE_DIR = dir;
        File file = new File(dir);
        if(!file.exists()){
            file.mkdir();
        }
    }
    public File getConversationFile(String conversationId){
        return new File(BASE_DIR,conversationId+".kryo");
    }
    private  List<Message> getOrCreateConversation(String conversationId) {
        File conversationFile = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        if (conversationFile.exists()) {
            try (Input input = new Input(new FileInputStream(conversationFile))) {
                 messages = kryo.readObject(input,ArrayList.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return messages;
    }
    private  void saveConversation(String conversationId,List<Message> messages){
        File conversationFile = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(conversationFile))) {
           kryo.writeObject(output,messages);
       } catch (Exception e) {
           e.printStackTrace();
       }
    }
    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> messageList = getOrCreateConversation(conversationId);
        messageList.addAll(messageList);
        saveConversation(conversationId, messageList);
    }


    /**
     * 获取最近几天对话消息
     * @param conversationId
     * @param lastN
     * @return
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> messageList = getOrCreateConversation(conversationId);
        return messageList.stream()
                .skip(Math.max(0,messageList.size()-lastN))
                .toList();
    }

    @Override
    public void clear(String conversationId) {
//        List<Message> messageList = getOrCreateConversation(conversationId);
//        messageList.clear();
//        saveConversation(conversationId,messageList);
        File conversationFile = getConversationFile(conversationId);
        if(conversationFile.exists()){
            conversationFile.delete();
        }
    }
}
