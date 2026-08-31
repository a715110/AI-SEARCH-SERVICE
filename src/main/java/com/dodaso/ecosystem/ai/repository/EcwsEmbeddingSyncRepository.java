package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.EcwsEmbeddingSync;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EcwsEmbeddingSyncRepository extends JpaRepository<EcwsEmbeddingSync, Long> {
    Optional<EcwsEmbeddingSync> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
    
    List<EcwsEmbeddingSync> findByStatusAndNextRetryAtBefore(String status, Instant now);
}
