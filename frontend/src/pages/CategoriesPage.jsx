import { useState, useEffect, useMemo } from 'react'
import { categoryService, productService } from '../services/productService'

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

function CategoryForm({ initial = {}, onSubmit, onClose, loading, error }) {
  const [form, setForm] = useState({
    name: initial.name ?? '',
  })

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))

  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(form) }} className="space-y-4">
      {error && (
        <div className="rounded-lg bg-red-50 p-3 text-xs font-medium text-red-700 border border-red-200">
          {error}
        </div>
      )}
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">Kategori Adı *</label>
        <input
          required
          value={form.name}
          onChange={set('name')}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          placeholder="Kategori adını girin (ör. Elektronik)"
        />
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

// categories and onCategoriesChange are shared from App.jsx (single source of truth)
export default function CategoriesPage({ categories = [], onCategoriesChange }) {
  // products kept local — only used for the per-category product count
  const [products, setProducts] = useState([])
  const [loading, setLoading] = useState(true)
  const [errorMessage, setErrorMessage] = useState(null)
  const [successMessage, setSuccessMessage] = useState(null)
  const [search, setSearch] = useState('')

  // Modal state
  const [modal, setModal] = useState(null) // null | 'create' | { mode: 'edit', category }
  const [formError, setFormError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)

  // ── Fetch products (for product count map only) ──────────────────────────
  useEffect(() => {
    setLoading(true)
    productService
      .getAllProducts()
      .catch(() => [])
      .then((list) => setProducts(list || []))
      .finally(() => setLoading(false))
  }, [])

  const flashSuccess = (msg) => {
    setSuccessMessage(msg)
    setTimeout(() => setSuccessMessage(null), 4000)
  }

  // ── Category product count map ────────────────────────────────────────────
  const productCountMap = useMemo(() => {
    const map = {}
    products.forEach((p) => {
      if (p.category?.id) {
        map[p.category.id] = (map[p.category.id] || 0) + 1
      }
    })
    return map
  }, [products])

  const filteredCategories = useMemo(() => {
    return categories.filter((c) =>
      c.name.toLowerCase().includes(search.toLowerCase()) ||
      (c.description ?? '').toLowerCase().includes(search.toLowerCase())
    )
  }, [categories, search])

  // ── Actions ───────────────────────────────────────────────────────────────
  async function handleCreate(form) {
    setSaving(true)
    setFormError(null)
    try {
      await categoryService.createCategory(form)
      setModal(null)
      flashSuccess('Kategori başarıyla oluşturuldu.')
      // Notify parent so shared list updates → ProductsPage dropdown refreshes too
      onCategoriesChange?.()
    } catch (err) {
      setFormError(err.message || 'Kategori oluşturulamadı.')
    } finally {
      setSaving(false)
    }
  }

  async function handleUpdate(form) {
    setSaving(true)
    setFormError(null)
    try {
      await categoryService.updateCategory(modal.category.id, form)
      setModal(null)
      flashSuccess('Kategori başarıyla güncellendi.')
      onCategoriesChange?.()
    } catch (err) {
      setFormError(err.message || 'Kategori güncellenemedi.')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(id) {
    setDeleting(true)
    setErrorMessage(null)
    try {
      await categoryService.deleteCategory(id)
      setDeleteTarget(null)
      flashSuccess('Kategori başarıyla silindi.')
      onCategoriesChange?.()
    } catch (err) {
      setErrorMessage(err.message || 'Kategori silinemedi.')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 font-sans">
      {/* ── Page header ── */}
      <div className="bg-white border-b border-gray-200 px-6 py-5">
        <div className="mx-auto max-w-7xl flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Kategori Yönetimi</h1>
            <p className="text-sm text-gray-500 mt-0.5">{categories.length} kategori kayıtlı</p>
          </div>
          <button
            onClick={() => { setFormError(null); setModal('create') }}
            className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 active:scale-95 transition-all"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
            Yeni Kategori Ekle
          </button>
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

        {/* ── Search bar ── */}
        <div className="relative max-w-md">
          <svg className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-4.35-4.35m0 0A7.5 7.5 0 104.65 16.65 7.5 7.5 0 0016.65 16.65z" /></svg>
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Kategori adı"
            className="w-full rounded-xl border border-gray-300 bg-white py-2.5 pl-10 pr-4 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>

        {/* ── Table ── */}
        <div className="overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-100">
              <thead className="bg-gray-50">
                <tr>
                  {['Kategori Adı', 'Açıklama', 'Ürün Sayısı', 'İşlemler'].map((h) => (
                    <th key={h} className="px-5 py-3.5 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {loading ? (
                  <tr>
                    <td colSpan={4} className="py-16 text-center text-sm text-gray-500">
                      <div className="flex justify-center items-center gap-2">
                        <svg className="animate-spin h-5 w-5 text-indigo-600" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" /></svg>
                        Kategoriler yükleniyor...
                      </div>
                    </td>
                  </tr>
                ) : filteredCategories.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="py-16 text-center text-sm text-gray-400">
                      <div className="flex flex-col items-center gap-2">
                        <svg className="h-10 w-10 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" /></svg>
                        Kategori bulunamadı.
                      </div>
                    </td>
                  </tr>
                ) : (
                  filteredCategories.map((cat) => {
                    const count = productCountMap[cat.id] || 0
                    return (
                      <tr key={cat.id} className="group hover:bg-indigo-50/40 transition-colors">
                        <td className="px-5 py-4 font-medium text-gray-800 text-sm">
                          {cat.name}
                        </td>
                        <td className="px-5 py-4 text-sm text-gray-500">
                          {cat.description || <span className="text-gray-400 italic">—</span>}
                        </td>
                        <td className="px-5 py-4">
                          <span className="inline-flex items-center rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs font-semibold text-indigo-700 ring-1 ring-indigo-200">
                            {count} Ürün
                          </span>
                        </td>
                        <td className="px-5 py-4">
                          <div className="flex items-center gap-1.5">
                            <button
                              title="Düzenle"
                              onClick={() => { setFormError(null); setModal({ mode: 'edit', category: cat }) }}
                              className="flex h-7 items-center gap-1 rounded-lg bg-indigo-100 px-2.5 text-xs font-medium text-indigo-700 hover:bg-indigo-200 transition-colors"
                            >
                              <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
                              Düzenle
                            </button>
                            <button
                              title="Sil"
                              onClick={() => setDeleteTarget(cat)}
                              className="flex h-7 items-center gap-1 rounded-lg bg-red-100 px-2.5 text-xs font-medium text-red-700 hover:bg-red-200 transition-colors"
                            >
                              <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
                              Sil
                            </button>
                          </div>
                        </td>
                      </tr>
                    )
                  })
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* ── Create Modal ── */}
      {modal === 'create' && (
        <Modal title="Yeni Kategori Ekle" onClose={() => setModal(null)}>
          <CategoryForm onSubmit={handleCreate} onClose={() => setModal(null)} loading={saving} error={formError} />
        </Modal>
      )}

      {/* ── Edit Modal ── */}
      {modal?.mode === 'edit' && (
        <Modal title="Kategoriyi Düzenle" onClose={() => setModal(null)}>
          <CategoryForm initial={modal.category} onSubmit={handleUpdate} onClose={() => setModal(null)} loading={saving} error={formError} />
        </Modal>
      )}

      {/* ── Delete Confirm Modal ── */}
      {deleteTarget && (
        <Modal title="Kategoriyi Sil" onClose={() => setDeleteTarget(null)}>
          <p className="text-sm text-gray-600 mb-6">
            <span className="font-semibold text-gray-800">{deleteTarget.name}</span> kategorisini silmek istediğinizden emin misiniz? Bu kategoriye bağlı ürünlerin kategorisi boş olarak güncellenecektir.
          </p>
          <div className="flex gap-3">
            <button onClick={() => setDeleteTarget(null)} className="flex-1 rounded-lg border border-gray-300 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors">İptal</button>
            <button onClick={() => handleDelete(deleteTarget.id)} disabled={deleting} className="flex-1 rounded-lg bg-red-600 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50 transition-colors">
              {deleting ? 'Siliniyor...' : 'Sil'}
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}
