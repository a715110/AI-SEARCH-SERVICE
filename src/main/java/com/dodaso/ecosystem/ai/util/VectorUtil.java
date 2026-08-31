package com.dodaso.ecosystem.ai.util;

import java.util.Arrays;

public class VectorUtil {

    /**
     * Converts a float array to a PostgreSQL vector string representation format: [v1,v2,...]
     *
     * @param vector The float array to convert.
     * @return A string formatted for PostgreSQL vector types.
     */
    public static String toString(float[] vector) {
        if (vector == null) {
            return null;
        }
        return Arrays.toString(vector).replace(" ", "");
    }

    /**
     * Converts a PostgreSQL vector string representation format: [v1,v2,...] back to a float array.
     *
     * @param vectorString The string to parse.
     * @return A float array representation of the vector.
     */
    public static float[] toFloatArray(String vectorString) {
        if (vectorString == null || vectorString.isEmpty()) {
            return null;
        }
        
        String cleanString = vectorString.replace("[", "").replace("]", "");
        String[] parts = cleanString.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }
}
