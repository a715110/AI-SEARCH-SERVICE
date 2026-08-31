package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.EcwsSearchHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EcwsSearchHistoryRepository extends JpaRepository<EcwsSearchHistory, Long> {
    List<EcwsSearchHistory> findByProjectIdAndWorkspaceId(Long projectId, Long workspaceId);
    List<EcwsSearchHistory> findByUserId(Long userId);
}
