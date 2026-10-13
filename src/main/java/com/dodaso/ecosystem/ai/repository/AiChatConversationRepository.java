package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.AiChatConversation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiChatConversationRepository extends JpaRepository<AiChatConversation, Long> {

    /** The chat, if it belongs to this user and hasn't been deleted. */
    @Query("select c from AiChatConversation c where c.id = :id and lower(c.ownerLoginId) = lower(:loginId)"
        + " and c.activeInd = 'Y'")
    Optional<AiChatConversation> findOwned(@Param("id") Long id, @Param("loginId") String loginId);

    /** Same as findOwned, locked until the transaction ends, so two answers can't take the same seq. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AiChatConversation c where c.id = :id and lower(c.ownerLoginId) = lower(:loginId)"
        + " and c.activeInd = 'Y'")
    Optional<AiChatConversation> findOwnedForUpdate(@Param("id") Long id, @Param("loginId") String loginId);

    /** One user's open chats in one app, newest first. */
    @Query("select c from AiChatConversation c where lower(c.ownerLoginId) = lower(:loginId)"
        + " and c.appCode = :appCode and c.activeInd = 'Y' order by c.lastMessageAt desc, c.id desc")
    List<AiChatConversation> findRecent(@Param("loginId") String loginId, @Param("appCode") String appCode,
        Pageable pageable);
}
