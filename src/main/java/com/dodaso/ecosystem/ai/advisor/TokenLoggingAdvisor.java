package com.dodaso.ecosystem.ai.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;


@Slf4j
public class TokenLoggingAdvisor implements CallAdvisor {

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

    ChatClientResponse clientResponse = chain.nextCall(request);
    ChatResponse response = clientResponse.chatResponse();

    Usage usage = null;
    if (response != null) {
      usage = response.getMetadata().getUsage();
    }
    if (usage != null) {
      log.info("[Token Usage] prompt={}, generation={}, total={}",
          usage.getPromptTokens(),
          usage.getCompletionTokens(),
          usage.getTotalTokens());
    }

    return clientResponse;
  }

  @Override
  public String getName() { return "TokenLoggingAdvisor"; }

  @Override
  public int getOrder() { return 0; }
}



