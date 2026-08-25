package com.depo.controller;

import com.depo.dto.ApiResponse;
import com.depo.dto.IslemGecmisiResponse;
import com.depo.dto.IslemSummaryResponse;
import com.depo.service.IslemGecmisiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/islemler")
@RequiredArgsConstructor
public class IslemGecmisiController {

    private final IslemGecmisiService islemGecmisiService;

    /**
     * İşlem geçmişini getirir (ham liste, eskiden beri var).
     * ADMIN: Tüm kayıtlar, STAFF/PERSONEL: Sadece kendi kayıtları.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<IslemGecmisiResponse>>> getIslemGecmisi() {
        List<IslemGecmisiResponse> islemler = islemGecmisiService.getIslemGecmisi();
        return ResponseEntity.ok(ApiResponse.success(islemler, "İşlem geçmişi başarıyla listelendi."));
    }

    /**
     * Birleşik özet listesi: toplu işlemler tek satıra çöküp toplamUrun ile
     * gösterilir.
     * Frontend'in varsayılan olarak kullandığı endpoint.
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<List<IslemSummaryResponse>>> getSummary() {
        List<IslemSummaryResponse> summary = islemGecmisiService.getSummary();
        return ResponseEntity.ok(ApiResponse.success(summary, "Özet işlem geçmişi başarıyla listelendi."));
    }

    /**
     * Belirli bir toplu işlemin (batchId) detay kayıtlarını döner.
     * Rol kontrolü: ADMIN her batchId'yi görebilir, PERSONEL sadece kendi
     * batchId'sini.
     */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<ApiResponse<List<IslemGecmisiResponse>>> getBatchDetail(
            @PathVariable String batchId) {
        List<IslemGecmisiResponse> detail = islemGecmisiService.getByBatchId(batchId);
        return ResponseEntity.ok(ApiResponse.success(detail,
                detail.size() + " kayıt bulundu."));
    }
}
