package com.depo.bulkimport.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PdfRecordMicroChunkingTest {

    private final PdfRecordSegmenter segmenter = new PdfRecordSegmenter();

    @Test
    void twentyTwoMixedRecordsAreDetectedAcrossAllSupportedMarkerStyles() {
        PdfRecordSegmenter.Segmentation segmentation = segmenter.segment(mixedRecords(22));

        assertThat(segmentation.confidence()).isEqualTo(PdfRecordSegmenter.Confidence.RELIABLE);
        assertThat(segmentation.records()).hasSize(22);
        assertThat(segmentation.mixedFormats()).isTrue();
        assertThat(segmentation.records())
                .extracting(PdfRecordSegmenter.LogicalRecord::sourceRecordId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 22).boxed().toList());
        assertThat(segmentation.records().get(2).productNameAnchor()).isEqualTo("Product-3");
    }

    @Test
    void twentyTwoRecordsProduceFiveFiveFiveFiveTwoWithoutSourceLoss() throws Exception {
        DocumentParsingService service = new DocumentParsingService(
                mock(OllamaParsingService.class), 10, 12_000, 2_000, 5);

        List<DocumentChunk> chunks = service.createPdfChunks(
                asPdfUpload(mixedRecords(22)), "mixed-22.pdf");

        assertThat(chunks).extracting(DocumentChunk::candidateRecordCount)
                .containsExactly(5, 5, 5, 5, 2);
        assertThat(chunks).flatExtracting(DocumentChunk::sourceRecordIdentifiers)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 22).boxed().toList());
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.pdfMetadata()).isNotNull();
            assertThat(chunk.pdfMetadata().boundaryConfidence())
                    .isEqualTo(PdfRecordSegmenter.Confidence.RELIABLE);
        });
        assertThat(chunks.get(4).content()).contains("Product-21", "Product-22");
        assertThat(chunks.get(0).content())
                .contains("RECORD 1:\nSOURCE QUANTITY EVIDENCE: 1")
                .contains("RECORD 5:\nSOURCE QUANTITY EVIDENCE: 5");
    }

    @Test
    void technicalNumbersDoNotCreateFalseRecordBoundaries() {
        String source = "Workshop notes\n"
                + "12V power support\n"
                + "5mm tip width\n"
                + "300 adet package contents\n"
                + "20x15mm dimensions\n"
                + "2020 profile system\n"
                + "2 metre cable length";

        PdfRecordSegmenter.Segmentation segmentation = segmenter.segment(source);

        assertThat(segmentation.confidence()).isEqualTo(PdfRecordSegmenter.Confidence.UNKNOWN);
        assertThat(segmentation.records()).isEmpty();
    }

    @Test
    void technicalWordsInsideNumberedMixedRecordsAreNotMistakenForHeaders() {
        String source = "Malzeme: First\nIstenen miktar: 1\n"
                + "2) DC Adapter | 12V degeri teknik ozelliktir | Urun kodu: E-2 | Toplam: 2 adet\n"
                + "Urun Adi: Third\nADET: 3\n"
                + "4) Mini Breadboard | 170 baglanti noktasi | Urun kodu: yok | Toplam: 4 adet\n"
                + "Malzeme: Fifth\nIstenen miktar: 5";

        PdfRecordSegmenter.Segmentation segmentation = segmenter.segment(source);

        assertThat(segmentation.confidence()).isEqualTo(PdfRecordSegmenter.Confidence.RELIABLE);
        assertThat(segmentation.records()).hasSize(5);
    }

    @Test
    void freeFormPageUsesSmallerCharacterFallbackChunks() throws Exception {
        DocumentParsingService service = new DocumentParsingService(
                mock(OllamaParsingService.class), 10, 12_000, 1_000, 5);
        List<String> lines = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "Free form paragraph " + index + " " + "X".repeat(180))
                .toList();

        List<DocumentChunk> chunks = service.createPdfChunks(
                asPdfUpload(String.join("\n", lines)), "free-form.pdf");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.pdfMetadata()).isNull();
            assertThat(chunk.candidateRecordCount()).isZero();
            assertThat(chunk.sourceCharacterCount()).isLessThanOrEqualTo(1_000);
        });
    }

    @Test
    void missingCodeEvidenceIsStoredPerLogicalRecord() {
        String source = "Malzeme: Coded Product\nKod: P-1\nIstenen miktar: 2\n"
                + "Malzeme: M3 Vida Somun Seti\nKod: Belgede belirtilmemis\n"
                + "Istenen miktar: 3";

        PdfRecordSegmenter.Segmentation segmentation = segmenter.segment(source);

        assertThat(segmentation.records()).hasSize(2);
        assertThat(segmentation.records().get(0).explicitProductCodeAbsent()).isFalse();
        assertThat(segmentation.records().get(0).productCodeAnchor()).isEqualTo("P-1");
        assertThat(segmentation.records().get(1).explicitProductCodeAbsent()).isTrue();
        assertThat(segmentation.records().get(1).productCodeAnchor()).isNull();
    }

    @Test
    void flattenedTableRowsExposeOnlyConservativeProductNameAnchors() {
        String source = "1 Tuval 30x40 cm Su bazlıdır; teknik açıklama Ortak kullanım 9\n"
                + "2 Eskiz Defteri A4 Set içerisinde 24 renk vardır Ortak kullanım 19\n"
                + "3 Kraft Karton Kalınlık 2 mm, teknik ölçüdür Ortak kullanım 5";

        PdfRecordSegmenter.Segmentation segmentation = segmenter.segment(source);

        assertThat(segmentation.confidence()).isEqualTo(PdfRecordSegmenter.Confidence.RELIABLE);
        assertThat(segmentation.records())
                .extracting(PdfRecordSegmenter.LogicalRecord::productNameAnchor)
                .containsExactly("Tuval 30x40 cm", "Eskiz Defteri A4", "Kraft Karton");
    }

    @Test
    void technicalDescriptionStartsDoNotRemoveSpecificationsBelongingToProductNames() {
        String source = "1 Breadboard 830 Pin 3.5 x 16 mm ölçülerde parçalar içerir. Adet 13\n"
                + "2 Lehim Teli 0.8 mm 300 parçalı kutu içeriği olabilir. Adet 14\n"
                + "3 Pil Yuvası 4xAA 12V adaptörlerle uyumludur. Adet 6";

        PdfRecordSegmenter.Segmentation segmentation = segmenter.segment(source);

        assertThat(segmentation.confidence()).isEqualTo(PdfRecordSegmenter.Confidence.RELIABLE);
        assertThat(segmentation.records())
                .extracting(PdfRecordSegmenter.LogicalRecord::productNameAnchor)
                .containsExactly("Breadboard 830 Pin", "Lehim Teli 0.8 mm", "Pil Yuvası 4xAA");
    }

    private String mixedRecords(int count) {
        List<String> lines = new ArrayList<>();
        lines.add("MIXED INVENTORY PAGE");
        lines.add("Headers are reference only");
        for (int index = 1; index <= count; index++) {
            switch ((index - 1) % 3) {
                case 0 -> {
                    lines.add("Malzeme: Product-" + index);
                    lines.add("Kod: Belgede belirtilmemis");
                    lines.add("Istenen miktar: " + index);
                }
                case 1 -> lines.add(index + ") Product-" + index
                        + " | Technical 12V 5mm 300 adet 20x15mm"
                        + " | Urun kodu: yok | Toplam: " + index + " adet");
                default -> {
                    lines.add("Urun Adi: Product-" + index);
                    lines.add("ACIKLAMA: Technical 12V 5mm 300 adet 20x15mm");
                    lines.add("STOK KODU: -");
                    lines.add("ADET: " + index);
                }
            }
        }
        return String.join("\n", lines);
    }

    private MockMultipartFile asPdfUpload(String text) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 7);
                stream.newLineAtOffset(24, 770);
                for (String line : text.lines().toList()) {
                    stream.showText(line);
                    stream.newLineAtOffset(0, -9);
                }
                stream.endText();
            }
            document.save(output);
            return new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", output.toByteArray());
        }
    }
}
