package com.dodaso.ecosystem.ai.repository;

import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import jakarta.transaction.Transactional;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EcwsEmbeddingRepository extends JpaRepository<EcwsEmbedding, Long> {

    // Basic CRUD and Search
    List<EcwsEmbedding> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
    @Modifying
    @Transactional
    void deleteBySourceTypeAndSourceId(String sourceType, Long sourceId);

    // Scoped apps (ELCM): rows of one app only
    List<EcwsEmbedding> findByAppCodeAndSourceTypeAndSourceId(String appCode, String sourceType, Long sourceId);

    @Modifying
    @Transactional
    long deleteByAppCodeAndSourceTypeAndSourceId(String appCode, String sourceType, Long sourceId);

    @Query("SELECT DISTINCT e.sourceId FROM EcwsEmbedding e WHERE e.sourceType IN :sourceTypes")
    List<Long> findDistinctSourceIdsBySourceTypeIn(@Param("sourceTypes") Collection<String> sourceTypes);

    /**
     * Similarity search implementation
     * Utilizes the HNSW index via the <=> operator in the ORDER BY clause
     */
    @Query(value = """
        SELECT *
        FROM dodaso_embeddings
        WHERE app_code = 'ECWS'
        AND project_id = :projectId
        AND workspace_id = :workspaceId
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<EcwsEmbedding> findSimilar(@Param("projectId") Long projectId,
        @Param("workspaceId") Long workspaceId,
        @Param("queryVector") String queryVector,
        @Param("limit") int limit);

    /**
     * Search across all source types within a project (no workspaceId filter).
     * Explicitly selects columns and casts embedding to text so Hibernate
     * can map all fields including parent_source_id correctly.
     * ATTACH-CS: task_id added to the column list; without it the new EcwsEmbedding.taskId field
     * can't be mapped and every search fails (plan §7.5). The list was otherwise unchanged.
     * Every EcwsEmbedding column must be listed here, including the ones ELCM rows use.
     */
    @Query(value = """
        SELECT id, source_type, source_id, chunk_index, chunk_text, chunk_tokens,
               embedding::text AS embedding, summary, status, project_id,
               created_by_user_id, updated_by_user_id, workspace_id, user_id,
               source_updated_at, priority, requestor, due_date, scheduled_date,
               parent_source_id, task_id,
               company_id, app_code, scope_id, owner_type, owner_id, title, embedding_model,
               created_at, created_by, updated_at, updated_by
        FROM dodaso_embeddings
        WHERE app_code = 'ECWS'
          AND project_id = :projectId
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<EcwsEmbedding> findSimilarAll(@Param("projectId") Long projectId,
        @Param("queryVector") String queryVector,
        @Param("limit") int limit);

    /**
     * Search all content scoped to a specific task:
     * task body (workspace_id=1) + its comments (workspace_id=2) + its direct file attachments (workspace_id=3).
     */
    @Query(value = """
        SELECT *
        FROM dodaso_embeddings
        WHERE app_code = 'ECWS'
          AND project_id = :projectId
          AND (
            (workspace_id = 1 AND source_id        = :taskId)
         OR (workspace_id = 2 AND parent_source_id = :taskId)
         OR (workspace_id = 3 AND parent_source_id = :taskId)
          )
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<EcwsEmbedding> findSimilarByTask(@Param("projectId") Long projectId,
        @Param("taskId") Long taskId,
        @Param("queryVector") String queryVector,
        @Param("limit") int limit);

    /**
     * Search comment-level file attachments scoped to a specific comment (workspace_id=4).
     */
    @Query(value = """
        SELECT *
        FROM dodaso_embeddings
        WHERE app_code = 'ECWS'
          AND project_id = :projectId
          AND workspace_id = 4
          AND parent_source_id = :commentId
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<EcwsEmbedding> findSimilarByComment(@Param("projectId") Long projectId,
        @Param("commentId") Long commentId,
        @Param("queryVector") String queryVector,
        @Param("limit") int limit);

    /**
     * Hybrid search: vector similarity + priority and due date range filters (tasks only).
     */
    @Query(value = """
        SELECT *
        FROM dodaso_embeddings
        WHERE app_code = 'ECWS'
          AND project_id = :projectId
          AND workspace_id = 1
          AND (:priority    IS NULL OR priority  = :priority)
          AND (:dueDateFrom IS NULL OR due_date >= CAST(:dueDateFrom AS date))
          AND (:dueDateTo   IS NULL OR due_date <= CAST(:dueDateTo   AS date))
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<EcwsEmbedding> findSimilarWithFilter(@Param("projectId") Long projectId,
        @Param("queryVector") String queryVector,
        @Param("priority") String priority,
        @Param("dueDateFrom") Date dueDateFrom,
        @Param("dueDateTo") Date dueDateTo,
        @Param("limit") int limit);

    /**
     * Search limited to one app and the scopes the caller may see (ELCM workspaces), and to
     * vectors of the current model. Returns a projection, so new EcwsEmbedding columns don't
     * have to be listed here.
     */
    @Query(value = """
        SELECT id, source_type AS "sourceType", source_id AS "sourceId", chunk_index AS "chunkIndex",
               chunk_text AS "chunkText", title, app_code AS "appCode", scope_id AS "scopeId",
               owner_type AS "ownerType", owner_id AS "ownerId", parent_source_id AS "parentSourceId",
               1 - (embedding <=> (:queryVector)::vector) AS score
        FROM dodaso_embeddings
        WHERE app_code = :appCode
          AND scope_id IN (:scopeIds)
          AND embedding_model = :embeddingModel
          AND source_type IN (:sourceTypes)
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<ScopedEmbeddingRow> findSimilarScoped(@Param("appCode") String appCode,
        @Param("scopeIds") Collection<Long> scopeIds,
        @Param("embeddingModel") String embeddingModel,
        @Param("sourceTypes") Collection<String> sourceTypes,
        @Param("queryVector") String queryVector,
        @Param("limit") int limit);

    /** As findSimilarScoped, every source type. */
    @Query(value = """
        SELECT id, source_type AS "sourceType", source_id AS "sourceId", chunk_index AS "chunkIndex",
               chunk_text AS "chunkText", title, app_code AS "appCode", scope_id AS "scopeId",
               owner_type AS "ownerType", owner_id AS "ownerId", parent_source_id AS "parentSourceId",
               1 - (embedding <=> (:queryVector)::vector) AS score
        FROM dodaso_embeddings
        WHERE app_code = :appCode
          AND scope_id IN (:scopeIds)
          AND embedding_model = :embeddingModel
        ORDER BY embedding <=> (:queryVector)::vector
        LIMIT :limit""", nativeQuery = true)
    List<ScopedEmbeddingRow> findSimilarScopedAllTypes(@Param("appCode") String appCode,
        @Param("scopeIds") Collection<Long> scopeIds,
        @Param("embeddingModel") String embeddingModel,
        @Param("queryVector") String queryVector,
        @Param("limit") int limit);

//
//    @Query(value = """
//    SELECT *
//    FROM ecws_embeddings
//    ORDER BY embedding <=> CAST(:queryVector AS vector)
//    LIMIT :limit
//    """, nativeQuery = true)
//    List<EcwsEmbedding> findSimilarPrompt(
//        @Param("queryVector") String queryVector,
//        @Param("limit") int limit);
}