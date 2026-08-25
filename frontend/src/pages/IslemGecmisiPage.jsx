import { useState, useEffect } from 'react'
import { islemGecmisiService } from '../services/productService'
import { useAuth } from '../contexts/AuthContext'
import BatchDetailModal from '../components/BatchDetailModal'
import { getBatchOperationInfo } from '../utils/batchOperationInfo'

// ── Constants ─────────────────────────────────────────────────────────────────

const ISLEM_TIPI_LABELS = {
  STOK_GIRIS:        { text: 'Stok Giriş',          color: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30', icon: '📥' },
  STOK_CIKIS:        { text: 'Stok Çıkış',          color: 'bg-red-500/20 text-red-400 border-red-500/30',            icon: '📤' },
  PDF_YUKLEME:       { text: 'PDF Yükleme',          color: 'bg-blue-500/20 text-blue-400 border-blue-500/30',         icon: '📄' },
  TOPLU_ICE_AKTARIM: { text: 'Toplu İçe Aktarım',   color: 'bg-violet-500/20 text-violet-400 border-violet-500/30',   icon: '📦' },
  KARMA_ISLEM:       { text: 'Karma İşlem',          color: 'bg-blue-500/20 text-blue-400 border-blue-500/30',         icon: '🔀' },
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatDate(dateStr) {
  if (!dateStr) return '—'
  const d = new Date(dateStr)
  return d.toLocaleDateString('tr-TR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

// ── Sub-components ────────────────────────────────────────────────────────────

/** İşlem tipi badge */
function TipBadge({ islemTipi }) {
  const tip = ISLEM_TIPI_LABELS[islemTipi] || {
    text: islemTipi,
    color: 'bg-slate-500/20 text-slate-400 border-slate-500/30',
    icon: '❓',
  }
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-semibold whitespace-nowrap ${tip.color}`}>
      {tip.icon} {tip.text}
    </span>
  )
}

/** Kullanıcı avatarı + adı */
function UserCell({ fullName, username }) {
  if (!fullName && !username) return <span className="text-slate-400">—</span>
  return (
    <div className="flex items-center gap-2">
      <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-indigo-100 text-xs font-bold text-indigo-600">
        {(fullName || '?')[0]}
      </span>
      <div>
        <div className="font-medium text-slate-800 text-sm">{fullName || '—'}</div>
        <div className="text-xs text-slate-400">{username || '—'}</div>
      </div>
    </div>
  )
}

// ── Main Page ─────────────────────────────────────────────────────────────────

export default function IslemGecmisiPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const [items, setItems]       = useState([])
  const [loading, setLoading]   = useState(true)
  const [error, setError]       = useState(null)
  const [success, setSuccess]   = useState(null)
  const [rollingBackBatchId, setRollingBackBatchId] = useState(null)
  const [batchModal, setBatchModal] = useState(null) // { batchId, summary }

  useEffect(() => { loadSummary() }, [])

  const loadSummary = async () => {
    try {
      setLoading(true)
      const data = await islemGecmisiService.getSummary()
      setItems(data || [])
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleRollback = async (item) => {
    if (!isAdmin || item.isCancelled || rollingBackBatchId) return

    const confirmed = window.confirm(
      `${item.toplamUrun} ürün içeren bu toplu işlemi geri almak istediğinize emin misiniz? Bu işlem yeniden uygulanamaz.`
    )
    if (!confirmed) return

    try {
      setRollingBackBatchId(item.batchId)
      setError(null)
      setSuccess(null)
      await islemGecmisiService.rollbackBatch(item.batchId)
      const refreshedItems = await islemGecmisiService.getSummary()
      setItems(refreshedItems || [])
      setSuccess('Toplu işlem başarıyla geri alındı ve stoklar güncellendi.')
    } catch (err) {
      setError(err.message)
    } finally {
      setRollingBackBatchId(null)
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div className="mx-auto max-w-7xl px-6 py-8">

      {/* ── Header ── */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
            📋 İşlem Geçmişi
          </h2>
          <p className="text-sm text-slate-500 mt-1">
            {isAdmin
              ? 'Tüm kullanıcıların işlem kayıtları gösterilmektedir. Toplu işlemler tek satırda özetlenir.'
              : 'Sadece kendi işlem kayıtlarınız gösterilmektedir. Toplu işlemler tek satırda özetlenir.'}
          </p>
        </div>
        <button
          onClick={loadSummary}
          className="inline-flex items-center gap-2 rounded-xl bg-slate-900 text-white px-4 py-2.5 text-sm font-semibold hover:bg-slate-800 transition-all active:scale-95"
        >
          🔄 Yenile
        </button>
      </div>

      {/* ── Error Banner ── */}
      {error && (
        <div className="mb-4 rounded-xl bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-700">
          ⚠️ {error}
        </div>
      )}

      {success && (
        <div className="mb-4 rounded-xl bg-emerald-50 border border-emerald-200 px-4 py-3 text-sm text-emerald-700">
          ✅ {success}
        </div>
      )}

      {/* ── Loading ── */}
      {loading ? (
        <div className="flex flex-col items-center justify-center py-20 gap-3">
          <svg className="animate-spin h-7 w-7 text-indigo-500" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
          </svg>
          <span className="text-slate-400 text-sm">Yükleniyor…</span>
        </div>
      ) : items.length === 0 ? (
        <div className="text-center py-20 text-slate-400">
          <span className="text-5xl block mb-3">📭</span>
          <p className="font-medium">Henüz işlem kaydı bulunmuyor.</p>
        </div>
      ) : (

        /* ── Table ── */
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-slate-50 border-b border-slate-200">
                  <th className="text-left px-5 py-3.5 font-semibold text-slate-600">Tarih</th>
                  {isAdmin && (
                    <th className="text-left px-5 py-3.5 font-semibold text-slate-600">Kullanıcı</th>
                  )}
                  <th className="text-left px-5 py-3.5 font-semibold text-slate-600">İşlem Tipi</th>
                  <th className="text-left px-5 py-3.5 font-semibold text-slate-600">Ürün / Özet</th>
                  <th className="text-right px-5 py-3.5 font-semibold text-slate-600">Miktar</th>
                  <th className="text-left px-5 py-3.5 font-semibold text-slate-600">Açıklama</th>
                  <th className="text-left px-5 py-3.5 font-semibold text-slate-600">Teslim Alan</th>
                  <th className="text-center px-5 py-3.5 font-semibold text-slate-600">İşlemler</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((item, idx) => {
                  // ── Batch summary row ──────────────────────────────────
                  if (item.isBatch) {
                    const tipInfo = getBatchOperationInfo(item.islemTipi)
                    return (
                      <tr
                        key={item.batchId ?? idx}
                        className={`${item.isCancelled ? 'bg-red-50/70 opacity-75' : tipInfo.rowHover} transition-colors group`}
                      >
                        <td className={`px-5 py-3.5 text-slate-500 whitespace-nowrap ${item.isCancelled ? 'line-through' : ''}`}>
                          {formatDate(item.tarihSaat)}
                        </td>
                        {isAdmin && (
                          <td className="px-5 py-3.5">
                            <UserCell fullName={item.kullaniciFullName} username={item.kullaniciAdi} />
                          </td>
                        )}
                        <td className="px-5 py-3.5">
                          <span className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs font-semibold whitespace-nowrap ${tipInfo.badgeColor}`}>
                            {tipInfo.icon} {tipInfo.text}
                          </span>
                        </td>
                        {/* Özet: ürün sayısı */}
                        <td className="px-5 py-3.5">
                          <div className={`flex items-center gap-2 ${item.isCancelled ? 'line-through' : ''}`}>
                            <span className={`inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-bold border ${tipInfo.countColor}`}>
                              {tipInfo.icon} {item.toplamUrun} ürün
                            </span>
                          </div>
                        </td>
                        {/* Toplam miktar hesaplanamaz burada — boş bırak */}
                        <td className="px-5 py-3.5 text-right">
                          <span className="text-slate-300 text-xs">—</span>
                        </td>
                        <td className="px-5 py-3.5 text-slate-500 max-w-xs truncate" title={item.aciklama || undefined}>
                          {item.aciklama || '—'}
                        </td>
                        <td className="px-5 py-3.5">
                          {item.recipientName
                            ? <span className="inline-flex items-center gap-1 rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700 border border-indigo-200">👤 {item.recipientName}</span>
                            : <span className="text-slate-300">—</span>}
                        </td>
                        {/* İncele, geri al ve iptal durumu */}
                        <td className="px-5 py-3.5 text-center">
                          <div className="flex items-center justify-center gap-2 whitespace-nowrap">
                            <button
                              onClick={() => setBatchModal({ batchId: item.batchId, summary: item })}
                              className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold text-white active:scale-95 transition-all shadow-sm ${tipInfo.buttonColor}`}
                            >
                              🔍 İncele
                            </button>
                            {item.isCancelled ? (
                              <span className="inline-flex items-center gap-1 rounded-lg border border-red-300 bg-red-100 px-3 py-1.5 text-xs font-extrabold text-red-700 shadow-sm">
                                ⛔ İptal Edildi
                              </span>
                            ) : isAdmin ? (
                              <button
                                onClick={() => handleRollback(item)}
                                disabled={rollingBackBatchId !== null}
                                className="inline-flex items-center gap-1.5 rounded-lg border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-800 shadow-sm transition-all hover:bg-amber-100 active:scale-95 disabled:cursor-not-allowed disabled:opacity-50"
                              >
                                {rollingBackBatchId === item.batchId ? '⏳ Geri Alınıyor…' : '↩️ Geri Al'}
                              </button>
                            ) : null}
                          </div>
                        </td>
                      </tr>
                    )
                  }

                  // ── Single (non-batch) row ─────────────────────────────
                  return (
                    <tr key={item.id ?? idx} className="hover:bg-slate-50/60 transition-colors">
                      <td className="px-5 py-3.5 text-slate-500 whitespace-nowrap">
                        {formatDate(item.tarihSaat)}
                      </td>
                      {isAdmin && (
                        <td className="px-5 py-3.5">
                          <UserCell fullName={item.kullaniciFullName} username={item.kullaniciAdi} />
                        </td>
                      )}
                      <td className="px-5 py-3.5">
                        <TipBadge islemTipi={item.islemTipi} />
                      </td>
                      <td className="px-5 py-3.5 font-medium text-slate-800">
                        {item.urunAdi || '—'}
                      </td>
                      <td className="px-5 py-3.5 text-right font-semibold text-slate-700">
                        {item.miktar != null ? item.miktar : '—'}
                      </td>
                      <td className="px-5 py-3.5 text-slate-500 max-w-xs truncate">
                        {item.aciklama || '—'}
                      </td>
                      <td className="px-5 py-3.5 text-slate-500 whitespace-nowrap">
                        {item.recipientName
                          ? <span className="inline-flex items-center gap-1 rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700 border border-indigo-200">👤 {item.recipientName}</span>
                          : <span className="text-slate-300">—</span>}
                      </td>
                      {/* No detail button for single rows */}
                      <td className="px-5 py-3.5 text-center">
                        <span className="text-slate-200 text-xs">—</span>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {/* ── Footer stats ── */}
          <div className="px-5 py-3 bg-slate-50 border-t border-slate-100 flex items-center justify-between">
            <p className="text-xs text-slate-400">
              Toplam <span className="font-semibold text-slate-600">{items.length}</span> kayıt
              {' · '}
              <span className="font-semibold text-violet-600">{items.filter(i => i.isBatch).length}</span> toplu işlem
            </p>
            <p className="text-xs text-slate-400 italic">
              📦 Toplu işlemler bir satırda özetlenir — detay için "İncele" butonuna tıklayın.
            </p>
          </div>
        </div>
      )}

      {/* ── Batch Detail Modal ── */}
      {batchModal && (
        <BatchDetailModal
          batchId={batchModal.batchId}
          summary={batchModal.summary}
          onClose={() => setBatchModal(null)}
        />
      )}
    </div>
  )
}
