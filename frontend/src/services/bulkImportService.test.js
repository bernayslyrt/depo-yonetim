import test from 'node:test'
import assert from 'node:assert/strict'

import { cancelBulkImport, matchBulkPreview, parsePreview } from './bulkImportService.js'

test('source changes are sent to backend matching without reparsing the document', async () => {
  globalThis.localStorage = { getItem: () => null }
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, body: JSON.parse(options.body) })
    return { ok: true, json: async () => ({ success: true, data: [] }) }
  }

  const rows = [{
    rowNumber: 1,
    productName: 'Silikon_Tabancası',
    quantity: 15,
    importedQuantity: 15,
    canonicalName: 'silikon tabancası',
    valid: true,
    contributingSourceRecordIds: ['xlsx:Ürünler:row:2'],
    previewItemIds: ['preview-item-1'],
  }]
  await matchBulkPreview(rows, 'Belediye', 'preview-1')
  await matchBulkPreview(rows, 'T3', 'preview-1')

  assert.equal(requests.length, 2)
  assert.equal(requests[0].url, '/api/products/bulk/match-preview')
  assert.equal(requests[0].body.source, 'Belediye')
  assert.equal(requests[1].body.source, 'T3')
  assert.equal(requests[1].body.items[0].productName, 'Silikon_Tabancası')
  assert.equal(requests[0].body.items[0].quantity, 15)
  assert.equal(requests[1].body.items[0].quantity, 15)
  assert.deepEqual(requests[1].body.items[0].contributingSourceRecordIds,
    ['xlsx:Ürünler:row:2'])
  assert.deepEqual(requests[1].body.items[0].previewItemIds, ['preview-item-1'])
  assert.equal(requests[1].body.items[0].importedQuantity, 15)
  assert.equal(requests[1].body.items[0].canonicalName, 'silikon tabancası')
})

test('parse preview carries server job identity and browser cancellation signal', async () => {
  globalThis.localStorage = { getItem: () => null }
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    return { ok: true, json: async () => ({ success: true, data: {} }) }
  }
  const controller = new AbortController()

  await parsePreview(new Blob(['x']), {
    jobId: '11111111-1111-1111-1111-111111111111',
    signal: controller.signal,
  })

  assert.equal(requests[0].url,
    '/api/products/bulk/parse-preview?jobId=11111111-1111-1111-1111-111111111111')
  assert.equal(requests[0].options.signal, controller.signal)
})

test('parse preview exposes the backend generic message instead of a null error', async () => {
  globalThis.localStorage = { getItem: () => null }
  globalThis.fetch = async () => ({
    ok: false,
    status: 500,
    json: async () => ({
      success: false,
      message: 'Belge işlenirken beklenmeyen bir hata oluştu.',
    }),
  })

  await assert.rejects(
    () => parsePreview(new Blob(['x'])),
    (error) => {
      assert.equal(error.message, 'Belge işlenirken beklenmeyen bir hata oluştu.')
      assert.notEqual(error.message, null)
      return true
    }
  )
})

test('explicit cancel identifies both backend job and preview session', async () => {
  globalThis.localStorage = { getItem: () => null }
  const requests = []
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options })
    return { ok: true, json: async () => ({ success: true }) }
  }

  await cancelBulkImport('11111111-1111-1111-1111-111111111111', 'preview-1')

  assert.equal(requests[0].url,
    '/api/products/bulk/jobs/11111111-1111-1111-1111-111111111111/cancel?previewId=preview-1')
  assert.equal(requests[0].options.method, 'POST')
})
