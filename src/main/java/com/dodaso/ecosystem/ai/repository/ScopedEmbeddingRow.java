package com.dodaso.ecosystem.ai.repository;

/**
 * One row of a scoped search (EcwsEmbeddingRepository.findSimilarScoped*). A projection, so the
 * query doesn't have to list every EcwsEmbedding column. Aliases are quoted to keep the case.
 */
public interface ScopedEmbeddingRow {
    Long getId();
    String getSourceType();
    Long getSourceId();
    Integer getChunkIndex();
    String getChunkText();
    String getTitle();
    String getAppCode();
    Long getScopeId();
    String getOwnerType();
    Long getOwnerId();
    Long getParentSourceId();
    /** 1 - cosine distance */
    Double getScore();
}
