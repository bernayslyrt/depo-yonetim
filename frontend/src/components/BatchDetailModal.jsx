import { useState, useEffect } from 'react'
import { islemGecmisiService } from '../services/productService'
import jsPDF from 'jspdf'
import autoTable from 'jspdf-autotable'
import * as XLSX from 'xlsx'
import logoSrc from '../assets/logo.png?inline'
import { RobotoRegularBase64 as fontBase64 } from '../assets/fonts/vfs_fonts'
import { getBatchOperationInfo } from '../utils/batchOperationInfo'

function formatDate(dateStr) {
  if (!dateStr) return '—'
  const d = new Date(dateStr)
  return d.toLocaleDateString('tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

function getRowOperationInfo(row, fallbackOperationType) {
  return getBatchOperationInfo(row?.islemTipi ?? fallbackOperationType)
}

function formatRowQuantity(row, fallbackOperationType, emptyValue = '—') {
  if (row?.miktar == null) return emptyValue
  const rowTipInfo = getRowOperationInfo(row, fallbackOperationType)
  return `${rowTipInfo.quantityPrefix}${row.miktar}`
}

/**
 * Toplu işlem detay modalı.
 * Açıldığında batchId'ye ait tüm ürün kayıtlarını API'den çeker,
 * tablo olarak gösterir ve PDF/Excel dışa aktarımı sunar.
 *
 * Props:
 *   batchId  {string}   - UUID
 *   summary  {object}   - Özet satırı verisi (tarih, islemTipi, toplamUrun, kullanici vb.)
 *   onClose  {function} - Modal kapatma callback'i
 */
export default function BatchDetailModal({ batchId, summary, onClose }) {
  const [rows, setRows]     = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError]   = useState(null)
  const [exportingPdf, setExportingPdf]   = useState(false)
  const [exportingXlsx, setExportingXlsx] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    islemGecmisiService.getBatchDetail(batchId)
      .then((data) => { if (!cancelled) { setRows(data || []); setLoading(false) } })
      .catch((err) => { if (!cancelled) { setError(err.message); setLoading(false) } })
    return () => { cancelled = true }
  }, [batchId])

  // ── Helpers ─────────────────────────────────────────────────────────────
  const operationType = summary?.islemTipi ?? rows[0]?.islemTipi
  const tipInfo = getBatchOperationInfo(operationType)

  const exportFilenameBase = () => {
    const date = new Date().toISOString().slice(0, 10)
    return `toplu_islem_${date}_${batchId.slice(0, 8)}`
  }

  // ── PDF Export ───────────────────────────────────────────────────────────
  function exportToPDF() {
    setExportingPdf(true)
    try {
      const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
      const pageW = doc.internal.pageSize.getWidth()
      const pageH = doc.internal.pageSize.getHeight()

      // ── 1. Turkish-compatible font (Roboto) ───────────────────────────
      // The complete TTF is bundled as Base64; PDF export never needs the network.
      doc.addFileToVFS('Roboto-Regular.ttf', fontBase64)
      doc.addFont('Roboto-Regular.ttf', 'Roboto', 'normal')
      doc.setFont('Roboto', 'normal')

      // ── 2. Logo (top-right corner) ────────────────────────────────────
      try {
        // The ?inline import keeps the logo available as a data URL offline.
        doc.addImage(logoSrc, 'PNG', pageW - 40, 7, 28, 28)
      } catch {
        // Logo rendering failed — continue without it.
      }

      // ── 3. Header text ────────────────────────────────────────────────
      const userName = summary?.kullaniciFullName || summary?.kullaniciAdi || '—'
      const recipientName = summary?.recipientName || rows.find((row) => row.recipientName)?.recipientName
      const description = summary?.aciklama || rows.find((row) => row.aciklama)?.aciklama

      doc.setFont('Roboto', 'normal')
      doc.setFontSize(16)
      doc.setTextColor(25, 25, 55)
      doc.text('Toplu İşlem Detayı', 14, 17)

      doc.setFontSize(9)
      doc.setTextColor(70, 70, 100)
      const headerLines = [
        `İşlem Tarihi  : ${formatDate(summary?.tarihSaat)}`,
        `İşlem Tipi    : ${tipInfo.text}`,
        `İşlemi Yapan  : ${userName}`,
      ]
      if (recipientName?.trim()) headerLines.push(`Teslim Alan    : ${recipientName.trim()}`)
      if (description?.trim()) headerLines.push(`Açıklama       : ${description.trim()}`)
      headerLines.push(`Toplam Ürün   : ${rows.length} adet`)

      let headerY = 27
      headerLines.forEach((line) => {
        const wrappedLines = doc.splitTextToSize(line, pageW - 70)
        doc.text(wrappedLines, 14, headerY)
        headerY += wrappedLines.length * 5.5 + 0.5
      })

      // Separator line
      const separatorY = headerY + 0.5
      doc.setDrawColor(180, 180, 210)
      doc.setLineWidth(0.35)
      doc.line(14, separatorY, pageW - 14, separatorY)

      // ── 4. Data table ─────────────────────────────────────────────────
      autoTable(doc, {
        startY: separatorY + 5,
        head: [['#', 'Ürün Adı', 'Miktar', 'Açıklama', 'Tarih']],
        body: rows.map((r, i) => [
          i + 1,
          r.urunAdi || '—',
          formatRowQuantity(r, operationType),
          r.aciklama || '—',
          formatDate(r.tarihSaat),
        ]),
        headStyles: {
          fillColor: tipInfo.pdfHeadColor,
          textColor: 255,
          font: 'Roboto',
          fontStyle: 'normal',
          fontSize: 9,
        },
        alternateRowStyles: { fillColor: [248, 248, 255] },
        styles: {
          font: 'Roboto',
          fontStyle: 'normal',
          fontSize: 9,
          cellPadding: 3,
        },
        columnStyles: {
          0: { cellWidth: 10, halign: 'center' },
          2: { cellWidth: 22, halign: 'center' },
          4: { cellWidth: 44 },
        },
      })

      // ── 5. Footer: page numbers ────────────────────────────────────────
      const totalPages = doc.internal.getNumberOfPages()
      for (let pg = 1; pg <= totalPages; pg++) {
        doc.setPage(pg)
        doc.setFont('Roboto', 'normal')
        doc.setFontSize(7)
        doc.setTextColor(150, 150, 175)
        doc.text(
          `Sayfa ${pg} / ${totalPages}  ·  Bilim Samsun Depo Yönetimi`,
          14,
          pageH - 6
        )
      }

      doc.save(`${exportFilenameBase()}.pdf`)
    } catch (err) {
      console.error('PDF export hatası:', err)
    } finally {
      setExportingPdf(false)
    }
  }

  // ── Excel Export ─────────────────────────────────────────────────────────
  function handleExportExcel() {
    setExportingXlsx(true)
    try {
      const sheetData = [
        ['#', 'Ürün Adı', 'Miktar', 'Açıklama', 'Tarih'],
        ...rows.map((r, i) => [
          i + 1,
          r.urunAdi || '',
          formatRowQuantity(r, operationType, ''),
          r.aciklama || '',
          formatDate(r.tarihSaat),
        ]),
      ]
      const ws = XLSX.utils.aoa_to_sheet(sheetData)
      // Column widths
      ws['!cols'] = [{ wch: 4 }, { wch: 35 }, { wch: 10 }, { wch: 50 }, { wch: 18 }]
      const wb = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(wb, ws, 'Detay')
      XLSX.writeFile(wb, `${exportFilenameBase()}.xlsx`)
    } finally {
      setExportingXlsx(false)
    }
  }

  // ── Backdrop click ───────────────────────────────────────────────────────
  function handleBackdrop(e) {
    if (e.target === e.currentTarget) onClose()
  }

  // ── Render ───────────────────────────────────────────────────────────────
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4"
      onClick={handleBackdrop}
    >
      <div className="w-full max-w-4xl max-h-[90vh] flex flex-col rounded-2xl bg-slate-900 border border-slate-700 shadow-2xl overflow-hidden">

        {/* ── Header ── */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-700 bg-slate-800/60">
          <div className="flex items-center gap-3">
            <div className={`flex h-10 w-10 items-center justify-center rounded-xl text-2xl ${tipInfo.iconColor}`}>
              {tipInfo.icon}
            </div>
            <div>
              <h2 className="text-base font-bold text-slate-100">Toplu İşlem Detayı</h2>
              <p className="text-xs text-slate-400 mt-0.5">
                {formatDate(summary?.tarihSaat)}
                {summary?.kullaniciFullName && (
                  <span className="ml-2 text-slate-500">· {summary.kullaniciFullName}</span>
                )}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* Badge */}
            <span className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-semibold ${tipInfo.modalBadgeColor}`}>
              {tipInfo.icon} {tipInfo.text}
            </span>
            {/* Ürün sayısı */}
            <span className="rounded-lg bg-slate-700 px-2.5 py-1 text-xs font-semibold text-slate-300">
              {summary?.toplamUrun ?? rows.length} ürün
            </span>
            {/* Close */}
            <button
              onClick={onClose}
              className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 hover:bg-slate-700 hover:text-slate-100 transition-colors"
            >
              ✕
            </button>
          </div>
        </div>

        {/* ── Body ── */}
        <div className="flex-1 overflow-auto">
          {loading ? (
            <div className="flex flex-col items-center justify-center py-20 gap-3">
              <svg className="animate-spin h-8 w-8 text-violet-500" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
              </svg>
              <span className="text-sm text-slate-400">Detaylar yükleniyor…</span>
            </div>
          ) : error ? (
            <div className="m-6 rounded-xl bg-red-500/10 border border-red-500/30 px-4 py-3 text-sm text-red-400">
              ⚠️ {error}
            </div>
          ) : rows.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-slate-500">
              <span className="text-4xl mb-2">📭</span>
              <span className="text-sm">Bu toplu işlem için kayıt bulunamadı.</span>
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-slate-800 z-10">
                <tr className="border-b border-slate-700">
                  <th className="text-left px-5 py-3 font-semibold text-slate-400 text-xs uppercase tracking-wide">#</th>
                  <th className="text-left px-5 py-3 font-semibold text-slate-400 text-xs uppercase tracking-wide">Ürün Adı</th>
                  <th className="text-right px-5 py-3 font-semibold text-slate-400 text-xs uppercase tracking-wide">Miktar</th>
                  <th className="text-left px-5 py-3 font-semibold text-slate-400 text-xs uppercase tracking-wide">Açıklama</th>
                  <th className="text-left px-5 py-3 font-semibold text-slate-400 text-xs uppercase tracking-wide">Tarih</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                {rows.map((row, idx) => {
                  const rowTipInfo = getRowOperationInfo(row, operationType)
                  return (
                    <tr
                      key={row.id ?? idx}
                      className="hover:bg-slate-800/50 transition-colors group"
                    >
                      <td className="px-5 py-3 text-slate-500 tabular-nums">{idx + 1}</td>
                      <td className="px-5 py-3 font-medium text-slate-200">{row.urunAdi || '—'}</td>
                      <td className="px-5 py-3 text-right">
                        <span className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-bold ${rowTipInfo.quantityColor}`}>
                          {formatRowQuantity(row, operationType)}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-slate-400 max-w-xs truncate">{row.aciklama || '—'}</td>
                      <td className="px-5 py-3 text-slate-500 whitespace-nowrap text-xs">{formatDate(row.tarihSaat)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* ── Footer — Export buttons ── */}
        {!loading && !error && rows.length > 0 && (
          <div className="flex items-center justify-between gap-3 px-6 py-4 border-t border-slate-700 bg-slate-800/40">
            <p className="text-xs text-slate-500">
              Batch ID: <span className="font-mono text-slate-400">{batchId.slice(0, 8)}…</span>
            </p>
            <div className="flex items-center gap-2">
              <button
                onClick={handleExportExcel}
                disabled={exportingXlsx || exportingPdf}
                className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-emerald-700 active:scale-95 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                {exportingXlsx ? 'İndiriliyor…' : 'Excel İndir'}
              </button>
              <button
                onClick={exportToPDF}
                disabled={exportingPdf || exportingXlsx}
                className="inline-flex items-center gap-2 rounded-xl bg-red-600 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-red-700 active:scale-95 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
                {exportingPdf ? 'İndiriliyor…' : 'PDF İndir'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
