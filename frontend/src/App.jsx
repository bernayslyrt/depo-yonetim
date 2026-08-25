import { useState, useEffect, useCallback } from 'react'
import './index.css'
import logo from './assets/logo.png'
import ProductsPage from './pages/ProductsPage'
import CategoriesPage from './pages/CategoriesPage'
import UsersPage from './pages/UsersPage'
import IslemGecmisiPage from './pages/IslemGecmisiPage'
import CartDrawer from './components/CartDrawer'
import ProtectedRoute from './components/ProtectedRoute'
import ProfileModal from './components/ProfileModal'
import { useAuth } from './contexts/AuthContext'
import { categoryService } from './services/productService'

export default function App() {
  const { user, logout, isAdmin } = useAuth()
  const [activeTab, setActiveTab] = useState('products')
  const [showProfile, setShowProfile] = useState(false)
  const [cartItems, setCartItems] = useState([])
  const [isCartOpen, setIsCartOpen] = useState(false)
  const [refreshTrigger, setRefreshTrigger] = useState(0)
  const [toastMessage, setToastMessage] = useState(null)

  // ── Shared Categories State (single source of truth) ───────────────────────
  const [categories, setCategories] = useState([])

  const loadCategories = useCallback(async () => {
    try {
      const list = await categoryService.getAllCategories()
      setCategories(list || [])
    } catch {
      // silently ignore — each page shows its own error banner
    }
  }, [])

  useEffect(() => {
    if (user) loadCategories()
  }, [loadCategories, user])

  const allTabs = [
    { id: 'products', label: 'Ürünler', icon: '📦' },
    { id: 'categories', label: 'Kategoriler', icon: '🏷️' },
    { id: 'users', label: 'Kullanıcılar', icon: '👥', adminOnly: true },
    { id: 'islemler', label: 'İşlem Geçmişi', icon: '📋' },
  ]

  const tabs = allTabs.filter((tab) => !tab.adminOnly || isAdmin)

  useEffect(() => {
    if (activeTab === 'users' && !isAdmin) {
      setActiveTab('products')
    }
  }, [activeTab, isAdmin])

  const showToast = (msg) => {
    setToastMessage(msg)
    setTimeout(() => setToastMessage(null), 4000)
  }

  // ── Cart Handlers ──────────────────────────────────────────────────────────
  // Cart item shape: { key (productId string), productId, productName,
  //   productCode, unit, currentStock, netChange (±N) }
  // ONE entry per product — netChange tracks net direction.
  // netChange > 0 → net IN  |  netChange < 0 → net OUT  |  0 → removed
  const handleAddToCart = (product, movementType) => {
    const key = String(product.id)
    const delta = movementType === 'IN' ? 1 : -1

    setCartItems((prev) => {
      const existing = prev.find((item) => item.key === key)
      if (existing) {
        const newNet = existing.netChange + delta
        if (newNet === 0) {
          // Actions cancelled each other out — remove from cart
          return prev.filter((item) => item.key !== key)
        }
        return prev.map((item) =>
          item.key === key ? { ...item, netChange: newNet } : item
        )
      }
      return [
        ...prev,
        {
          key,
          productId: product.id,
          productName: product.name,
          productCode: product.code,
          unit: product.unit || 'Adet',
          currentStock: product.quantity,
          netChange: delta,
        },
      ]
    })

    const typeText = movementType === 'IN' ? 'Arttır (+)' : 'Eksilt (-)'
    showToast(`"${product.name}" güncellendi [${typeText}].`)
  }

  // newNetChange is the signed value to set directly (positive = IN, negative = OUT)
  const handleUpdateQuantity = (key, newNetChange) => {
    if (newNetChange === 0) {
      handleRemoveItem(key)
      return
    }
    setCartItems((prev) =>
      prev.map((item) => (item.key === key ? { ...item, netChange: newNetChange } : item))
    )
  }

  const handleRemoveItem = (key) => {
    setCartItems((prev) => prev.filter((item) => item.key !== key))
  }

  const handleClearCart = () => {
    setCartItems([])
  }

  const handleCheckoutSuccess = (message) => {
    showToast(message)
    setRefreshTrigger((prev) => prev + 1)
  }

  // Badge count = total absolute magnitude of all pending changes
  const totalCartCount = cartItems.reduce((acc, item) => acc + Math.abs(item.netChange), 0)

  return (
    <ProtectedRoute>
      <div className="min-h-screen bg-gray-50 flex flex-col relative">
        {/* ── Toast Notification ── */}
        {toastMessage && (
          <div className="fixed top-20 right-6 z-50 animate-bounce rounded-xl bg-slate-900 px-4 py-3 text-xs font-semibold text-white shadow-2xl border border-slate-700 flex items-center gap-2">
            <span>✨ {toastMessage}</span>
            <button onClick={() => setToastMessage(null)} className="ml-2 text-slate-400 hover:text-white">✕</button>
          </div>
        )}

        {/* ── Top Navigation Bar ── */}
        <header className="bg-slate-900 text-white shadow-md border-b border-slate-800 sticky top-0 z-40">
          <div className="mx-auto max-w-7xl px-6 flex h-16 items-center justify-between">
            {/* Logo + Brand */}
            <div className="flex items-center gap-3">
              <div className="h-14 w-14 shrink-0">
                <img
                  src={logo}
                  alt="Bilim Samsun Logo"
                  className="h-full w-full object-contain"
                />
              </div>
              <div>
                <span className="text-lg font-bold tracking-tight text-white">Bilim Samsun</span>
                <span className="hidden sm:inline-block ml-2 text-xs font-semibold px-2 py-0.5 rounded bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">Depo Yönetimi</span>
              </div>
            </div>

            {/* Center Tabs */}
            <nav className="flex items-center space-x-1 sm:space-x-2">
              {tabs.map((tab) => {
                const isActive = activeTab === tab.id
                return (
                  <button
                    key={tab.id}
                    onClick={() => setActiveTab(tab.id)}
                    className={`flex items-center gap-2 rounded-xl px-3.5 py-2 text-sm font-medium transition-all ${isActive
                      ? 'bg-indigo-600 text-white shadow-md shadow-indigo-600/30'
                      : 'text-slate-300 hover:bg-slate-800 hover:text-white'
                      }`}
                  >
                    <span>{tab.icon}</span>
                    <span>{tab.label}</span>
                  </button>
                )
              })}
            </nav>

            {/* Right Action: Cart + User Info + Logout */}
            <div className="flex items-center gap-3">
              <button
                onClick={() => setIsCartOpen(true)}
                className="relative inline-flex items-center gap-2 rounded-xl bg-amber-500 hover:bg-amber-600 text-slate-950 px-3.5 py-2 text-xs font-extrabold shadow-md shadow-amber-500/20 active:scale-95 transition-all"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 100 4 2 2 0 000-4z" />
                </svg>
                <span>Sepet / İşlem Onay</span>
                {totalCartCount > 0 && (
                  <span className="flex h-5 min-w-[20px] items-center justify-center rounded-full bg-slate-950 px-1.5 text-[11px] font-black text-amber-400">
                    {totalCartCount}
                  </span>
                )}
              </button>

              {/* User Info, Profile & Logout */}
              {user && (
                <div className="flex items-center gap-2">
                  <span className="hidden sm:inline text-xs text-slate-400">
                    {user.fullName}
                  </span>
                  <button
                    onClick={() => setShowProfile(true)}
                    className="rounded-lg bg-slate-700 hover:bg-slate-600 text-slate-300 hover:text-white px-2.5 py-1.5 text-xs font-medium transition-all"
                  >
                    Profilim
                  </button>
                  <button
                    onClick={logout}
                    className="rounded-lg bg-slate-700 hover:bg-slate-600 text-slate-300 hover:text-white px-2.5 py-1.5 text-xs font-medium transition-all"
                  >
                    Çıkış
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        {/* ── Page Views (Kept mounted to preserve state) ── */}
        <main className="flex-1">
          <div className={activeTab === 'products' ? 'block' : 'hidden'}>
            <ProductsPage
              onAddToCart={handleAddToCart}
              refreshTrigger={refreshTrigger}
              categories={categories}
              cartItems={cartItems}
            />
          </div>
          <div className={activeTab === 'categories' ? 'block' : 'hidden'}>
            <CategoriesPage
              categories={categories}
              onCategoriesChange={loadCategories}
            />
          </div>
          {isAdmin && (
            <div className={activeTab === 'users' ? 'block' : 'hidden'}>
              <UsersPage />
            </div>
          )}
          <div className={activeTab === 'islemler' ? 'block' : 'hidden'}>
            <IslemGecmisiPage />
          </div>
        </main>

        {/* ── Cart Drawer ── */}
        <CartDrawer
          isOpen={isCartOpen}
          onClose={() => setIsCartOpen(false)}
          cartItems={cartItems}
          onUpdateQuantity={handleUpdateQuantity}
          onRemoveItem={handleRemoveItem}
          onClearCart={handleClearCart}
          onCheckoutSuccess={handleCheckoutSuccess}
        />

        {showProfile && <ProfileModal onClose={() => setShowProfile(false)} />}
      </div>
    </ProtectedRoute>
  )
}
