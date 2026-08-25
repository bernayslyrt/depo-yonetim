import { useState, useEffect } from 'react'
import { movementService } from '../services/productService'

export default function CartDrawer({
  isOpen,
  onClose,
  cartItems,
  onUpdateQuantity,
  onRemoveItem,
  onClearCart,
  onCheckoutSuccess,
}) {
  const [checkingOut, setCheckingOut] = useState(false)
  const [error, setError] = useState(null)
  const [recipientName, setRecipientName] = useState('')
  const [description, setDescription] = useState('')

  useEffect(() => {
    if (isOpen) {
      setError(null)
    }
  }, [isOpen])

  if (!isOpen) return null

  // Total absolute units across all pending items
  const totalItemCount = cartItems.reduce((acc, item) => acc + Math.abs(item.netChange), 0)

  async function handleCheckout() {
    if (cartItems.length === 0) return
    setError(null)

    // Validate: net OUT cannot exceed current stock
    for (const item of cartItems) {
      if (item.netChange < 0 && Math.abs(item.netChange) > item.currentStock) {
        setError(
          `"${item.productName}" için net çıkış miktarı (${Math.abs(item.netChange)}), mevcut stoktan (${item.currentStock} ${item.unit}) fazla olamaz.`
        )
        return
      }
    }

    setCheckingOut(true)
    try {
      const movements = cartItems.map((item) => ({
        productId: item.productId,
        movementType: item.netChange > 0 ? 'IN' : 'OUT',
        quantity: Math.abs(item.netChange),
        recipientName: recipientName || null,
        description: description || 'Toplu stok hareketi',
      }))

      if (movements.length === 1) {
        await movementService.createMovement(movements[0])
      } else {
        // Send the entire cart in one bulk request — the backend generates a
        // single batch_id for all items, including mixed IN+OUT carts.
        await movementService.createMovements(movements)
      }

      onClearCart()
      onCheckoutSuccess('Toplu stok hareketi başarıyla tamamlandı!')
      // Reset form state so previous data doesn't persist on next open
      setRecipientName('')
      setDescription('')
      onClose()
    } catch (err) {
      setError(err.message || 'Stok hareketi işlenirken bir hata oluştu.')
    } finally {
      setCheckingOut(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 overflow-hidden bg-black/40 backdrop-blur-sm transition-opacity">
      <div className="absolute inset-y-0 right-0 flex max-w-full pl-10">
        <div className="w-screen max-w-md bg-white shadow-2xl flex flex-col">
          {/* ── Header ── */}
          <div className="bg-slate-900 text-white px-6 py-5 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-2xl">🛒</span>
              <div>
                <h2 className="text-lg font-bold">Stok İşlem Sepeti</h2>
                <p className="text-xs text-slate-400 mt-0.5">
                  {cartItems.length} ürün · {totalItemCount} adet toplam hareket
                </p>
              </div>
            </div>
            <button
              onClick={onClose}
              className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
            >
              <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          {/* ── Body Content ── */}
          <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
            {error && (
              <div className="rounded-xl bg-red-50 p-3.5 text-xs font-medium text-red-700 border border-red-200 shadow-sm flex items-start justify-between">
                <span>{error}</span>
                <button onClick={() => setError(null)} className="text-red-500 font-bold ml-2">✕</button>
              </div>
            )}

            {/* ── Details Form ── */}
            <div className="rounded-xl bg-slate-50 p-4 border border-slate-200/80 space-y-3">
              <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500">İşlem Bilgileri</h3>
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">Teslim Alan (Opsiyonel)</label>
                  <input
                    value={recipientName}
                    onChange={(e) => setRecipientName(e.target.value)}
                    placeholder="Ahmet Yılmaz"
                    className="w-full rounded-lg border border-gray-300 bg-white px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-gray-700 mb-1">Açıklama (Opsiyonel)</label>
                  <input
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                    placeholder="Toplu stok nakli"
                    className="w-full rounded-lg border border-gray-300 bg-white px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                </div>
              </div>
            </div>

            {/* ── Items List ── */}
            {cartItems.length === 0 ? (
              <div className="py-16 text-center text-gray-400">
                <svg className="mx-auto h-12 w-12 text-gray-300 mb-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 100 4 2 2 0 000-4z" />
                </svg>
                <p className="text-sm font-medium">Sepetiniz boş.</p>
                <p className="text-xs text-gray-400 mt-1">Ürünler sayfasındaki Arttır veya Eksilt butonlarıyla ürün ekleyin.</p>
              </div>
            ) : (
              <div className="space-y-2.5">
                <div className="flex items-center justify-between text-xs font-bold text-gray-500 uppercase px-1">
                  <span>Ürün Hareketleri</span>
                  <button onClick={onClearCart} className="text-red-600 hover:underline">Tümünü Temizle</button>
                </div>

                {cartItems.map((item) => {
                  const isIN = item.netChange > 0
                  const absQty = Math.abs(item.netChange)
                  const isOverStock = !isIN && absQty > item.currentStock

                  return (
                    <div
                      key={item.key}
                      className={`p-3.5 rounded-xl border transition-all ${
                        isOverStock
                          ? 'border-red-300 bg-red-50/50'
                          : 'border-gray-200 bg-white hover:border-gray-300'
                      }`}
                    >
                      {/* ── Card Header ── */}
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          {/* Direction badge — derived from sign of netChange */}
                          <span
                            className={`inline-block rounded-md px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider mb-1 ${
                              isIN
                                ? 'bg-emerald-100 text-emerald-800 border border-emerald-200'
                                : 'bg-orange-100 text-orange-800 border border-orange-200'
                            }`}
                          >
                            {isIN ? '↑ Stok Giriş (+)' : '↓ Stok Çıkış (-)'}
                          </span>
                          <h4 className="text-sm font-semibold text-gray-800 truncate">{item.productName}</h4>
                          <p className="text-xs text-gray-400 mt-0.5">
                            {item.productCode ? `Kod: ${item.productCode} · ` : ''}
                            Mevcut Stok:{' '}
                            <span className="font-semibold text-gray-600">
                              {item.currentStock} {item.unit}
                            </span>
                          </p>
                        </div>
                        <button
                          onClick={() => onRemoveItem(item.key)}
                          className="text-gray-400 hover:text-red-600 p-1 transition-colors flex-shrink-0"
                          title="Sepetten Çıkar"
                        >
                          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                          </svg>
                        </button>
                      </div>

                      {/* ── Quantity stepper ── */}
                      {/* Stepper adjusts the signed netChange:
                            − shrinks the magnitude toward 0 (auto-removes at 0)
                            + grows the magnitude away from 0
                          Direction badge updates automatically. */}
                      <div className="mt-3 flex items-center justify-between border-t border-gray-100 pt-2.5">
                        <span className="text-xs text-gray-500 font-medium">Net Miktar:</span>
                        <div className="flex items-center gap-1.5">
                          <button
                            onClick={() => {
                              const sign = isIN ? 1 : -1
                              onUpdateQuantity(item.key, item.netChange - sign)
                            }}
                            className="h-6 w-6 rounded bg-gray-100 text-gray-700 hover:bg-gray-200 flex items-center justify-center font-bold text-xs"
                          >
                            −
                          </button>
                          <input
                            type="number"
                            min="1"
                            value={absQty}
                            onChange={(e) => {
                              const sign = isIN ? 1 : -1
                              const v = Math.max(1, parseInt(e.target.value) || 1)
                              onUpdateQuantity(item.key, sign * v)
                            }}
                            className="w-16 rounded border border-gray-300 px-2 py-0.5 text-center text-xs font-semibold focus:outline-none focus:ring-1 focus:ring-indigo-500"
                          />
                          <button
                            onClick={() => {
                              const sign = isIN ? 1 : -1
                              onUpdateQuantity(item.key, item.netChange + sign)
                            }}
                            className="h-6 w-6 rounded bg-gray-100 text-gray-700 hover:bg-gray-200 flex items-center justify-center font-bold text-xs"
                          >
                            +
                          </button>
                          <span className="text-xs text-gray-400 ml-1">{item.unit}</span>
                        </div>
                      </div>

                      {isOverStock && (
                        <p className="text-[11px] font-semibold text-red-600 mt-2">
                          ⚠️ Stok yetersiz! İstenen miktar mevcut stoktan ({item.currentStock}) fazla.
                        </p>
                      )}
                    </div>
                  )
                })}
              </div>
            )}
          </div>

          {/* ── Footer / Checkout Button ── */}
          <div className="border-t border-gray-200 bg-white p-6 space-y-3">
            <button
              onClick={handleCheckout}
              disabled={cartItems.length === 0 || checkingOut}
              className="w-full rounded-xl bg-indigo-600 py-3 text-sm font-semibold text-white shadow-lg shadow-indigo-600/30 hover:bg-indigo-700 active:scale-95 disabled:opacity-50 disabled:pointer-events-none transition-all flex items-center justify-center gap-2"
            >
              {checkingOut ? (
                <>
                  <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
                  </svg>
                  İşlemler Gönderiliyor...
                </>
              ) : (
                <>
                  <span>İşlem Onay</span>
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
                  </svg>
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
