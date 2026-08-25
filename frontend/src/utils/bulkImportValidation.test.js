import test from 'node:test'
import assert from 'node:assert/strict'

import { applyPreviewEdit, validatePreviewRow } from './bulkImportValidation.js'

test('multi-line product-name text is not accepted as a product code', () => {
  const row = validatePreviewRow({
    productCode: 'M3 Vida\nSomun\nSeti',
    productName: 'M3 Vida Somun Seti',
    quantity: 3,
    reviewRequired: false,
  })

  assert.equal(row.valid, false)
  assert.match(row.errorMessage, /kontrol karakteri/)
  assert.match(row.errorMessage, /ürün adından kopyalanamaz/i)
})

test('manual correction revalidates the row and clears its hard error', () => {
  const invalid = validatePreviewRow({
    productCode: 'M3 Vida\nSomun\nSeti',
    productName: 'M3 Vida Somun Seti',
    quantity: 3,
    reviewRequired: false,
  })

  const corrected = applyPreviewEdit(invalid, 'productCode', '')

  assert.equal(corrected.valid, true)
  assert.equal(corrected.errorMessage, null)
})

test('formatting-only name edit invalidates prior match resolution for backend reevaluation', () => {
  const row = {
    productName: 'Pil_Yuvarlak', quantity: 2, valid: true,
    resolutionType: 'EXISTING', selectedProductId: 51, matchFingerprint: 'snapshot',
  }

  const edited = applyPreviewEdit(row, 'productName', 'PİL YUVARLAK')

  assert.equal(edited.resolutionType, null)
  assert.equal(edited.selectedProductId, null)
  assert.equal(edited.matchFingerprint, null)
})
