package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.AiChatMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findByConversationIdOrderBySeqAsc(Long conversationId);

    /** Newest messages first; the page size is how many. */
    List<AiChatMessage> findByConversationIdOrderBySeqDesc(Long conversationId, Pageable pageable);

    @Query("select coalesce(max(m.seq), 0) from AiChatMessage m where m.conversationId = :conversationId")
    int findMaxSeq(@Param("conversationId") Long conversationId);
}
