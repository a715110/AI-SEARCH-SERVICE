package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.AiChatFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AiChatFileRepository extends JpaRepository<AiChatFile, UUID> {

    /** The file, if it belongs to this user, is active and hasn't expired. */
    @Query("select f from AiChatFile f where f.id = :id and lower(f.ownerLoginId) = lower(:loginId)"
        + " and f.activeInd = 'Y' and f.expiresAt > :now")
    Optional<AiChatFile> findDownloadable(@Param("id") UUID id, @Param("loginId") String loginId,
        @Param("now") Instant now);

    @Modifying
    @Query("delete from AiChatFile f where f.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /** Ties files an answer made to its saved chat message. */
    @Modifying
    @Query("update AiChatFile f set f.conversationId = :conversationId, f.messageId = :messageId"
        + " where f.id in :ids")
    int linkToMessage(@Param("ids") List<UUID> ids, @Param("conversationId") Long conversationId,
        @Param("messageId") Long messageId);

    /** Files of these messages that can still be downloaded, without their bytes. */
    @Query("select f.id as id, f.messageId as messageId, f.fileName as fileName, f.contentType as contentType,"
        + " f.sizeBytes as sizeBytes, f.expiresAt as expiresAt from AiChatFile f"
        + " where f.messageId in :messageIds and f.activeInd = 'Y' and f.expiresAt > :now"
        + " order by f.createdAt")
    List<FileInfo> findInfoByMessageIds(@Param("messageIds") List<Long> messageIds, @Param("now") Instant now);

    /** A file's details without its bytes. */
    interface FileInfo {
        UUID getId();
        Long getMessageId();
        String getFileName();
        String getContentType();
        Long getSizeBytes();
        Instant getExpiresAt();
    }
}
