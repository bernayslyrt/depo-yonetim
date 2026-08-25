package com.depo.service.impl;

import com.depo.entity.Category;
import com.depo.entity.Product;
import com.depo.repository.ProductRepository;
import com.depo.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final String[] HEADERS = {
            "Sıra No", "Ürün Kodu", "Ürün Adı", "Stok Miktarı", "Birim", "Depo/Kategori", "Son Güncelleme Tarihi"
    };

    private final ProductRepository productRepository;

    @Override
    public ByteArrayInputStream exportProductsToExcel() throws IOException {
        List<Product> products = loadProducts();

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("Güncel Stok Listesi");

            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);

            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(22);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (Product product : products) {
                Row row = sheet.createRow(rowIdx);
                fillProductRow(row, rowIdx, product, dataStyle);
                rowIdx++;
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, switch (i) {
                    case 0 -> 3000;
                    case 1 -> 4500;
                    case 2 -> 9000;
                    case 3 -> 4000;
                    case 4 -> 3500;
                    case 5 -> 7000;
                    case 6 -> 6500;
                    default -> 5000;
                });
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            workbook.dispose();
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Override
    public ByteArrayInputStream exportProductsToPdf() throws IOException {
        List<Product> products = loadProducts();

        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDType0Font fontRegular = loadFont(document, "fonts/Arial.ttf");
            PDType0Font fontBold = loadFont(document, "fonts/Arial-Bold.ttf");

            float margin = 36f;
            float rowHeight = 20f;
            float headerHeight = 24f;
            float fontSize = 8.5f;
            float headerFontSize = 9.5f;

            float[] colWidths = { 35f, 85f, 180f, 60f, 50f, 110f, 110f };

            PDPage page = newPage(document);
            PDPageContentStream content = new PDPageContentStream(document, page);
            float y = drawPdfTitle(content, page, fontBold, margin);

            y = drawPdfHeaderRow(content, fontBold, margin, y, colWidths, headerHeight, headerFontSize);

            int rowNum = 1;
            for (Product product : products) {
                if (y - rowHeight < margin + 20) {
                    content.close();
                    page = newPage(document);
                    content = new PDPageContentStream(document, page);
                    y = page.getMediaBox().getHeight() - margin;
                    y = drawPdfHeaderRow(content, fontBold, margin, y, colWidths, headerHeight, headerFontSize);
                }

                String[] values = productRowValues(rowNum++, product);
                y = drawPdfDataRow(content, fontRegular, margin, y, colWidths, rowHeight, fontSize, values,
                        rowNum % 2 == 0);
            }

            content.close();
            document.save(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private List<Product> loadProducts() {
        return productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private void fillProductRow(Row row, int rowNum, Product product, CellStyle dataStyle) {
        String[] values = productRowValues(rowNum, product);
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(dataStyle);
        }
    }

    private String[] productRowValues(int rowNum, Product product) {
        return new String[] {
                String.valueOf(rowNum),
                nullToDash(product.getCode()),
                product.getName(),
                String.valueOf(product.getQuantity()),
                nullToDash(product.getUnit()),
                categoryName(product.getCategory()),
                formatDate(product.getUpdatedAt() != null ? product.getUpdatedAt() : product.getCreatedAt())
        };
    }

    private String categoryName(Category category) {
        return category != null ? category.getName() : "—";
    }

    private String nullToDash(String value) {
        return value != null && !value.isBlank() ? value : "—";
    }

    private String formatDate(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATE_FORMAT) : "—";
    }

    private PDPage newPage(PDDocument document) {
        PDPage page = new PDPage(new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
        document.addPage(page);
        return page;
    }

    private float drawPdfTitle(PDPageContentStream content, PDPage page, PDType0Font fontBold, float margin)
            throws IOException {
        float y = page.getMediaBox().getHeight() - margin;
        content.beginText();
        content.setFont(fontBold, 14);
        content.newLineAtOffset(margin, y);
        content.showText("Güncel Stok Listesi");
        content.endText();

        content.beginText();
        content.setFont(fontBold, 9);
        content.newLineAtOffset(margin, y - 16);
        content.showText("Oluşturulma: " + LocalDateTime.now().format(DATE_FORMAT));
        content.endText();

        return y - 36;
    }

    private float drawPdfHeaderRow(PDPageContentStream content, PDType0Font fontBold,
            float margin, float y, float[] colWidths,
            float rowHeight, float fontSize) throws IOException {
        float x = margin;
        content.setNonStrokingColor(0.12f, 0.23f, 0.45f);
        content.addRect(margin, y - rowHeight, sum(colWidths), rowHeight);
        content.fill();

        for (int i = 0; i < HEADERS.length; i++) {
            drawPdfCellText(content, fontBold, fontSize, x + 4, y - rowHeight + 7, HEADERS[i], true);
            x += colWidths[i];
        }
        return y - rowHeight;
    }

    private float drawPdfDataRow(PDPageContentStream content, PDType0Font fontRegular,
            float margin, float y, float[] colWidths, float rowHeight,
            float fontSize, String[] values, boolean shaded) throws IOException {
        if (shaded) {
            content.setNonStrokingColor(0.96f, 0.96f, 0.96f);
            content.addRect(margin, y - rowHeight, sum(colWidths), rowHeight);
            content.fill();
        }

        float x = margin;
        for (int i = 0; i < values.length; i++) {
            drawPdfCellText(content, fontRegular, fontSize, x + 4, y - rowHeight + 6, values[i], false);
            x += colWidths[i];
        }

        // Çizgiler
        content.setStrokingColor(0.85f, 0.85f, 0.85f);
        content.moveTo(margin, y - rowHeight);
        content.lineTo(margin + sum(colWidths), y - rowHeight);
        content.stroke();

        return y - rowHeight;
    }

    private void drawPdfCellText(PDPageContentStream content, PDType0Font font, float fontSize,
            float x, float y, String text, boolean whiteText) throws IOException {
        content.beginText();
        content.setFont(font, fontSize);
        if (whiteText) {
            content.setNonStrokingColor(1f, 1f, 1f);
        } else {
            content.setNonStrokingColor(0f, 0f, 0f);
        }
        content.newLineAtOffset(x, y);
        content.showText(cleanText(truncate(text, 40)));
        content.endText();
    }

    private float sum(float[] values) {
        float total = 0;
        for (float v : values) {
            total += v;
        }
        return total;
    }

    private String cleanText(String text) {
        if (text == null)
            return "";
        return text.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }

    private PDType0Font loadFont(PDDocument document, String relativePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(relativePath);
        if (!resource.exists()) {
            throw new IOException("Font dosyası bulunamadı: " + relativePath);
        }
        try (InputStream is = resource.getInputStream()) {
            return PDType0Font.load(document, is);
        }
    }
}