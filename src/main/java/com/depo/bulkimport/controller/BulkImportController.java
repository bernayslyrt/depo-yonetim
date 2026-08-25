package com.depo.bulkimport.controller;

import com.depo.bulkimport.dto.BulkConfirmRequestDto;
import com.depo.bulkimport.dto.BulkMatchPreviewRequestDto;
import com.depo.bulkimport.dto.BulkPreviewResponseDto;
import com.depo.bulkimport.dto.ProductPreviewDto;
import com.depo.bulkimport.service.BulkImportCancellationToken;
import com.depo.bulkimport.service.BulkImportCancelledException;
import com.depo.bulkimport.service.BulkImportJobService;
import com.depo.bulkimport.service.BulkProductService;
import com.depo.bulkimport.service.BulkPreviewSessionService;
import com.depo.bulkimport.service.DocumentParsingService;
import com.depo.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Toplu ürün/stok içe aktarım işlemleri için izole REST controller.
 *
 * <p>Endpoint kökü: {@code /api/products/bulk}</p>
 *
 * <p>İş akışı:</p>
 * <ol>
 *   <li>{@code POST /parse-preview} — Dosya yüklenir, parse edilir, ön izleme döner (DB'ye yazılmaz)</li>
 *   <li>{@code POST /confirm} — Kullanıcı düzenleyip onaylar, veriler DB'ye işlenir</li>
 * </ol>
 *
 * <p>Bu controller mevcut proje yapısına dokunmaz; kendi paketi altında izole çalışır.
 * Güvenlik, mevcut SecurityConfig'deki {@code anyRequest().authenticated()} kuralıyla
 * otomatik olarak JWT koruması altındadır.</p>
 */
@RestController
@RequestMapping("/api/products/bulk")
@RequiredArgsConstructor
@Slf4j
public class BulkImportController {

    private final DocumentParsingService documentParsingService;
    private final BulkProductService bulkProductService;
    private final BulkPreviewSessionService bulkPreviewSessionService;
    private final BulkImportJobService bulkImportJobService;

    /**
     * Yüklenen belge dosyasını parse ederek ön izleme listesi döner.
     * Veritabanına hiçbir şey yazılmaz.
     *
     * @param file kullanıcının yüklediği belge dosyası (.xlsx, .csv, .pdf, .docx)
     * @return parse edilen satırların ön izlemesi (doğrulama bilgileriyle birlikte)
     */
    @PostMapping(value = "/parse-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BulkPreviewResponseDto>> parsePreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "jobId", required = false) String requestedJobId) {

        log.info("Dosya ön izleme isteği alındı: {} ({} bytes)",
                file.getOriginalFilename(), file.getSize());

        // Boş dosya kontrolü
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Yüklenen dosya boş. Lütfen geçerli bir dosya seçin."));
        }

        String jobId = requestedJobId == null || requestedJobId.isBlank()
                ? bulkImportJobService.newJobId()
                : requestedJobId;
        BulkImportCancellationToken cancellationToken = bulkImportJobService.start(jobId);
        String createdPreviewId = null;
        try {
            long requestStartedNanos = System.nanoTime();
            BulkPreviewResponseDto parsedPreview = documentParsingService.parseFileWithRecovery(
                    file, cancellationToken);
            cancellationToken.throwIfCancelled();
            long parsingMillis = elapsedMillis(requestStartedNanos);
            long consolidationStartedNanos = System.nanoTime();
            List<ProductPreviewDto> logicalProducts =
                    bulkProductService.preparePreview(parsedPreview.getProducts());
            long consolidationMillis = elapsedMillis(consolidationStartedNanos);
            long sessionStartedNanos = System.nanoTime();
            BulkPreviewResponseDto preview = bulkPreviewSessionService.register(BulkPreviewResponseDto.builder()
                    .products(logicalProducts)
                    .unresolvedRecords(parsedPreview.getUnresolvedRecords())
                    .complete(parsedPreview.isComplete())
                    .build());
            createdPreviewId = preview.getPreviewId();
            bulkImportJobService.bindPreview(cancellationToken, createdPreviewId);
            cancellationToken.throwIfCancelled();
            long sessionMillis = elapsedMillis(sessionStartedNanos);

            log.info("Dosya başarıyla parse edildi. Toplam satır: {}, çözülemeyen kayıt: {}",
                    preview.getProducts().size(), preview.getUnresolvedRecords().size());
            long finalReviewRequiredRows = preview.getProducts().stream()
                    .filter(ProductPreviewDto::isReviewRequired)
                    .count();
            log.info("BULK_IMPORT_DIAGNOSTICS|stage=PREVIEW|jobId={}|parsingMs={}|"
                            + "initialConsolidationMs={}|productMatchingMs=0|previewSessionMs={}|"
                            + "reviewRequiredRows={}|unresolvedRows={}|totalElapsedMs={}",
                    jobId, parsingMillis, consolidationMillis, sessionMillis,
                    finalReviewRequiredRows, preview.getUnresolvedRecords().size(),
                    elapsedMillis(requestStartedNanos));
            return ResponseEntity.ok(
                    ApiResponse.success(preview,
                            "Dosya başarıyla okundu. " + preview.getProducts().size()
                                    + " satır bulundu, " + preview.getUnresolvedRecords().size()
                                    + " kayıt manuel tamamlama bekliyor."));

        } catch (BulkImportCancelledException e) {
            bulkPreviewSessionService.invalidate(createdPreviewId);
            return ResponseEntity.status(409)
                    .body(ApiResponse.error("Toplu içe aktarım iptal edildi."));

        } catch (IllegalArgumentException e) {
            // Desteklenmeyen format
            log.warn("Desteklenmeyen dosya formatı: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));

        } catch (RuntimeException e) {
            String boundPreviewId = bulkImportJobService.fail(cancellationToken);
            bulkPreviewSessionService.invalidate(createdPreviewId);
            if (boundPreviewId != null && !boundPreviewId.equals(createdPreviewId)) {
                bulkPreviewSessionService.invalidate(boundPreviewId);
            }
            log.error("Dosya okuma hatası: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Belge işlenirken beklenmeyen bir hata oluştu."));
        } finally {
            bulkImportJobService.finish(cancellationToken);
        }
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<ApiResponse<String>> cancelImport(
            @PathVariable String jobId,
            @RequestParam(value = "previewId", required = false) String previewId) {
        BulkImportJobService.CancelResult result = bulkImportJobService.cancel(jobId);
        bulkPreviewSessionService.invalidate(result.previewId());
        bulkPreviewSessionService.invalidate(previewId);
        return ResponseEntity.ok(ApiResponse.success("Toplu içe aktarım iptal edildi."));
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    @PostMapping("/match-preview")
    public ResponseEntity<ApiResponse<List<ProductPreviewDto>>> matchPreview(
            @Valid @RequestBody BulkMatchPreviewRequestDto request) {
        try {
            long requestStartedNanos = System.nanoTime();
            List<ProductPreviewDto> restoredItems = bulkPreviewSessionService.restoreForMatching(
                    request.getPreviewId(), request.getItems());
            long restoreMillis = elapsedMillis(requestStartedNanos);
            long matchingStartedNanos = System.nanoTime();
            List<ProductPreviewDto> products =
                    bulkProductService.preparePreview(restoredItems, request.getSource());
            long reviewRequiredRows = products.stream().filter(ProductPreviewDto::isReviewRequired).count();
            log.info("BULK_IMPORT_DIAGNOSTICS|stage=MATCH_PREVIEW|previewId={}|source={}|"
                            + "restoreMs={}|productMatchingMs={}|reviewRequiredRows={}|totalElapsedMs={}",
                    request.getPreviewId(), request.getSource(), restoreMillis,
                    elapsedMillis(matchingStartedNanos), reviewRequiredRows,
                    elapsedMillis(requestStartedNanos));
            return ResponseEntity.ok(ApiResponse.success(products));
        } catch (IllegalArgumentException exception) {
            log.warn("Toplu içe aktarım eşleşme doğrulama hatası: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(exception.getMessage()));
        }
    }

    /**
     * Kullanıcının düzenleyip onayladığı ürün listesini veritabanına toplu olarak işler.
     *
     * @param request onaylanan ürün listesini içeren istek DTO'su
     * @return işlem sonucu özet mesajı
     */
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<String>> confirmBulkImport(
            @Valid @RequestBody BulkConfirmRequestDto request) {

        log.info("Toplu içe aktarım onay isteği alındı. Ürün sayısı: {}, Kaynak: {}",
                request.getItems().size(), request.getSource());

        boolean sessionLocked = false;
        try {
            List<ProductPreviewDto> restoredItems = bulkPreviewSessionService.beginConfirmation(
                    request.getPreviewId(), request.getItems());
            sessionLocked = true;
            BulkProductService.BulkImportResult result =
                    bulkProductService.confirmBulkImport(restoredItems, request.getSource());
            bulkPreviewSessionService.completeConfirmation(request.getPreviewId());
            sessionLocked = false;

            log.info("Toplu içe aktarım tamamlandı: {}", result.toSummaryMessage());
            return ResponseEntity.ok(
                    ApiResponse.success(result.toSummaryMessage()));

        } catch (IllegalArgumentException e) {
            if (sessionLocked) {
                bulkPreviewSessionService.releaseConfirmation(request.getPreviewId());
            }
            log.warn("Toplu içe aktarım doğrulama hatası: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));

        } catch (RuntimeException e) {
            if (sessionLocked) {
                bulkPreviewSessionService.releaseConfirmation(request.getPreviewId());
            }
            log.error("Toplu içe aktarım sırasında hata oluştu: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(
                            "Toplu içe aktarım sırasında bir hata oluştu. İşlem geri alındı: " + e.getMessage()));
        }
    }
}
