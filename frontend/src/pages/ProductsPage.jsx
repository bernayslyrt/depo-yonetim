import { useState, useEffect, useMemo } from 'react'
import { productService } from '../services/productService'
import BulkImportModal from '../components/BulkImportModal'
import { useAuth } from '../contexts/AuthContext'
import jsPDF from 'jspdf'
import autoTable from 'jspdf-autotable'
import logoSrc from '../assets/logo.png?inline'
import { RobotoRegularBase64 as fontBase64 } from '../assets/fonts/vfs_fonts'

// ─── Helpers ─────────────────────────────────────────────────────────────────

const SOURCE_COLORS = {
  Belediye: 'bg-blue-100 text-blue-800 ring-1 ring-blue-300',
  Tubitak: 'bg-purple-100 text-purple-800 ring-1 ring-purple-300',
  TÜBİTAK: 'bg-purple-100 text-purple-800 ring-1 ring-purple-300',
  T3: 'bg-amber-100 text-amber-800 ring-1 ring-amber-300',
  default: 'bg-gray-100 text-gray-700 ring-1 ring-gray-300',
}

function SourceBadge({ source }) {
  if (!source) return <span className="text-gray-400 text-sm italic">—</span>
  const cls = SOURCE_COLORS[source] ?? SOURCE_COLORS.default
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${cls}`}>
      {source}
    </span>
  )
}

function StockPill({ quantity, minStockLevel }) {
  const isCritical = quantity <= minStockLevel
  const isLow = quantity <= minStockLevel * 2
  const cls = isCritical
    ? 'bg-red-100 text-red-700 ring-1 ring-red-300'
    : isLow
    ? 'bg-yellow-100 text-yellow-700 ring-1 ring-yellow-300'
    : 'bg-emerald-100 text-emerald-700 ring-1 ring-emerald-300'
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${cls}`}>
      {isCritical && <span className="h-1.5 w-1.5 rounded-full bg-red-500 animate-pulse" />}
      {quantity}
    </span>
  )
}

// Stock status filter options — mirrors StockPill color logic
const STOCK_STATUS_OPTIONS = [
  { value: 'Tümü',   label: 'Stok Durumu: TÜMÜ' },
  { value: 'Kritik', label: '🔴 Kritik Seviye' },
  { value: 'Az',     label: '🟡 Az Seviye' },
  { value: 'İyi',    label: '🟢 İyi Seviye' },
]

const SOURCES = ['Tümü', 'Belediye', 'Tubitak', 'T3']

function normalizeSource(source) {
  return source === 'TÜBİTAK' ? 'Tubitak' : source
}

// ─── Modal ────────────────────────────────────────────────────────────────────

function Modal({ title, onClose, children }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="w-full max-w-lg rounded-2xl bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b border-gray-100 px-6 py-4">
          <h2 className="text-lg font-semibold text-gray-800">{title}</h2>
          <button onClick={onClose} className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors">
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>
        <div className="px-6 py-5">{children}</div>
      </div>
    </div>
  )
}

function ProductForm({ initial = {}, categories, onSubmit, onClose, loading, error }) {
  const [form, setForm] = useState({
    name: initial.name ?? '',
    categoryId: initial.category?.id ?? '',
    source: initial.source ?? '',
    quantity: initial.quantity ?? 0,
    unit: initial.unit ?? 'Adet',
    minStockLevel: initial.minStockLevel ?? 5,
    shelfLocation: initial.shelfLocation ?? '',
  })

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))

  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(form) }} className="space-y-4">
      {error && (
        <div className="rounded-lg bg-red-50 p-3 text-xs font-medium text-red-700 border border-red-200">
          {error}
        </div>
      )}
      <div className="grid grid-cols-2 gap-4">
        <div className="col-span-2">
          <label className="block text-sm font-medium text-gray-700 mb-1">Ürün Adı *</label>
          <input required value={form.name} onChange={set('name')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" placeholder="Ürün adını girin" />
        </div>
        <div className="col-span-2">
          <label className="block text-sm font-medium text-gray-700 mb-1">Birim</label>
          <input value={form.unit} onChange={set('unit')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" placeholder="Adet" />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Kategori</label>
          <select value={form.categoryId} onChange={set('categoryId')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
            <option value="">Kategori seçin</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Kaynak</label>
          <select value={form.source} onChange={set('source')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
            <option value="">Kaynak seçin</option>
            {SOURCES.slice(1).map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Miktar</label>
          <input type="number" min="0" value={form.quantity} onChange={set('quantity')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">Min. Stok Seviyesi</label>
          <input type="number" min="0" value={form.minStockLevel} onChange={set('minStockLevel')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
        </div>
        <div className="col-span-2">
          <label className="block text-sm font-medium text-gray-700 mb-1">Raf Konumu</label>
          <input value={form.shelfLocation} onChange={set('shelfLocation')} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" placeholder="A-01" />
        </div>
      </div>
      <div className="flex gap-3 pt-2">
        <button type="button" onClick={onClose} className="flex-1 rounded-lg border border-gray-300 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors">İptal</button>
        <button type="submit" disabled={loading} className="flex-1 rounded-lg bg-indigo-600 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50 transition-colors">
          {loading ? 'Kaydediliyor…' : 'Kaydet'}
        </button>
      </div>
    </form>
  )
}

// ─── Main Component ───────────────────────────────────────────────────────────

// categories is now passed down from App.jsx (shared, always-fresh)
// cartItems is passed for optimistic UI stock preview
export default function ProductsPage({ onAddToCart, refreshTrigger, categories = [], cartItems = [] }) {
  const { user } = useAuth()
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [errorMessage, setErrorMessage] = useState(null)
  const [successMessage, setSuccessMessage] = useState(null)

  // Filters
  const [search, setSearch] = useState('')
  const [filterCategory, setFilterCategory] = useState('Tümü')
  const [filterSource, setFilterSource] = useState('Tümü')
  const [filterStock, setFilterStock] = useState('Tümü') // 'Tümü' | 'Kritik' | 'Az' | 'İyi'

  // Modal state
  const [modal, setModal] = useState(null) // null | 'create' | { mode: 'edit', product }
  const [formError, setFormError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [bulkImportOpen, setBulkImportOpen] = useState(false)
  const [exportingExcel, setExportingExcel] = useState(false)
  const [exportingPdf, setExportingPdf] = useState(false)

  // ── Initial Fetch & Refresh Trigger ───────────────────────────────────────
  const loadData = async () => {
    setLoading(true)
    setErrorMessage(null)
    try {
      const productList = await productService.getAllProducts()
      setProducts(productList || [])
    } catch (err) {
      setErrorMessage(err.message || 'Veriler yüklenirken bir hata oluştu.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadData()
  }, [refreshTrigger])

  const flashSuccess = (msg) => {
    setSuccessMessage(msg)
    setTimeout(() => setSuccessMessage(null), 4000)
  }

  // ── Derived list ──────────────────────────────────────────────────────────
  const filteredProducts = useMemo(() => {
    return products.filter((p) => {
      const matchSearch =
        p.name.toLowerCase().includes(search.toLowerCase()) ||
        (p.code ?? '').toLowerCase().includes(search.toLowerCase())
      const matchCategory = filterCategory === 'Tümü' || p.category?.name === filterCategory
      const matchSource = filterSource === 'Tümü' || normalizeSource(p.source) === filterSource
      // Stock status filtering — mirrors StockPill color logic
      let matchStock = true
      if (filterStock !== 'Tümü') {
        const isCritical = p.quantity <= p.minStockLevel
        const isLow = p.quantity <= p.minStockLevel * 2
        if (filterStock === 'Kritik') matchStock = isCritical
        else if (filterStock === 'Az')    matchStock = !isCritical && isLow
        else if (filterStock === 'İyi')   matchStock = !isCritical && !isLow
      }
      return matchSearch && matchCategory && matchSource && matchStock
    })
  }, [products, search, filterCategory, filterSource, filterStock])

  // ── Optimistic pending-change map ───────────────────────────────────────────
  // Cart now stores ONE entry per product with a pre-computed netChange.
  // No aggregation needed — just index by productId directly.
  const pendingMap = useMemo(() => {
    const map = {}
    for (const item of cartItems) {
      map[item.productId] = item.netChange
    }
    return map
  }, [cartItems])

  // ── Actions ───────────────────────────────────────────────────────────────
  async function handleCreate(form) {
    setSaving(true)
    setFormError(null)
    try {
      const payload = {
        name: form.name,
        code: form.code || null,
        categoryId: form.categoryId ? Number(form.categoryId) : null,
        source: form.source || null,
        quantity: Number(form.quantity),
        unit: form.unit || 'Adet',
        minStockLevel: Number(form.minStockLevel),
        shelfLocation: form.shelfLocation || null,
      }
      await productService.createProduct(payload)
      setModal(null)
      flashSuccess('Ürün başarıyla oluşturuldu.')
      await loadData()
    } catch (err) {
      setFormError(err.message || 'Ürün oluşturulamadı.')
    } finally {
      setSaving(false)
    }
  }

  async function handleUpdate(form) {
    setSaving(true)
    setFormError(null)
    try {
      const payload = {
        name: form.name,
        code: form.code || null,
        categoryId: form.categoryId ? Number(form.categoryId) : null,
        source: form.source || null,
        quantity: Number(form.quantity),
        unit: form.unit || 'Adet',
        minStockLevel: Number(form.minStockLevel),
        shelfLocation: form.shelfLocation || null,
      }
      await productService.updateProduct(modal.product.id, payload)
      setModal(null)
      flashSuccess('Ürün başarıyla güncellendi.')
      await loadData()
    } catch (err) {
      setFormError(err.message || 'Ürün güncellenemedi.')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(id) {
    setDeleting(true)
    setErrorMessage(null)
    try {
      await productService.deleteProduct(id)
      setDeleteTarget(null)
      flashSuccess('Ürün başarıyla silindi.')
      await loadData()
    } catch (err) {
      setErrorMessage(err.message || 'Ürün silinemedi.')
    } finally {
      setDeleting(false)
    }
  }

  function downloadBlob(blob, filename) {
    const url = window.URL.createObjectURL(new Blob([blob]))
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  }

  function exportFilename(ext) {
    const date = new Date().toISOString().slice(0, 10)
    return `stok_listesi_${date}.${ext}`
  }

  async function handleExportExcel() {
    setExportingExcel(true)
    setErrorMessage(null)
    try {
      const blob = await productService.exportProductsExcel()
      downloadBlob(blob, exportFilename('xlsx'))
      flashSuccess('Excel dosyası indirildi.')
    } catch (err) {
      setErrorMessage(err.message || 'Excel dosyası indirilemedi.')
    } finally {
      setExportingExcel(false)
    }
  }

  function handleExportPdf() {
    setExportingPdf(true)
    setErrorMessage(null)
    try {
      const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
      const pageW = doc.internal.pageSize.getWidth()
      const pageH = doc.internal.pageSize.getHeight()

      // ── 1. Turkish-compatible font (Roboto) ───────────────────────────────
      // The complete TTF is bundled as Base64; PDF export never needs the network.
      doc.addFileToVFS('Roboto-Regular.ttf', fontBase64)
      doc.addFont('Roboto-Regular.ttf', 'Roboto', 'normal')
      doc.setFont('Roboto', 'normal')

      // ── 2. Logo (top-right corner) ────────────────────────────────────────
      try {
        // The ?inline import keeps the logo available as a data URL offline.
        doc.addImage(logoSrc, 'PNG', pageW - 40, 7, 28, 28)
      } catch {
        // Logo rendering failed — continue without it.
      }

      // ── 3. Header text ────────────────────────────────────────────────────
      const userName = user?.fullName || user?.username || '—'

      doc.setFont('Roboto', 'normal')
      doc.setFontSize(16)
      doc.setTextColor(25, 25, 55)
      doc.text('Stok Listesi', 14, 17)

      doc.setFontSize(9)
      doc.setTextColor(70, 70, 100)
      const dateStr = new Date().toLocaleDateString('tr-TR', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit',
      })
      const headerLines = [
        `Oluşturma Tarihi : ${dateStr}`,
        `PDF'i Oluşturan  : ${userName}`,
        `Toplam Ürün      : ${filteredProducts.length} adet`,
      ]

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

      // ── 4. Data table ─────────────────────────────────────────────────────
      autoTable(doc, {
        startY: separatorY + 5,
        head: [['#', 'Ürün Adı', 'Kategori', 'Kaynak', 'Stok', 'Min. Seviye', 'Birim', 'Raf']],
        body: filteredProducts.map((p, i) => [
          i + 1,
          p.name || '—',
          p.category?.name || '—',
          p.source || '—',
          p.quantity != null ? p.quantity : '—',
          p.minStockLevel != null ? p.minStockLevel : '—',
          p.unit || '—',
          p.shelfLocation || '—',
        ]),
        headStyles: {
          fillColor: [79, 70, 229],
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
          4: { cellWidth: 16, halign: 'center' },
          5: { cellWidth: 20, halign: 'center' },
          6: { cellWidth: 16, halign: 'center' },
          7: { cellWidth: 22 },
        },
      })

      // ── 5. Footer: page numbers ────────────────────────────────────────────
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

      doc.save(exportFilename('pdf'))
      flashSuccess('PDF dosyası indirildi.')
    } catch (err) {
      console.error('PDF export hatası:', err)
      setErrorMessage('PDF dosyası oluşturulamadı.')
    } finally {
      setExportingPdf(false)
    }
  }

  const uniqueCategories = ['Tümü', ...new Set(categories.map((c) => c.name).filter(Boolean))]

  // ─────────────────────────────────────────────────────────────────────────
  return (
    <div className="min-h-screen bg-gray-50 font-sans">
      {/* ── Page header ── */}
      <div className="bg-white border-b border-gray-200 px-6 py-5">
        <div className="mx-auto max-w-7xl flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Ürün Yönetimi</h1>
            <p className="text-sm text-gray-500 mt-0.5">{products.length} ürün kayıtlı · {filteredProducts.length} sonuç gösteriliyor</p>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <button
              onClick={handleExportExcel}
              disabled={exportingExcel || exportingPdf}
              className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-emerald-700 active:scale-95 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              {exportingExcel ? 'İndiriliyor…' : "Excel'e Aktar"}
            </button>
            <button
              onClick={handleExportPdf}
              disabled={exportingExcel || exportingPdf}
              className="inline-flex items-center gap-2 rounded-xl bg-red-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-red-700 active:scale-95 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              {exportingPdf ? 'İndiriliyor…' : "PDF'e Aktar"}
            </button>
            <button
              onClick={() => setBulkImportOpen(true)}
              className="inline-flex items-center gap-2 rounded-xl bg-amber-500 px-4 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-amber-600 active:scale-95 transition-all"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" /></svg>
              Belge ile Toplu Yükle
            </button>
            <button
              onClick={() => { setFormError(null); setModal('create') }}
              className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 active:scale-95 transition-all"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
              Yeni Ürün Ekle
            </button>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-7xl px-6 py-6 space-y-5">
        {/* ── Banners ── */}
        {errorMessage && (
          <div className="flex items-center justify-between rounded-xl bg-red-50 p-4 text-sm font-medium text-red-700 border border-red-200 shadow-sm">
            <span>{errorMessage}</span>
            <button onClick={() => setErrorMessage(null)} className="text-red-500 hover:text-red-700 font-bold ml-4">✕</button>
          </div>
        )}
        {successMessage && (
          <div className="flex items-center justify-between rounded-xl bg-emerald-50 p-4 text-sm font-medium text-emerald-700 border border-emerald-200 shadow-sm">
            <span>{successMessage}</span>
            <button onClick={() => setSuccessMessage(null)} className="text-emerald-500 hover:text-emerald-700 font-bold ml-4">✕</button>
          </div>
        )}

        {/* ── Filters bar ── */}
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
          {/* Search */}
          <div className="relative flex-1">
            <svg className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-4.35-4.35m0 0A7.5 7.5 0 104.65 16.65 7.5 7.5 0 0016.65 16.65z" /></svg>
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Ürün adı veya kodu ara…"
              className="w-full rounded-xl border border-gray-300 bg-white py-2.5 pl-10 pr-4 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          {/* Category filter */}
          <select
            value={filterCategory}
            onChange={(e) => setFilterCategory(e.target.value)}
            className="rounded-xl border border-gray-300 bg-white px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 lg:w-48"
          >
            <option value="Tümü">Kategori: TÜMÜ</option>
            {uniqueCategories.filter((c) => c !== 'Tümü').map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>

          {/* Source filter */}
          <select
            value={filterSource}
            onChange={(e) => setFilterSource(e.target.value)}
            className="rounded-xl border border-gray-300 bg-white px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 lg:w-44"
          >
            <option value="Tümü">Kaynak: TÜMÜ</option>
            {SOURCES.filter((s) => s !== 'Tümü').map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>

          {/* Stock status filter */}
          <select
            value={filterStock}
            onChange={(e) => setFilterStock(e.target.value)}
            className="rounded-xl border border-gray-300 bg-white px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 lg:w-48"
          >
            {STOCK_STATUS_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </div>

        {/* ── Table ── */}
        <div className="overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-100">
              <thead className="bg-gray-50">
                <tr>
                  {['Ürün', 'Kategori', 'Kaynak', 'Stok', 'Konum', 'İşlemler'].map((h) => (
                    <th key={h} className="px-5 py-3.5 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {loading ? (
                  <tr>
                    <td colSpan={6} className="py-16 text-center text-sm text-gray-500">
                      <div className="flex justify-center items-center gap-2">
                        <svg className="animate-spin h-5 w-5 text-indigo-600" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" /></svg>
                        Ürünler yükleniyor...
                      </div>
                    </td>
                  </tr>
                ) : filteredProducts.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="py-16 text-center text-sm text-gray-400">
                      <div className="flex flex-col items-center gap-2">
                        <svg className="h-10 w-10 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" /></svg>
                        Filtreyle eşleşen ürün bulunamadı.
                      </div>
                    </td>
                  </tr>
                ) : (
                  filteredProducts.map((p) => (
                    <tr key={p.id} className="group hover:bg-indigo-50/40 transition-colors">
                      {/* Product name + code */}
                      <td className="px-5 py-4">
                        <p className="font-medium text-gray-800 text-sm">{p.name}</p>
                        {p.code && <p className="text-xs text-gray-400 mt-0.5">{p.code}</p>}
                      </td>

                      {/* Category */}
                      <td className="px-5 py-4">
                        {p.category
                          ? <span className="inline-block rounded-md bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">{p.category.name}</span>
                          : <span className="text-gray-400 text-sm italic">—</span>}
                      </td>

                      {/* Source badge */}
                      <td className="px-5 py-4">
                        <SourceBadge source={p.source} />
                      </td>

                      {/* Stock quantity (Optimistic UI) */}
                      <td className="px-5 py-4">
                        {(() => {
                          const pending = pendingMap[p.id] ?? 0
                          const displayStock = p.quantity + pending
                          return (
                            <div className="flex items-center gap-2">
                              <StockPill quantity={displayStock} minStockLevel={p.minStockLevel} />
                              <span className="text-xs text-gray-400">{p.unit}</span>
                              {pending !== 0 && (
                                <span
                                  title="Sepette bekleyen değişiklik"
                                  className={`text-[10px] font-bold px-1.5 py-0.5 rounded-full ${
                                    pending > 0
                                      ? 'bg-emerald-100 text-emerald-700'
                                      : 'bg-orange-100 text-orange-700'
                                  }`}
                                >
                                  {pending > 0 ? `+${pending}` : pending}
                                </span>
                              )}
                            </div>
                          )
                        })()}
                      </td>

                      {/* Shelf */}
                      <td className="px-5 py-4 text-sm text-gray-500">{p.shelfLocation ?? '—'}</td>

                      {/* Actions */}
                      <td className="px-5 py-4">
                        <div className="flex items-center gap-1.5">
                          {/* Add to Cart IN */}
                          <button
                            title="Sepete Ekle (Stok Giriş)"
                            onClick={() => onAddToCart && onAddToCart(p, 'IN')}
                            className="flex h-7 px-2 items-center gap-1 rounded-lg bg-emerald-100 text-emerald-800 hover:bg-emerald-200 transition-all active:scale-95 text-xs font-bold"
                          >
                            Arttır
                          </button>

                          {/* Add to Cart OUT */}
                          <button
                            title="Sepete Ekle (Stok Çıkış)"
                            disabled={(p.quantity + (pendingMap[p.id] ?? 0)) <= 0}
                            onClick={() => onAddToCart && onAddToCart(p, 'OUT')}
                            className="flex h-7 px-2 items-center gap-1 rounded-lg bg-orange-100 text-orange-800 hover:bg-orange-200 disabled:opacity-40 transition-all active:scale-95 text-xs font-bold"
                          >
                            Eksilt
                          </button>

                          {/* Edit */}
                          <button
                            title="Düzenle"
                            onClick={() => { setFormError(null); setModal({ mode: 'edit', product: p }) }}
                            className="flex h-7 items-center gap-1 rounded-lg bg-indigo-100 px-2 text-xs font-medium text-indigo-700 hover:bg-indigo-200 transition-colors"
                          >
                            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
                            Düzenle
                          </button>

                          {/* Delete */}
                          <button
                            title="Sil"
                            onClick={() => setDeleteTarget(p)}
                            className="flex h-7 items-center gap-1 rounded-lg bg-red-100 px-2 text-xs font-medium text-red-700 hover:bg-red-200 transition-colors"
                          >
                            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
                            Sil
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* ── Create Modal ── */}
      {modal === 'create' && (
        <Modal title="Yeni Ürün Ekle" onClose={() => setModal(null)}>
          <ProductForm
            categories={categories}
            onSubmit={handleCreate}
            onClose={() => setModal(null)}
            loading={saving}
            error={formError}
          />
        </Modal>
      )}

      {/* ── Edit Modal ── */}
      {modal?.mode === 'edit' && (
        <Modal title="Ürünü Düzenle" onClose={() => setModal(null)}>
          <ProductForm
            initial={modal.product}
            categories={categories}
            onSubmit={handleUpdate}
            onClose={() => setModal(null)}
            loading={saving}
            error={formError}
          />
        </Modal>
      )}

      {/* ── Delete Confirm Modal ── */}
      {deleteTarget && (
        <Modal title="Ürünü Sil" onClose={() => setDeleteTarget(null)}>
          <p className="text-sm text-gray-600 mb-6">
            <span className="font-semibold text-gray-800">{deleteTarget.name}</span> ürününü silmek istediğinizden emin misiniz? Bu işlem geri alınamaz.
          </p>
          <div className="flex gap-3">
            <button onClick={() => setDeleteTarget(null)} className="flex-1 rounded-lg border border-gray-300 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors">İptal</button>
            <button onClick={() => handleDelete(deleteTarget.id)} disabled={deleting} className="flex-1 rounded-lg bg-red-600 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50 transition-colors">
              {deleting ? 'Siliniyor...' : 'Sil'}
            </button>
          </div>
        </Modal>
      )}

      {/* ── Bulk Import Modal ── */}
      {bulkImportOpen && (
        <BulkImportModal
          onClose={() => setBulkImportOpen(false)}
          onImportSuccess={() => { setBulkImportOpen(false); flashSuccess('Toplu içe aktarım başarıyla tamamlandı.'); loadData() }}
        />
      )}
    </div>
  )
}
