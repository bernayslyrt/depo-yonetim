/**
 * Toplu Ürün İçe Aktarım API Servisi
 * 
 * Bu servis, mevcut productService'e dokunmadan
 * /api/products/bulk endpoint'leriyle iletişimi sağlar.
 */

const BASE_URL = '/api/products/bulk';

function getAuthHeaders() {
  const token = localStorage.getItem('token');
  const headers = {};
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

/**
 * Yüklenen dosyayı parse eder ve ön izleme listesi döner.
 * DB'ye hiçbir şey yazılmaz.
 * 
 * @param {File} file - Kullanıcının yüklediği dosya (.xlsx, .csv, .pdf, .docx)
 * @returns {Promise<{data: {previewId: string, products: Array, unresolvedRecords: Array, complete: boolean}, message: string, success: boolean}>}
 */
export async function parsePreview(file, { jobId, signal } = {}) {
  const formData = new FormData();
  formData.append('file', file);
  const query = jobId ? `?jobId=${encodeURIComponent(jobId)}` : '';

  const res = await fetch(`${BASE_URL}/parse-preview${query}`, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: formData,
    signal,
  });

  const json = await res.json().catch(() => ({}));

  if (!res.ok) {
    throw new Error(json.message || `Dosya okunamadı (HTTP ${res.status})`);
  }

  return json;
}

/** Explicitly cancels backend work and invalidates any preview created by the job. */
export async function cancelBulkImport(jobId, previewId = null) {
  if (!jobId) return
  const previewQuery = previewId ? `?previewId=${encodeURIComponent(previewId)}` : ''
  const res = await fetch(
    `${BASE_URL}/jobs/${encodeURIComponent(jobId)}/cancel${previewQuery}`,
    { method: 'POST', headers: getAuthHeaders() }
  )
  if (!res.ok) {
    const json = await res.json().catch(() => ({}))
    throw new Error(json.message || `Toplu aktarım iptal edilemedi (HTTP ${res.status})`)
  }
}

function sanitizePreviewItem(item) {
  return {
    rowNumber: item.rowNumber,
    productCode: item.productCode,
    productName: item.productName,
    quantity: item.quantity,
    importedQuantity: item.importedQuantity,
    canonicalName: item.canonicalName,
    rawQuantityText: item.rawQuantityText,
    valid: item.valid,
    errorMessage: item.errorMessage,
    reviewRequired: item.reviewRequired,
    reviewMessage: item.reviewMessage,
    matchReviewRequired: item.matchReviewRequired,
    documentReviewRequired: item.documentReviewRequired,
    documentReviewMessage: item.documentReviewMessage,
    resolutionType: item.resolutionType,
    selectedProductId: item.selectedProductId,
    matchFingerprint: item.matchFingerprint,
    resolvedSourceRecordId: item.resolvedSourceRecordId,
    resolvedSourceRecordIds: item.resolvedSourceRecordIds,
    contributingSourceRecordIds: item.contributingSourceRecordIds,
    previewItemIds: item.previewItemIds,
    sourceIdentityReviewRequired: item.sourceIdentityReviewRequired,
  }
}

export async function matchBulkPreview(items, source, previewId) {
  const res = await fetch(`${BASE_URL}/match-preview`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ previewId, source, items: items.map(sanitizePreviewItem) }),
  })
  const json = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new Error(json.message || `Ürün eşleşmeleri yenilenemedi (HTTP ${res.status})`)
  }
  return json
}

/**
 * Onaylanan ürün listesini veritabanına toplu olarak işler.
 * 
 * @param {Array} items - Onaylanan ProductPreviewDto listesi
 * @param {'Belediye'|'Tubitak'|'T3'} source - Tüm yeni ürünlere atanacak kaynak
 * @param {string} previewId - Backend'in verdiği kısa ömürlü ön izleme oturumu
 * @returns {Promise<{data: string, message: string, success: boolean}>}
 */
export async function confirmBulkImport(items, source, previewId) {
  // Only fields used by the import endpoint are submitted. In particular,
  // document prices are preview-only input and never enter the confirm payload.
  const sanitizedItems = items.map(sanitizePreviewItem);

  const res = await fetch(`${BASE_URL}/confirm`, {
    method: 'POST',
    headers: {
      ...getAuthHeaders(),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ previewId, source, items: sanitizedItems }),
  });

  const json = await res.json().catch(() => ({}));

  if (!res.ok) {
    throw new Error(json.message || `Toplu aktarım başarısız (HTTP ${res.status})`);
  }

  return json;
}
