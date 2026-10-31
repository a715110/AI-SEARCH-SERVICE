package com.dodaso.ecosystem.ai.service;

import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;

@Service
@Slf4j
public class FileTextExtractorService {

    public String extract(InputStream inputStream, String fileName) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))                        return extractPdf(inputStream);
        if (lower.endsWith(".docx"))                       return extractDocx(inputStream);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return extractExcel(inputStream);
        if (lower.endsWith(".txt") || lower.endsWith(".md"))   return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        if (lower.endsWith(".pptx"))                           return extractPptx(inputStream);
        if (lower.endsWith(".ppt"))                            return extractPpt(inputStream);
        throw new UnsupportedOperationException("Unsupported file type: " + fileName);
    }

    private String extractPdf(InputStream is) throws IOException {
        try (PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            if (doc.isEncrypted()) {
                throw new IOException("PDF is encrypted and cannot be read without a password");
            }
            String text = new PDFTextStripper().getText(doc);
            if (text.isBlank()) {
                log.warn("Extracted empty text from PDF — it may be image-based");
            }
            return text;
        }
    }

    private String extractDocx(InputStream is) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringJoiner sj = new StringJoiner("\n");
            // doc.getParagraphs().forEach(p -> sj.add(p.getText()));
            // Paragraphs and tables in document order; a table row is one line, cells joined by " | "
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    sj.add(p.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        StringJoiner rowJoiner = new StringJoiner(" | ");
                        for (XWPFTableCell cell : row.getTableCells()) {
                            rowJoiner.add(cell.getTextRecursively().replaceAll("\\s+", " ").trim());
                        }
                        sj.add(rowJoiner.toString());
                    }
                }
            }
            return sj.toString();
        }
    }

    private String extractPptx(InputStream is) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(is)) {
            StringJoiner sj = new StringJoiner("\n");
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts && ts.getText() != null) {
                        sj.add(ts.getText());
                    }
                }
            }
            return sj.toString();
        }
    }

    private String extractPpt(InputStream is) throws IOException {
        try (HSLFSlideShow ppt = new HSLFSlideShow(is)) {
            StringJoiner sj = new StringJoiner("\n");
            for (HSLFSlide slide : ppt.getSlides()) {
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape ts && ts.getText() != null) {
                        sj.add(ts.getText());
                    }
                }
            }
            return sj.toString();
        }
    }

    private String extractExcel(InputStream is) throws IOException {
        try (Workbook wb = WorkbookFactory.create(is)) {
            StringJoiner sj = new StringJoiner("\n");
            for (Sheet sheet : wb) {
                for (Row row : sheet) {
                    StringJoiner rowJoiner = new StringJoiner(" | ");
                    for (Cell cell : row) rowJoiner.add(cell.toString());
                    sj.add(rowJoiner.toString());
                }
            }
            return sj.toString();
        }
    }
}
