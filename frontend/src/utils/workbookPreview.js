import * as XLSX from 'xlsx'

export function buildWorkbookPreview(workbook, sheetToJson = XLSX.utils.sheet_to_json) {
  const sheets = (workbook?.SheetNames || []).map((name) => {
    const worksheet = workbook.Sheets[name]
    const values = sheetToJson(worksheet, { header: 1, defval: '', blankrows: true })
    const startRow = worksheet?.['!ref']
      ? XLSX.utils.decode_range(worksheet['!ref']).s.r + 1
      : 1
    return {
      name,
      headers: normalizeRow(values[0]),
      rows: values.slice(1).map(normalizeRow),
      rowNumbers: values.slice(1).map((_, index) => startRow + index + 1),
    }
  })
  return { type: 'workbook', sheets }
}

export function worksheetFromPreview(preview, worksheetName) {
  if (preview?.type !== 'workbook') return null
  return preview.sheets.find((sheet) => sheet.name === worksheetName) || preview.sheets[0] || null
}

export function sourceLocationFromContributionIds(ids) {
  for (const id of ids || []) {
    const match = /^xlsx:(.*):row:(\d+)$/.exec(id || '')
    if (match) return { worksheetName: match[1], rowNumber: Number(match[2]) }
  }
  return null
}

function normalizeRow(row) {
  return Array.isArray(row) ? row.map((cell) => cell == null ? '' : String(cell)) : []
}
