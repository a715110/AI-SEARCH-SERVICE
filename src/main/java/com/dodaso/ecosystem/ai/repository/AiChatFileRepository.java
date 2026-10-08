package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.AiChatFile;
import java.time.Instant;
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
}
