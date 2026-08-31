//package com.dodaso.ecosystem.ai.config;
//
//import com.github.benmanes.caffeine.cache.Caffeine;
//import java.util.concurrent.TimeUnit;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
//import org.springframework.cache.caffeine.CaffeineCacheManager;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//@Configuration
//public class AiConfig {
//
//  @Bean
//  public ChatClient chatClient(ChatModel chatModel) {
//    return ChatClient.builder(chatModel).build();
//  }
//
//}
