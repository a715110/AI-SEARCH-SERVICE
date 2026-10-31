package com.dodaso.ecosystem.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;

class FileTextExtractorServiceTest {

    private final FileTextExtractorService extractor = new FileTextExtractorService();

    @Test
    void docxTablesAreReadInDocumentOrder() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("ARTICLE 1. BASIC LEASE INFORMATION");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Premises");
            table.getRow(0).getCell(1).setText("Suite 400");
            table.getRow(1).getCell(0).setText("Rentable Square Feet");
            // A cell with two paragraphs stays on one line
            XWPFTableCell cell = table.getRow(1).getCell(1);
            cell.setText("12,450 RSF");
            cell.addParagraph().createRun().setText("fourth floor");
            doc.createParagraph().createRun().setText("ARTICLE 2. PREMISES");
            doc.write(out);
            docx = out.toByteArray();
        }

        String text = extractor.extract(new ByteArrayInputStream(docx), "Lease.DOCX");

        assertEquals("""
            ARTICLE 1. BASIC LEASE INFORMATION
            Premises | Suite 400
            Rentable Square Feet | 12,450 RSF fourth floor
            ARTICLE 2. PREMISES""", text);
    }
}
