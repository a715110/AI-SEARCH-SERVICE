package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EcwsEmbeddingSyncRepository extends JpaRepository<EcwsEmbeddingSync, Long> {
    Optional<EcwsEmbeddingSync> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    @Modifying
    @Transactional
    void deleteBySourceTypeAndSourceId(String sourceType, Long sourceId);

    @Query("SELECT DISTINCT s.sourceId FROM EcwsEmbeddingSync s WHERE s.sourceType IN :sourceTypes")
    List<Long> findDistinctSourceIdsBySourceTypeIn(@Param("sourceTypes") Collection<String> sourceTypes);

    List<EcwsEmbeddingSync> findByStatusAndNextRetryAtBefore(String status, Instant now);
}
