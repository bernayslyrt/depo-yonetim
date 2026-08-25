import test from 'node:test'
import assert from 'node:assert/strict'
import * as XLSX from 'xlsx'
import {
  buildWorkbookPreview,
  sourceLocationFromContributionIds,
  worksheetFromPreview,
} from './workbookPreview.js'

test('all five worksheets are available from one client-side parse', () => {
  const workbook = XLSX.utils.book_new()
  for (let index = 1; index <= 5; index += 1) {
    XLSX.utils.book_append_sheet(
      workbook,
      XLSX.utils.aoa_to_sheet([
        ['Malzeme Adı', 'Miktar'],
        [`Ürün-${index}`, index],
      ]),
      `EK-${index}`,
    )
  }
  let sheetReads = 0
  const preview = buildWorkbookPreview(workbook, (sheet, options) => {
    sheetReads += 1
    return XLSX.utils.sheet_to_json(sheet, options)
  })

  assert.deepEqual(preview.sheets.map((sheet) => sheet.name), [
    'EK-1', 'EK-2', 'EK-3', 'EK-4', 'EK-5',
  ])
  assert.equal(worksheetFromPreview(preview, 'EK-1').rows[0][0], 'Ürün-1')
  assert.equal(worksheetFromPreview(preview, 'EK-5').rows[0][0], 'Ürün-5')
  assert.equal(sheetReads, 5, 'switching the selected preview must not parse sheets again')
})

test('trusted Excel contribution ID resolves to its worksheet and physical row', () => {
  assert.deepEqual(
    sourceLocationFromContributionIds(['xlsx:EK-4:row:87']),
    { worksheetName: 'EK-4', rowNumber: 87 },
  )
  assert.equal(sourceLocationFromContributionIds(['pdf:page:2:record:4']), null)
})
