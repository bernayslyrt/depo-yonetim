import { validatePreviewRow } from './bulkImportValidation.js'

export function normalizePreviewPayload(payload) {
  if (Array.isArray(payload)) {
    return {
      previewId: null,
      products: renumberRows(payload.map((row, index) => ({
        ...row,
        _sourceProductIndex: index,
      }))),
      unresolvedRecords: [],
      complete: true,
    }
  }

  const products = Array.isArray(payload?.products) ? payload.products : []
  const unresolvedRecords = Array.isArray(payload?.unresolvedRecords)
    ? payload.unresolvedRecords.map((gap, index) => ({ ...gap, _gapOrdinal: index }))
    : []
  return {
    previewId: payload?.previewId ?? null,
    products: renumberRows(products.map((row, index) => ({
      ...row,
      _sourceProductIndex: index,
    }))),
    unresolvedRecords,
    complete: Boolean(payload?.complete) && unresolvedRecords.length === 0,
  }
}

export function addManualProductForGap(rows, gap, draft) {
  const manualRow = validatePreviewRow({
    productCode: draft.productCode?.trim() || null,
    productName: draft.productName?.trim() || '',
    quantity: draft.quantity === '' || draft.quantity == null ? null : Number(draft.quantity),
    rawQuantityText: draft.quantity == null ? null : String(draft.quantity),
    price: null,
    reviewRequired: false,
    reviewMessage: null,
    resolvedSourceRecordId: gap.id,
    _sourceInsertionIndex: gap.insertionIndex,
    _sourceGapOrdinal: gap._gapOrdinal ?? 0,
  })
  if (!manualRow.valid) {
    return { rows, error: manualRow.errorMessage }
  }
  return { rows: renumberRows([...rows, manualRow]), error: null }
}

export function unresolvedGaps(gaps, rows) {
  const resolved = new Set(
    rows.flatMap((row) => [
      row.resolvedSourceRecordId,
      ...(Array.isArray(row.resolvedSourceRecordIds) ? row.resolvedSourceRecordIds : []),
    ]).filter(Boolean),
  )
  return gaps.filter((gap) => !resolved.has(gap.id))
}

export function canConfirmBulkPreview({ previewId, rows, gaps, source }) {
  return Boolean(previewId)
    && Boolean(source)
    && rows.length > 0
    && unresolvedGaps(gaps, rows).length === 0
    && rows.every((row) => row.valid && !row.errorMessage && !row.reviewRequired && Boolean(row.resolutionType))
}

export function formatUnresolvedLocation(gap) {
  if (gap.sourceType === 'XLSX') {
    const rowLabel = gap.sourceRowStart === gap.sourceRowEnd
      ? `satır ${gap.sourceRowStart}`
      : `satır ${gap.sourceRowStart}-${gap.sourceRowEnd}`
    return `${gap.worksheetName || 'Çalışma sayfası'} · ${rowLabel}`
  }
  if (gap.sourceType === 'PDF') {
    const recordLabel = gap.sourceRecordStart === gap.sourceRecordEnd
      ? `kayıt ${gap.sourceRecordStart}`
      : `kayıt ${gap.sourceRecordStart}-${gap.sourceRecordEnd}`
    return `PDF sayfa ${gap.pageNumber} · ${recordLabel}`
  }
  return 'Kaynak konumu belirlenen kayıt'
}

function compareSourceOrder(left, right) {
  const leftPosition = left._sourceInsertionIndex ?? left._sourceProductIndex ?? Number.MAX_SAFE_INTEGER
  const rightPosition = right._sourceInsertionIndex ?? right._sourceProductIndex ?? Number.MAX_SAFE_INTEGER
  if (leftPosition !== rightPosition) return leftPosition - rightPosition
  const leftManual = left._sourceInsertionIndex != null
  const rightManual = right._sourceInsertionIndex != null
  if (leftManual !== rightManual) return leftManual ? -1 : 1
  return (left._sourceGapOrdinal ?? 0) - (right._sourceGapOrdinal ?? 0)
}

function renumberRows(rows) {
  return [...rows]
    .sort(compareSourceOrder)
    .map((row, index) => ({ ...row, rowNumber: index + 1 }))
}
