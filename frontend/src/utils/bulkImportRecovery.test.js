import test from 'node:test'
import assert from 'node:assert/strict'

import {
  addManualProductForGap,
  canConfirmBulkPreview,
  formatUnresolvedLocation,
  normalizePreviewPayload,
  unresolvedGaps,
} from './bulkImportRecovery.js'

const gap = {
  id: 'xlsx:EK-1:row:12',
  sourceType: 'XLSX',
  worksheetName: 'EK-1',
  sourceRowStart: 12,
  sourceRowEnd: 12,
  insertionIndex: 1,
  _gapOrdinal: 0,
}

test('normalizes recoverable preview while retaining legacy array support', () => {
  const result = normalizePreviewPayload({
    previewId: 'p1', products: [{ valid: true }], unresolvedRecords: [gap], complete: false,
  })
  assert.equal(result.previewId, 'p1')
  assert.equal(result.products[0].rowNumber, 1)
  assert.equal(result.unresolvedRecords.length, 1)
  assert.equal(normalizePreviewPayload([{ valid: true }]).products.length, 1)
})

test('manual product resolves exactly one gap and is inserted in source order', () => {
  const rows = [
    { productName: 'Önce', quantity: 1, valid: true, _sourceProductIndex: 0 },
    { productName: 'Sonra', quantity: 1, valid: true, _sourceProductIndex: 1 },
  ]
  const result = addManualProductForGap(rows, gap, {
    productCode: '', productName: 'Manuel Ürün', quantity: 3,
  })
  assert.equal(result.error, null)
  assert.deepEqual(result.rows.map((row) => row.productName), ['Önce', 'Manuel Ürün', 'Sonra'])
  assert.equal(result.rows[1].resolvedSourceRecordId, gap.id)
  assert.equal(unresolvedGaps([gap], result.rows).length, 0)
})

test('invalid manual product leaves the gap unresolved', () => {
  const result = addManualProductForGap([], gap, { productName: '', quantity: 0 })
  assert.match(result.error, /Ürün adı boş olamaz/)
  assert.equal(unresolvedGaps([gap], result.rows).length, 1)
})

test('confirmation requires session, resolved gaps, valid rows and source', () => {
  const resolved = addManualProductForGap([], gap, {
    productName: 'Manuel Ürün', quantity: 2,
  }).rows.map((row) => ({ ...row, resolutionType: 'NEW' }))
  assert.equal(canConfirmBulkPreview({ previewId: 'p1', rows: resolved, gaps: [gap], source: 'T3' }), true)
  assert.equal(canConfirmBulkPreview({ previewId: 'p1', rows: [], gaps: [gap], source: 'T3' }), false)
  assert.equal(canConfirmBulkPreview({ previewId: null, rows: resolved, gaps: [gap], source: 'T3' }), false)
  assert.equal(canConfirmBulkPreview({
    previewId: 'p1', rows: resolved.map((row) => ({ ...row, resolutionType: null })), gaps: [gap], source: 'T3',
  }), false)
})

test('formats exact Excel and PDF source locations', () => {
  assert.equal(formatUnresolvedLocation(gap), 'EK-1 · satır 12')
  assert.equal(formatUnresolvedLocation({
    sourceType: 'PDF', pageNumber: 4, sourceRecordStart: 22, sourceRecordEnd: 22,
  }), 'PDF sayfa 4 · kayıt 22')
})

test('consolidated rows retain every resolved source-gap id', () => {
  const secondGap = { ...gap, id: 'xlsx:EK-1:row:13', sourceRowStart: 13, sourceRowEnd: 13 }
  const rows = [{
    productName: 'Aynı Ürün', quantity: 5, valid: true,
    resolvedSourceRecordIds: [gap.id, secondGap.id],
  }]
  assert.equal(unresolvedGaps([gap, secondGap], rows).length, 0)
})
