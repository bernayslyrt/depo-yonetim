import { useState, useCallback, useEffect, useRef, useMemo } from 'react'
import { cancelBulkImport, parsePreview, confirmBulkImport, matchBulkPreview } from '../services/bulkImportService'
import * as XLSX from 'xlsx'
import mammoth from 'mammoth'
import { applyPreviewEdit } from '../utils/bulkImportValidation'
import {
  buildWorkbookPreview,
  sourceLocationFromContributionIds,
  worksheetFromPreview,
} from '../utils/workbookPreview'
import {
  addManualProductForGap,
  canConfirmBulkPreview,
  formatUnresolvedLocation,
  normalizePreviewPayload,
  unresolvedGaps,
} from '../utils/bulkImportRecovery'

// ─── Desteklenen dosya uzantıları ─────────────────────────────────────────────
const ACCEPTED_EXTENSIONS = '.xlsx,.csv,.pdf,.docx'
const ACCEPTED_MIME =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,' +
  'text/csv,' +
  'application/pdf,' +
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

// ─── Aşama sabitleri ─────────────────────────────────────────────────────────
const STEP_UPLOAD = 'upload'
const STEP_PREVIEW = 'preview'
const STEP_SUCCESS = 'success'
const SOURCE_OPTIONS = ['Belediye', 'Tubitak', 'T3']

// ═══════════════════════════════════════════════════════════════════════════════
// Ana Modal Bileşeni
// ═══════════════════════════════════════════════════════════════════════════════

export default function BulkImportModal({ onClose, onImportSuccess }) {
  const [step, setStep] = useState(STEP_UPLOAD)
  const [file, setFile] = useState(null)
  const [fileUrl, setFileUrl] = useState(null) // sol panel PDF/dosya önizleme
  const [previewRows, setPreviewRows] = useState([])
  const [previewId, setPreviewId] = useState(null)
  const [sourceGaps, setSourceGaps] = useState([])
  const [parsing, setParsing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [matching, setMatching] = useState(false)
  const [error, setError] = useState(null)
  const [successMsg, setSuccessMsg] = useState('')
  const [source, setSource] = useState('')
  const fileInputRef = useRef(null)
  const dropZoneRef = useRef(null)
  const [dragOver, setDragOver] = useState(false)
  const [originalFileData, setOriginalFileData] = useState(null)
  const matchRequestRef = useRef(0)
  const activeJobRef = useRef(null)
  const activePreviewRef = useRef(null)
  const parseAbortRef = useRef(null)

  const cancelActiveImport = useCallback(() => {
    parseAbortRef.current?.abort()
    parseAbortRef.current = null
    const jobId = activeJobRef.current
    const activePreviewId = activePreviewRef.current
    activeJobRef.current = null
    activePreviewRef.current = null
    if (jobId) {
      void cancelBulkImport(jobId, activePreviewId).catch((err) => {
        console.warn('Toplu içe aktarım iptal isteği tamamlanamadı:', err)
      })
    }
  }, [])

  useEffect(() => () => cancelActiveImport(), [cancelActiveImport])

  const handleClose = useCallback(() => {
    cancelActiveImport()
    onClose()
  }, [cancelActiveImport, onClose])

  // ── Dosya seçimi ──────────────────────────────────────────────────────────
  const handleFileSelect = useCallback((selectedFile) => {
    if (!selectedFile) return
    setFile(selectedFile)
    setError(null)
    // Dosya önizleme URL'i oluştur (PDF ve görseller için)
    if (fileUrl) URL.revokeObjectURL(fileUrl)
    setFileUrl(URL.createObjectURL(selectedFile))
  }, [fileUrl])

  const handleInputChange = (e) => {
    handleFileSelect(e.target.files?.[0])
  }

  const handleDrop = (e) => {
    e.preventDefault()
    setDragOver(false)
    handleFileSelect(e.dataTransfer.files?.[0])
  }

  // ── Dosya içeriğini sol panel için client-side parse et ────────────────
  const parseFileForLeftPanel = useCallback(async (f) => {
    const ext = f.name.split('.').pop()?.toLowerCase()
    try {
      if (ext === 'csv') {
        const text = await f.text()
        const lines = text.split(/\r?\n/).filter(l => l.trim())
        const delimiter = lines[0]?.includes(';') ? ';' : ','
        const parsed = lines.map(line => line.split(delimiter).map(cell => cell.trim()))
        return { type: 'table', headers: parsed[0] || [], rows: parsed.slice(1) }
      }
      if (ext === 'xlsx') {
        const buffer = await f.arrayBuffer()
        const workbook = XLSX.read(buffer, { type: 'array' })
        return buildWorkbookPreview(workbook)
      }
      if (ext === 'docx') {
        const buffer = await f.arrayBuffer()
        const result = await mammoth.convertToHtml({ arrayBuffer: buffer })
        return { type: 'html', content: result.value }
      }
      if (ext === 'pdf') {
        return { type: 'pdf' }
      }
    } catch (e) {
      console.warn('Sol panel önizleme oluşturulamadı:', e)
    }
    return null
  }, [])

  // ── Parse Preview ─────────────────────────────────────────────────────────
  const handleParsePreview = async () => {
    if (!file) return
    cancelActiveImport()
    const jobId = crypto.randomUUID()
    const abortController = new AbortController()
    activeJobRef.current = jobId
    activePreviewRef.current = null
    parseAbortRef.current = abortController
    setParsing(true)
    setError(null)
    let previewCreated = false
    try {
      const result = await parsePreview(file, { jobId, signal: abortController.signal })
      const preview = normalizePreviewPayload(result.data)
      previewCreated = true
      activePreviewRef.current = preview.previewId
      setPreviewId(preview.previewId)
      setPreviewRows(preview.products)
      setSourceGaps(preview.unresolvedRecords)
      // Sol panel için client-side parse
      const leftPanelData = await parseFileForLeftPanel(file)
      setOriginalFileData(leftPanelData)
      setStep(STEP_PREVIEW)
    } catch (err) {
      if (err.name !== 'AbortError') {
        setError(err.message)
        if (activeJobRef.current === jobId) {
          void cancelBulkImport(jobId).catch((cancelError) => {
            console.warn('Başarısız içe aktarım işi iptal edilemedi:', cancelError)
          })
        }
      }
    } finally {
      if (parseAbortRef.current === abortController) parseAbortRef.current = null
      if (!previewCreated && activeJobRef.current === jobId) {
        activeJobRef.current = null
        activePreviewRef.current = null
      }
      setParsing(false)
    }
  }

  // ── Satır düzenleme ───────────────────────────────────────────────────────
  const refreshMatches = useCallback(async (rows, selectedSource) => {
    if (!selectedSource || !previewId || rows.length === 0) return
    const requestNumber = ++matchRequestRef.current
    setMatching(true)
    setError(null)
    try {
      const result = await matchBulkPreview(rows, selectedSource, previewId)
      if (requestNumber === matchRequestRef.current) setPreviewRows(result.data || [])
    } catch (err) {
      if (requestNumber === matchRequestRef.current) setError(err.message)
    } finally {
      if (requestNumber === matchRequestRef.current) setMatching(false)
    }
  }, [previewId])

  const updateRow = useCallback((rowNumber, field, value) => {
    const nextRows = previewRows.map((row) =>
      row.rowNumber === rowNumber ? applyPreviewEdit(row, field, value) : row
    )
    setPreviewRows(nextRows)
    if (source) refreshMatches(nextRows, source)
  }, [previewRows, refreshMatches, source])

  const handleSourceChange = useCallback((selectedSource) => {
    setSource(selectedSource)
    if (selectedSource) refreshMatches(previewRows, selectedSource)
  }, [previewRows, refreshMatches])

  const deleteRow = useCallback((rowNumber) => {
    setPreviewRows((prev) => prev.filter((row) => row.rowNumber !== rowNumber))
  }, [])

  const resolveSourceGap = useCallback((gap, draft) => {
    const result = addManualProductForGap(previewRows, gap, draft)
    if (!result.error) {
      setPreviewRows(result.rows)
      if (source) refreshMatches(result.rows, source)
    }
    return result.error
  }, [previewRows, refreshMatches, source])

  const resolveMatch = useCallback((rowNumber, resolutionType, productId = null) => {
    setPreviewRows((rows) => rows.map((row) => {
      if (row.rowNumber !== rowNumber) return row
      const candidate = row.matchCandidates?.find((item) => item.productId === productId)
      if (resolutionType === 'EXISTING' && candidate) {
        return {
          ...row,
          matchStatus: 'Mevcut ürün', resolutionType: 'EXISTING', selectedProductId: productId,
          existingStock: candidate.currentStock,
          projectedStock: candidate.currentStock + Number(row.quantity),
          reviewRequired: Boolean(row.documentReviewRequired), matchReviewRequired: false,
          reviewMessage: row.documentReviewMessage || null,
          valid: !row.errorMessage && !row.documentReviewRequired,
        }
      }
      return {
        ...row,
        matchStatus: 'Yeni ürün', resolutionType: 'NEW', selectedProductId: null,
        existingStock: null, projectedStock: null,
        reviewRequired: Boolean(row.documentReviewRequired), matchReviewRequired: false,
        reviewMessage: row.documentReviewMessage || null,
        valid: !row.errorMessage && !row.documentReviewRequired,
      }
    }))
  }, [])

  // ── Onay ──────────────────────────────────────────────────────────────────
  const invalidCount = useMemo(
    () => previewRows.filter((r) => Boolean(r.errorMessage) || (!r.valid && !r.reviewRequired)).length,
    [previewRows]
  )

  const reviewCount = useMemo(
    () => previewRows.filter((r) => r.reviewRequired).length,
    [previewRows]
  )

  const unresolvedRecords = useMemo(
    () => unresolvedGaps(sourceGaps, previewRows),
    [sourceGaps, previewRows]
  )

  const canConfirm = !matching && canConfirmBulkPreview({
    previewId,
    rows: previewRows,
    gaps: sourceGaps,
    source,
  })

  const handleConfirm = async () => {
    if (!canConfirm) return
    setConfirming(true)
    setError(null)
    try {
      const result = await confirmBulkImport(previewRows, source, previewId)
      setSuccessMsg(result.data || 'Toplu içe aktarım başarılı.')
      setStep(STEP_SUCCESS)
      activeJobRef.current = null
      activePreviewRef.current = null
      // Ürün listesini yenile
      if (onImportSuccess) onImportSuccess()
    } catch (err) {
      setError(err.message)
    } finally {
      setConfirming(false)
    }
  }

  // ── Geri dön ──────────────────────────────────────────────────────────────
  const handleBack = () => {
    cancelActiveImport()
    setStep(STEP_UPLOAD)
    setPreviewRows([])
    setPreviewId(null)
    setSourceGaps([])
    setOriginalFileData(null)
    setSource('')
    setError(null)
  }

  // ── Dosya türü bilgisi ────────────────────────────────────────────────────
  const fileExtension = file?.name?.split('.').pop()?.toLowerCase()
  // ═════════════════════════════════════════════════════════════════════════════
  // RENDER
  // ═════════════════════════════════════════════════════════════════════════════

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
      <div
        className={`bg-white rounded-2xl shadow-2xl flex flex-col overflow-hidden transition-all duration-300 ${
          step === STEP_PREVIEW
            ? 'w-full max-w-7xl h-[90vh]'
            : 'w-full max-w-xl'
        }`}
      >
        {/* ── Header ─────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4 shrink-0">
          <div className="flex items-center gap-3">
            {step === STEP_PREVIEW && (
              <button
                onClick={handleBack}
                className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
                title="Geri dön"
              >
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
                </svg>
              </button>
            )}
            <div>
              <h2 className="text-lg font-semibold text-gray-800">
                {step === STEP_UPLOAD && 'Belge ile Toplu Ürün Yükle'}
                {step === STEP_PREVIEW && 'Ön İzleme ve Düzenleme'}
                {step === STEP_SUCCESS && 'İçe Aktarım Tamamlandı'}
              </h2>
              {step === STEP_PREVIEW && (
                <p className="text-xs text-gray-500 mt-0.5">
                  {previewRows.length} satır · {invalidCount > 0 ? (
                    <span className="text-red-500 font-medium">{invalidCount} hatalı satır</span>
                  ) : (
                    <span className="text-emerald-600 font-medium">Tüm satırlar geçerli</span>
                  )}
                  {unresolvedRecords.length > 0 && (
                    <span className="ml-2 text-amber-600 font-medium">
                      · {unresolvedRecords.length} kaynak kaydı tamamlanmalı
                    </span>
                  )}
                </p>
              )}
            </div>
          </div>

          {/* Aşama göstergesi */}
          <div className="flex items-center gap-4">
            {step !== STEP_SUCCESS && (
              <div className="hidden sm:flex items-center gap-2 text-xs">
                <StepIndicator label="Dosya Seç" number={1} active={step === STEP_UPLOAD} completed={step === STEP_PREVIEW} />
                <svg className="h-3 w-3 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                </svg>
                <StepIndicator label="İncele & Düzenle" number={2} active={step === STEP_PREVIEW} completed={false} />
              </div>
            )}
            <button
              onClick={handleClose}
              className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
            >
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* ── Body ───────────────────────────────────────────────────────── */}
        <div className="flex-1 overflow-hidden">
          {step === STEP_UPLOAD && (
            <UploadStep
              file={file}
              fileInputRef={fileInputRef}
              dropZoneRef={dropZoneRef}
              dragOver={dragOver}
              setDragOver={setDragOver}
              onInputChange={handleInputChange}
              onDrop={handleDrop}
              onParsePreview={handleParsePreview}
              parsing={parsing}
              error={error}
            />
          )}

          {step === STEP_PREVIEW && (
            <PreviewStep
              file={file}
              fileUrl={fileUrl}
              fileExtension={fileExtension}
              originalFileData={originalFileData}
              previewRows={previewRows}
              unresolvedRecords={unresolvedRecords}
              onResolveSourceGap={resolveSourceGap}
              source={source}
              onSourceChange={handleSourceChange}
              onUpdateRow={updateRow}
              onResolveMatch={resolveMatch}
              onDeleteRow={deleteRow}
              invalidCount={invalidCount}
              reviewCount={reviewCount}
              canConfirm={canConfirm}
              onConfirm={handleConfirm}
              confirming={confirming}
              matching={matching}
              error={error}
            />
          )}

          {step === STEP_SUCCESS && (
            <SuccessStep message={successMsg} onClose={handleClose} />
          )}
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════════════════════════════════════════════════════════════
// Alt Bileşenler
// ═══════════════════════════════════════════════════════════════════════════════

function StepIndicator({ label, number, active, completed }) {
  const bg = completed
    ? 'bg-emerald-500 text-white'
    : active
    ? 'bg-indigo-600 text-white'
    : 'bg-gray-200 text-gray-500'
  return (
    <div className="flex items-center gap-1.5">
      <span className={`inline-flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold ${bg}`}>
        {completed ? '✓' : number}
      </span>
      <span className={`font-medium ${active ? 'text-indigo-700' : completed ? 'text-emerald-700' : 'text-gray-400'}`}>
        {label}
      </span>
    </div>
  )
}

// ─── Aşama 1: Dosya Yükleme ──────────────────────────────────────────────────
function UploadStep({ file, fileInputRef, dropZoneRef, dragOver, setDragOver, onInputChange, onDrop, onParsePreview, parsing, error }) {
  return (
    <div className="px-6 py-8 space-y-6">
      {/* Hata mesajı */}
      {error && (
        <div className="rounded-xl bg-red-50 p-4 text-sm font-medium text-red-700 border border-red-200">
          {error}
        </div>
      )}

      {/* Drag & Drop alanı */}
      <div
        ref={dropZoneRef}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
        onClick={() => fileInputRef.current?.click()}
        className={`relative cursor-pointer rounded-2xl border-2 border-dashed p-12 text-center transition-all ${
          dragOver
            ? 'border-indigo-400 bg-indigo-50/50 scale-[1.01]'
            : file
            ? 'border-emerald-300 bg-emerald-50/30'
            : 'border-gray-300 hover:border-indigo-300 hover:bg-gray-50/50'
        }`}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept={`${ACCEPTED_EXTENSIONS},${ACCEPTED_MIME}`}
          onChange={onInputChange}
          className="hidden"
        />

        {file ? (
          <div className="space-y-3">
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl bg-emerald-100">
              <svg className="h-8 w-8 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <div>
              <p className="text-sm font-semibold text-gray-800">{file.name}</p>
              <p className="text-xs text-gray-500 mt-1">{formatFileSize(file.size)}</p>
            </div>
            <p className="text-xs text-indigo-500 font-medium">Farklı bir dosya seçmek için tıklayın</p>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl bg-indigo-100">
              <svg className="h-8 w-8 text-indigo-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
              </svg>
            </div>
            <div>
              <p className="text-sm font-semibold text-gray-700">Dosyanızı buraya sürükleyin</p>
              <p className="text-xs text-gray-500 mt-1">veya dosya seçmek için tıklayın</p>
            </div>
            <div className="flex flex-wrap justify-center gap-2 pt-1">
              {['XLSX', 'CSV', 'PDF', 'DOCX'].map((ext) => (
                <span key={ext} className="inline-flex items-center rounded-lg bg-gray-100 px-2.5 py-1 text-[10px] font-bold text-gray-500 uppercase tracking-wide">
                  .{ext}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Alt butonlar */}
      <div className="flex gap-3">
        <button
          onClick={onParsePreview}
          disabled={!file || parsing}
          className="flex-1 inline-flex items-center justify-center gap-2 rounded-xl bg-indigo-600 px-5 py-3 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 active:scale-[0.98] transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {parsing ? (
            <>
              <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
              </svg>
              Dosya okunuyor…
            </>
          ) : (
            <>
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
              İncele
            </>
          )}
        </button>
      </div>
    </div>
  )
}

// ─── Aşama 2: Split-Screen Ön İzleme ────────────────────────────────────────
function PreviewStep({
  file, fileUrl, fileExtension, originalFileData,
  previewRows, unresolvedRecords, onResolveSourceGap,
  source, onSourceChange, onUpdateRow, onResolveMatch, onDeleteRow,
  invalidCount, reviewCount, canConfirm, onConfirm, confirming, matching, error,
}) {
  const workbookSheets = useMemo(
    () => originalFileData?.type === 'workbook' ? originalFileData.sheets : [],
    [originalFileData],
  )
  const [activeWorksheet, setActiveWorksheet] = useState(workbookSheets[0]?.name || '')
  const [sourceJumpRequest, setSourceJumpRequest] = useState(null)
  const sourceScrollRef = useRef(null)
  const worksheetScrollPositions = useRef({})
  const activeSheet = worksheetFromPreview(originalFileData, activeWorksheet)

  useEffect(() => {
    if (workbookSheets.length === 0) return
    if (!workbookSheets.some((sheet) => sheet.name === activeWorksheet)) {
      setActiveWorksheet(workbookSheets[0].name)
    }
  }, [activeWorksheet, workbookSheets])

  useEffect(() => {
    const container = sourceScrollRef.current
    if (!container || !activeWorksheet) return undefined
    const frame = requestAnimationFrame(() => {
      if (sourceJumpRequest?.worksheetName === activeWorksheet) {
        const row = container.querySelector(`[data-source-row="${sourceJumpRequest.rowNumber}"]`)
        row?.scrollIntoView({ block: 'center' })
      } else {
        container.scrollTop = worksheetScrollPositions.current[activeWorksheet] || 0
      }
    })
    return () => cancelAnimationFrame(frame)
  }, [activeWorksheet, sourceJumpRequest])

  const selectWorksheet = (worksheetName) => {
    if (sourceScrollRef.current && activeWorksheet) {
      worksheetScrollPositions.current[activeWorksheet] = sourceScrollRef.current.scrollTop
    }
    setSourceJumpRequest(null)
    setActiveWorksheet(worksheetName)
  }

  const jumpToSource = (location) => {
    if (!location) return
    if (sourceScrollRef.current && activeWorksheet) {
      worksheetScrollPositions.current[activeWorksheet] = sourceScrollRef.current.scrollTop
    }
    setSourceJumpRequest({ ...location, requestId: Date.now() })
    setActiveWorksheet(location.worksheetName)
  }

  return (
    <div className="flex flex-col h-full">
      {/* Hata mesajı */}
      {error && (
        <div className="mx-4 mt-3 rounded-xl bg-red-50 p-3 text-sm font-medium text-red-700 border border-red-200 shrink-0">
          {error}
        </div>
      )}

      {/* Kaynak seçimi */}
      <div className="mx-4 mt-3 flex shrink-0 items-center justify-between gap-4 rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3 shadow-sm">
        <div>
          <label htmlFor="bulk-import-source" className="block text-sm font-semibold text-indigo-950">
            Ürün Kaynağı <span className="text-red-500">*</span>
          </label>
          <p className="mt-0.5 text-xs text-indigo-700">
            Seçilen kaynak bu aktarımdaki tüm yeni ürünlere uygulanır.
          </p>
        </div>
        <select
          id="bulk-import-source"
          value={source}
          onChange={(event) => onSourceChange(event.target.value)}
          required
          aria-required="true"
          className="min-w-48 rounded-xl border border-indigo-300 bg-white px-3 py-2.5 text-sm font-semibold text-gray-800 shadow-sm outline-none transition focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/30"
        >
          <option value="">Kaynak seçin…</option>
          {SOURCE_OPTIONS.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
        {matching && <span className="text-xs font-medium text-indigo-700">Eşleşmeler yenileniyor…</span>}
      </div>

      {/* Split Screen paneller */}
      <div className="flex-1 flex overflow-hidden">
        {/* ── SOL PANEL: Orijinal Belge Önizleme ── */}
        <div className="w-1/2 border-r border-gray-200 flex flex-col">
          <div className="px-4 py-3 bg-gray-50 border-b border-gray-200 shrink-0">
            <div className="flex items-center gap-2">
              <svg className="h-4 w-4 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
              </svg>
              <h3 className="text-sm font-semibold text-gray-700">Orijinal Belge</h3>
              <span className="ml-auto text-[10px] font-bold text-gray-400 bg-gray-200 rounded-md px-2 py-0.5 uppercase">{fileExtension}</span>
            </div>
            <p className="text-xs text-gray-500 mt-1 truncate">{file?.name}</p>
            {workbookSheets.length > 1 && (
              <div className="mt-2 flex gap-1 overflow-x-auto" role="tablist" aria-label="Çalışma sayfaları">
                {workbookSheets.map((sheet) => (
                  <button
                    key={sheet.name}
                    type="button"
                    role="tab"
                    aria-selected={sheet.name === activeWorksheet}
                    onClick={() => selectWorksheet(sheet.name)}
                    className={`shrink-0 rounded-md px-2 py-1 text-[10px] font-bold ${
                      sheet.name === activeWorksheet
                        ? 'bg-indigo-600 text-white'
                        : 'border border-gray-200 bg-white text-gray-600 hover:bg-gray-100'
                    }`}
                  >
                    {sheet.name}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div ref={sourceScrollRef} className="flex-1 overflow-auto bg-gray-100/50">
            {originalFileData?.type === 'pdf' ? (
              <iframe
                src={fileUrl}
                title="Belge Önizleme"
                className="w-full h-full border-0"
              />
            ) : originalFileData?.type === 'table' ? (
              <OriginalDataTable headers={originalFileData.headers} rows={originalFileData.rows} />
            ) : activeSheet ? (
              <OriginalDataTable
                headers={activeSheet.headers}
                rows={activeSheet.rows}
                rowNumbers={activeSheet.rowNumbers}
                highlightedRow={sourceJumpRequest?.worksheetName === activeWorksheet
                  ? sourceJumpRequest.rowNumber
                  : null}
              />
            ) : originalFileData?.type === 'html' ? (
              <div
                className="p-4 text-sm text-gray-700 leading-relaxed [&_table]:w-full [&_table]:border-collapse [&_table]:text-xs [&_td]:border [&_td]:border-gray-200 [&_td]:px-2 [&_td]:py-1 [&_th]:border [&_th]:border-gray-300 [&_th]:bg-gray-100 [&_th]:px-2 [&_th]:py-1.5 [&_th]:text-left [&_th]:font-semibold [&_p]:mb-2 [&_h1]:text-lg [&_h1]:font-bold [&_h1]:mb-3 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:mb-2 [&_ul]:list-disc [&_ul]:pl-5 [&_ol]:list-decimal [&_ol]:pl-5"
                dangerouslySetInnerHTML={{ __html: originalFileData.content }}
              />
            ) : (
              <div className="flex flex-col items-center justify-center h-full text-center p-8">
                <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl bg-white shadow-sm border border-gray-200 mb-4">
                  <FileTypeIcon extension={fileExtension} />
                </div>
                <p className="text-sm font-medium text-gray-600">{file?.name}</p>
                <p className="text-xs text-gray-400 mt-1">{formatFileSize(file?.size)}</p>
                <p className="text-xs text-gray-400 mt-3">
                  Dosya önizlemesi oluşturulamadı.
                  <br />Sağ paneldeki tabloyu inceleyerek doğrulama yapabilirsiniz.
                </p>
              </div>
            )}
          </div>
        </div>

        {/* ── SAĞ PANEL: Düzenlenebilir Tablo ── */}
        <div className="w-1/2 flex flex-col">
          <div className="px-4 py-3 bg-gray-50 border-b border-gray-200 shrink-0">
            <div className="flex items-center gap-2">
              <svg className="h-4 w-4 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 10h18M3 14h18m-9-4v8m-7 0h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
              <h3 className="text-sm font-semibold text-gray-700">Çıkarılan Veriler</h3>
              {invalidCount > 0 && (
                <span className="ml-auto text-[10px] font-bold bg-red-100 text-red-600 rounded-full px-2 py-0.5">
                  {invalidCount} hata
                </span>
              )}
              {reviewCount > 0 && (
                <span className={`${invalidCount === 0 ? 'ml-auto' : ''} text-[10px] font-bold bg-amber-100 text-amber-700 rounded-full px-2 py-0.5`}>
                  {reviewCount} kontrol gerekli
                </span>
              )}
              {unresolvedRecords.length > 0 && (
                <span className={`${invalidCount === 0 && reviewCount === 0 ? 'ml-auto' : ''} text-[10px] font-bold bg-amber-100 text-amber-700 rounded-full px-2 py-0.5`}>
                  {unresolvedRecords.length} eksik kaynak kaydı
                </span>
              )}
            </div>
            <p className="text-xs text-gray-500 mt-1">Hücrelere tıklayarak düzeltme yapabilirsiniz</p>
          </div>

          <div className="flex-1 overflow-auto">
            {unresolvedRecords.length > 0 && (
              <div className="space-y-3 border-b border-amber-200 bg-amber-50/60 p-3">
                <div>
                  <p className="text-xs font-bold text-amber-900">Manuel tamamlanması gereken kaynak kayıtları</p>
                  <p className="mt-0.5 text-[11px] text-amber-800">
                    Başarılı satırlar korunmuştur. Her kayıt için ürünü eklemeden onay verilemez.
                  </p>
                </div>
                {unresolvedRecords.map((gap) => (
                  <UnresolvedRecordCard
                    key={gap.id}
                    gap={gap}
                    onResolve={onResolveSourceGap}
                  />
                ))}
              </div>
            )}
            {previewRows.length === 0 && unresolvedRecords.length === 0 ? (
              <div className="flex items-center justify-center h-full text-sm text-gray-400">
                Belgeden veri çıkarılamadı.
              </div>
            ) : (
              <table className="min-w-full divide-y divide-gray-100 text-sm">
                <thead className="bg-gray-50 sticky top-0 z-10">
                  <tr>
                    <th className="px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-wider text-gray-500 w-10">#</th>
                    <th className="px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-wider text-gray-500">Ürün Kodu</th>
                    <th className="px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-wider text-gray-500">Ürün Adı</th>
                    <th className="px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-wider text-gray-500 w-24">Miktar</th>
                    <th className="px-3 py-2.5 text-left text-[10px] font-bold uppercase tracking-wider text-gray-500 w-56">Durum</th>
                    <th className="px-3 py-2.5 text-center text-[10px] font-bold uppercase tracking-wider text-gray-500 w-10"></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50">
                  {previewRows.map((row) => (
                    <EditableRow
                      key={row.rowNumber}
                      row={row}
                      source={source}
                      onUpdate={onUpdateRow}
                      onResolveMatch={onResolveMatch}
                      onDelete={onDeleteRow}
                      onLocateSource={jumpToSource}
                    />
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>

      {/* ── Alt Toolbar ── */}
      <div className="px-6 py-4 border-t border-gray-200 bg-gray-50 flex items-center justify-between shrink-0">
        <div className="text-xs text-gray-500">
          {previewRows.length} satır toplam
          {invalidCount > 0 && (
            <span className="text-red-500 ml-2">· {invalidCount} satır düzeltilmeli</span>
          )}
          {reviewCount > 0 && (
            <span className="text-amber-600 ml-2">· {reviewCount} ürün adı kontrol edilmeli</span>
          )}
          {unresolvedRecords.length > 0 && (
            <span className="text-amber-600 ml-2">· {unresolvedRecords.length} kaynak kaydı manuel eklenmeli</span>
          )}
          {!source && (
            <span className="text-amber-600 ml-2">· Kaynak seçimi gerekli</span>
          )}
        </div>
        <button
          onClick={onConfirm}
          disabled={!canConfirm || confirming}
          className={`inline-flex items-center gap-2 rounded-xl px-6 py-2.5 text-sm font-semibold shadow-sm transition-all active:scale-[0.98] ${
            canConfirm
              ? 'bg-emerald-600 text-white hover:bg-emerald-700'
              : 'bg-gray-200 text-gray-400 cursor-not-allowed'
          }`}
        >
          {confirming ? (
            <>
              <svg className="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
              </svg>
              İşleniyor…
            </>
          ) : (
            <>
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
              Onayla ve Stokları Güncelle
            </>
          )}
        </button>
      </div>
    </div>
  )
}

function UnresolvedRecordCard({ gap, onResolve }) {
  const [expanded, setExpanded] = useState(false)
  const [draft, setDraft] = useState({ productCode: '', productName: '', quantity: '' })
  const [formError, setFormError] = useState(null)

  const submit = (event) => {
    event.preventDefault()
    const resolutionError = onResolve(gap, draft)
    setFormError(resolutionError)
  }

  return (
    <div className="rounded-xl border border-amber-300 bg-white p-3 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-semibold text-gray-800">{formatUnresolvedLocation(gap)}</p>
          <p className="mt-1 text-[11px] leading-4 text-gray-600">{gap.reason}</p>
          {gap.sourceText && (
            <details className="mt-2">
              <summary className="cursor-pointer text-[10px] font-semibold text-indigo-600">
                Kaynak metni göster
              </summary>
              <pre className="mt-1 max-h-24 overflow-auto whitespace-pre-wrap rounded-lg bg-gray-50 p-2 text-[10px] leading-4 text-gray-600">
                {gap.sourceText}
              </pre>
            </details>
          )}
        </div>
        <button
          type="button"
          onClick={() => { setExpanded((value) => !value); setFormError(null) }}
          className="shrink-0 rounded-lg bg-amber-600 px-3 py-2 text-[11px] font-bold text-white hover:bg-amber-700"
        >
          Ürünü Manuel Ekle
        </button>
      </div>
      {expanded && (
        <form onSubmit={submit} className="mt-3 grid grid-cols-6 gap-2 border-t border-amber-100 pt-3">
          <input
            value={draft.productCode}
            onChange={(event) => setDraft({ ...draft, productCode: event.target.value })}
            placeholder="Ürün kodu (opsiyonel)"
            className="col-span-2 rounded-lg border border-gray-300 px-2 py-2 text-xs outline-none focus:border-indigo-500"
          />
          <input
            value={draft.productName}
            onChange={(event) => setDraft({ ...draft, productName: event.target.value })}
            placeholder="Ürün adı"
            className="col-span-2 rounded-lg border border-gray-300 px-2 py-2 text-xs outline-none focus:border-indigo-500"
          />
          <input
            type="number"
            min="1"
            step="1"
            value={draft.quantity}
            onChange={(event) => setDraft({ ...draft, quantity: event.target.value })}
            placeholder="Miktar"
            className="col-span-1 rounded-lg border border-gray-300 px-2 py-2 text-xs outline-none focus:border-indigo-500"
          />
          <button
            type="submit"
            className="col-span-1 rounded-lg bg-emerald-600 px-2 py-2 text-[11px] font-bold text-white hover:bg-emerald-700"
          >
            Kaydet
          </button>
          {formError && (
            <p className="col-span-6 text-[10px] font-medium text-red-600">{formError}</p>
          )}
        </form>
      )}
    </div>
  )
}

// ─── Düzenlenebilir Tablo Satırı ────────────────────────────────────────────
function EditableRow({ row, source, onUpdate, onResolveMatch, onDelete, onLocateSource }) {
  const isReviewRequired = Boolean(row.reviewRequired)
  const isInvalid = Boolean(row.errorMessage) || (!row.valid && !isReviewRequired)
  const [resolving, setResolving] = useState(false)
  const selectedCandidate = row.matchCandidates?.find(
    (candidate) => candidate.productId === row.selectedProductId
  )
  const sourceLocation = sourceLocationFromContributionIds(row.contributingSourceRecordIds)

  return (
    <tr className={`group transition-colors ${
      isInvalid
        ? 'bg-red-50/60'
        : isReviewRequired
          ? 'bg-amber-50/70'
          : 'hover:bg-indigo-50/30'
    }`}>
      {/* Satır numarası */}
      <td className="px-3 py-2 text-xs text-gray-400 font-mono">{row.rowNumber}</td>

      {/* Ürün kodu */}
      <td className="px-3 py-2">
        <EditableCell
          value={row.productCode || ''}
          onChange={(val) => onUpdate(row.rowNumber, 'productCode', val)}
          className="font-mono text-xs"
        />
      </td>

      {/* Ürün adı */}
      <td className="px-3 py-2">
        <EditableCell
          value={row.productName || ''}
          onChange={(val) => onUpdate(row.rowNumber, 'productName', val)}
          hasError={!row.productName || row.productName.trim() === ''}
        />
      </td>

      {/* Miktar */}
      <td className="px-3 py-2">
        <EditableCell
          value={row.quantity ?? ''}
          onChange={(val) => onUpdate(row.rowNumber, 'quantity', val === '' ? null : Number(val))}
          type="number"
          hasError={row.quantity === null || row.quantity === '' || Number(row.quantity) <= 0}
        />
      </td>

      {/* Durum */}
      <td className="px-3 py-2 text-left">
        <div className="flex flex-col items-start gap-1">
          {isInvalid && (
            <>
              <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-bold text-red-600">
                <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
                </svg>
                Hata
              </span>
              <span className="max-w-56 text-[10px] leading-4 text-red-600">
                ⚠ {row.errorMessage || 'Satırdaki geçersiz alanı düzeltin.'}
              </span>
            </>
          )}
          {isReviewRequired && (
            <>
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700">
                <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
                </svg>
                Kontrol gerekli
              </span>
              <span className="max-w-56 text-[10px] leading-4 text-amber-700">
                {row.reviewMessage || 'Ürün adı manuel olarak kontrol edilmelidir.'}
              </span>
              {row.conflictMessage && (
                <span className="max-w-56 text-[10px] leading-4 text-amber-700">{row.conflictMessage}</span>
              )}
              {row.matchReviewRequired && (
                <button
                  type="button"
                  onClick={() => setResolving((value) => !value)}
                  className="rounded-md border border-amber-300 bg-white px-2 py-1 text-[10px] font-bold text-amber-800 hover:bg-amber-50"
                >
                  {resolving ? 'Kapat' : 'Çöz'}
                </button>
              )}
              {resolving && row.matchReviewRequired && (
                <div className="mt-1 w-64 space-y-1.5 rounded-lg border border-amber-200 bg-white p-2 shadow-sm">
                  {!(row.matchCandidates || []).some((candidate) => candidate.source === source) && (
                    <button
                      type="button"
                      onClick={() => { onResolveMatch(row.rowNumber, 'NEW'); setResolving(false) }}
                      className="w-full rounded-md border border-emerald-200 bg-emerald-50 px-2 py-1.5 text-left text-[10px] font-semibold text-emerald-800 hover:bg-emerald-100"
                    >
                      Yeni ürün olarak oluştur — Kaynak: {source} (önerilen)
                    </button>
                  )}
                  {(row.matchCandidates || [])
                    .filter((candidate, _, candidates) =>
                      !candidates.some((item) => item.source === source) || candidate.source === source
                    )
                    .map((candidate) => (
                    <button
                      key={candidate.productId}
                      type="button"
                      onClick={() => { onResolveMatch(row.rowNumber, 'EXISTING', candidate.productId); setResolving(false) }}
                      className="w-full rounded-md border border-gray-200 px-2 py-1.5 text-left text-[10px] text-gray-700 hover:bg-gray-50"
                    >
                      {formatMatchCandidate(candidate)}
                    </button>
                  ))}
                </div>
              )}
            </>
          )}
          {(isReviewRequired || isInvalid) && sourceLocation && (
            <button
              type="button"
              onClick={() => onLocateSource?.(sourceLocation)}
              className="rounded-md border border-slate-300 bg-white px-2 py-1 text-[10px] font-bold text-slate-700 hover:bg-slate-50"
              title="Kaynak çalışma sayfasındaki fiziksel satıra git"
            >
              {sourceLocation.worksheetName} · satır {sourceLocation.rowNumber}
            </button>
          )}
          {!isInvalid && !isReviewRequired && (
            row.matchStatus === 'Mevcut ürün' ? (
              <>
                <span className="inline-flex items-center gap-1 rounded-full bg-blue-100 px-2 py-0.5 text-[10px] font-bold text-blue-700">Mevcut ürün</span>
                <span className="text-[10px] leading-4 text-blue-700">
                  {selectedCandidate?.source && `${selectedCandidate.source} · `}
                  Mevcut stok: {row.existingStock} · Eklenecek: +{row.quantity} · Yeni stok: {row.projectedStock}
                </span>
              </>
            ) : (
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-600">
                {row.matchStatus || (source ? 'Eşleşme bekleniyor' : 'Kaynak seçin')}
              </span>
            )
          )}
        </div>
      </td>

      {/* Sil butonu */}
      <td className="px-3 py-2 text-center">
        <button
          onClick={() => onDelete(row.rowNumber)}
          title="Satırı kaldır"
          className="rounded-lg p-1 text-gray-300 hover:text-red-500 hover:bg-red-50 transition-colors opacity-0 group-hover:opacity-100"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
          </svg>
        </button>
      </td>
    </tr>
  )
}

function formatMatchCandidate(candidate) {
  return [
    candidate.productName,
    candidate.source || 'Kaynak belirtilmemiş',
    candidate.unit || 'Birim belirtilmemiş',
    candidate.category,
    candidate.shelfLocation ? `Raf ${candidate.shelfLocation}` : null,
    `stok ${candidate.currentStock}`,
  ].filter(Boolean).join(' — ')
}

// ─── Düzenlenebilir Hücre ───────────────────────────────────────────────────
function EditableCell({ value, onChange, type = 'text', step, hasError = false, className = '' }) {
  const [editing, setEditing] = useState(false)
  const [localValue, setLocalValue] = useState(value)
  const inputRef = useRef(null)

  const startEdit = () => {
    setLocalValue(value)
    setEditing(true)
    // Focus input after render
    setTimeout(() => inputRef.current?.focus(), 0)
  }

  const commitEdit = () => {
    setEditing(false)
    if (localValue !== value) {
      onChange(localValue)
    }
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      commitEdit()
    } else if (e.key === 'Escape') {
      setLocalValue(value)
      setEditing(false)
    }
  }

  if (editing) {
    return (
      <input
        ref={inputRef}
        type={type}
        step={step}
        value={localValue}
        onChange={(e) => setLocalValue(type === 'number' ? e.target.value : e.target.value)}
        onBlur={commitEdit}
        onKeyDown={handleKeyDown}
        className={`w-full rounded-lg border-2 border-indigo-400 bg-white px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500/30 ${className}`}
      />
    )
  }

  return (
    <div
      onClick={startEdit}
      title="Düzenlemek için tıklayın"
      className={`cursor-text rounded-lg px-2 py-1 text-sm transition-colors hover:bg-white hover:shadow-sm hover:ring-1 hover:ring-gray-300 ${
        hasError ? 'text-red-600 font-medium' : 'text-gray-700'
      } ${className}`}
    >
      {value === '' || value === null || value === undefined ? (
        <span className="text-gray-300 italic">—</span>
      ) : (
        String(value)
      )}
    </div>
  )
}

// ─── Aşama 3: Başarı Ekranı ──────────────────────────────────────────────────
function SuccessStep({ message, onClose }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 px-8 text-center space-y-6">
      <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-full bg-emerald-100 animate-[scaleIn_0.3s_ease-out]">
        <svg className="h-10 w-10 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
        </svg>
      </div>
      <div>
        <h3 className="text-lg font-semibold text-gray-800 mb-2">Toplu İçe Aktarım Başarılı!</h3>
        <p className="text-sm text-gray-600 max-w-md">{message}</p>
      </div>
      <button
        onClick={onClose}
        className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-8 py-3 text-sm font-semibold text-white hover:bg-indigo-700 active:scale-[0.98] transition-all"
      >
        Kapat
      </button>
    </div>
  )
}

// ─── Orijinal Veri Tablosu (Salt Okunur) ────────────────────────────────────
function OriginalDataTable({ headers, rows, rowNumbers, highlightedRow }) {
  if (!rows || rows.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-gray-400">
        Dosyada görüntülenecek veri bulunamadı.
      </div>
    )
  }

  return (
    <div className="min-h-full">
      <table className="min-w-full text-xs border-collapse">
        {headers && headers.length > 0 && (
          <thead className="sticky top-0 z-10">
            <tr className="bg-slate-200/90 backdrop-blur-sm">
              <th className="px-2.5 py-2 text-left text-[10px] font-bold text-slate-500 uppercase tracking-wider border-b border-slate-300 w-10">#</th>
              {headers.map((h, i) => (
                <th key={i} className="px-2.5 py-2 text-left text-[10px] font-bold text-slate-600 uppercase tracking-wider border-b border-slate-300 whitespace-nowrap">
                  {h || `Sütun ${i + 1}`}
                </th>
              ))}
            </tr>
          </thead>
        )}
        <tbody>
          {rows.map((row, rowIdx) => {
            const physicalRow = rowNumbers?.[rowIdx] ?? rowIdx + 1
            return (
            <tr
              key={rowIdx}
              data-source-row={physicalRow}
              className={physicalRow === highlightedRow
                ? 'bg-amber-100 ring-1 ring-inset ring-amber-400'
                : rowIdx % 2 === 0 ? 'bg-white' : 'bg-slate-50/70'}
            >
              <td className="px-2.5 py-1.5 text-[10px] text-slate-400 font-mono border-b border-slate-100">{physicalRow}</td>
              {(Array.isArray(row) ? row : []).map((cell, cellIdx) => (
                <td key={cellIdx} className="px-2.5 py-1.5 text-slate-600 border-b border-slate-100 whitespace-nowrap max-w-[200px] truncate" title={cell}>
                  {cell || <span className="text-slate-300">—</span>}
                </td>
              ))}
              {headers && Array.isArray(row) && row.length < headers.length &&
                Array.from({ length: headers.length - row.length }).map((_, i) => (
                  <td key={`empty-${i}`} className="px-2.5 py-1.5 border-b border-slate-100">
                    <span className="text-slate-300">—</span>
                  </td>
                ))
              }
            </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

// ─── Dosya Türü İkonu ──────────────────────────────────────────────────────
function FileTypeIcon({ extension }) {
  const colors = {
    xlsx: 'text-emerald-600',
    csv: 'text-blue-600',
    pdf: 'text-red-600',
    docx: 'text-indigo-600',
  }
  return (
    <div className="flex flex-col items-center">
      <svg className={`h-10 w-10 ${colors[extension] || 'text-gray-400'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
      </svg>
      <span className={`text-[10px] font-black uppercase mt-1 ${colors[extension] || 'text-gray-400'}`}>
        .{extension}
      </span>
    </div>
  )
}

// ─── Yardımcı ──────────────────────────────────────────────────────────────
function formatFileSize(bytes) {
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
