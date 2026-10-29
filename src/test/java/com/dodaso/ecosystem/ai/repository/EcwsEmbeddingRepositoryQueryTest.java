package com.dodaso.ecosystem.ai.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dodaso.ecosystem.ai.entity.EcwsEmbedding;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * The ECWS native queries read only ECWS rows of the shared dodaso_embeddings table, so ELCM
 * contract text can never come back in an ECWS search or answer.
 */
class EcwsEmbeddingRepositoryQueryTest {

    private static List<Method> nativeQueries() {
        List<Method> methods = new ArrayList<>();
        for (Method m : EcwsEmbeddingRepository.class.getDeclaredMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q != null && q.nativeQuery()) {
                methods.add(m);
            }
        }
        return methods;
    }

    @Test
    void everyNativeQueryUsesDodasoTableAndEcwsFilter() {
        List<Method> methods = nativeQueries();
        assertFalse(methods.isEmpty());
        for (Method m : methods) {
            String sql = m.getAnnotation(Query.class).value();
            assertTrue(sql.contains("FROM dodaso_embeddings"), m.getName() + " must read dodaso_embeddings");
            assertFalse(sql.contains("ecws_embeddings"), m.getName() + " still names ecws_embeddings");
            assertTrue(sql.contains("app_code = 'ECWS'"), m.getName() + " must filter app_code = 'ECWS'");
        }
    }

    @Test
    void findSimilarAllListsEveryEntityColumn() throws Exception {
        Method m = EcwsEmbeddingRepository.class.getDeclaredMethod("findSimilarAll", Long.class, String.class, int.class);
        String sql = m.getAnnotation(Query.class).value();

        List<String> columns = new ArrayList<>();
        for (Class<?> c = EcwsEmbedding.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Column col = f.getAnnotation(Column.class);
                if (col != null) {
                    columns.add(col.name());
                }
            }
        }
        assertTrue(columns.contains("app_code"));
        for (String column : columns) {
            assertTrue(sql.matches("(?s).*\\b" + column + "\\b.*"), "findSimilarAll is missing column " + column);
        }
    }
}
