package com.dodaso.ecosystem.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * An answer as the model returns it when it may make a file: the chat reply, and a document spec
 * when the user asked for a Word or Excel file (null otherwise). Our code builds the file from the
 * spec (AiDocumentService); the model never returns file bytes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiAnswerSpec(String answer, FileSpec file) {

    /** The file to make: DOCX or XLSX, its name without extension, a title and the content. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileSpec(String format, String fileName, String title, List<BlockSpec> blocks) {}

    /**
     * One piece of content. type is heading (level 1-3, text), paragraph (text), bullets (items)
     * or table (name, columns, rows); the fields another type uses are null.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlockSpec(String type, Integer level, String text, List<String> items, String name,
        List<String> columns, List<List<String>> rows) {}

    /**
     * JSON schema sent to OpenAI as the response format (strict structured output): every field is
     * required, and the ones a block type doesn't use are null. OpenAI rejects a nullable array
     * written as "type": ["array", "null"], so those use anyOf.
     */
    static final String JSON_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "answer": {"type": "string",
                       "description": "Your reply in the chat, in the language of the user's question, not of the sources."},
            "file": {"anyOf": [{"$ref": "#/$defs/file"}, {"type": "null"}]}
          },
          "required": ["answer", "file"],
          "additionalProperties": false,
          "$defs": {
            "file": {
              "type": "object",
              "properties": {
                "format": {"type": "string", "enum": ["DOCX", "XLSX"]},
                "fileName": {"type": "string"},
                "title": {"type": ["string", "null"]},
                "blocks": {"type": "array", "items": {"$ref": "#/$defs/block"}}
              },
              "required": ["format", "fileName", "title", "blocks"],
              "additionalProperties": false
            },
            "block": {
              "type": "object",
              "properties": {
                "type": {"type": "string", "enum": ["heading", "paragraph", "bullets", "table"]},
                "level": {"type": ["integer", "null"]},
                "text": {"type": ["string", "null"]},
                "items": {"anyOf": [{"type": "array", "items": {"type": "string"}}, {"type": "null"}]},
                "name": {"type": ["string", "null"]},
                "columns": {"anyOf": [{"type": "array", "items": {"type": "string"}}, {"type": "null"}]},
                "rows": {"anyOf": [{"type": "array", "items": {"type": "array", "items": {"type": "string"}}},
                                   {"type": "null"}]}
              },
              "required": ["type", "level", "text", "items", "name", "columns", "rows"],
              "additionalProperties": false
            }
          }
        }""";
}
