import { useState, useEffect, useMemo } from 'react'
import { userService } from '../services/productService'

// ─── Helpers ─────────────────────────────────────────────────────────────────

function RoleBadge({ role }) {
  const isAdmin = role === 'ADMIN'
  const cls = isAdmin
    ? 'bg-purple-100 text-purple-800 ring-1 ring-purple-300'
    : 'bg-blue-100 text-blue-800 ring-1 ring-blue-300'
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${cls}`}>
      {role === 'ADMIN' ? 'Yönetici' : 'Personel'}
    </span>
  )
}

function formatDate(dateStr) {
  if (!dateStr) return '—'
  try {
    return new Date(dateStr).toLocaleString('tr-TR', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return dateStr
  }
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


function UserForm({ initial = {}, isEdit = false, onSubmit, onClose, loading, error }) {
  const [form, setForm] = useState({
    username: initial.username ?? '',
    fullName: initial.fullName ?? '',
    role: initial.role === 'STAFF' ? 'USER' : (initial.role ?? 'USER'),
    password: '',
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
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Kullanıcı Adı {isEdit ? '' : '*'}
        </label>
        <input
          required={!isEdit}
          value={form.username}
          onChange={set('username')}
          readOnly={isEdit}
          className={`w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 ${isEdit
              ? 'border-gray-200 bg-gray-100 text-gray-500 cursor-not-allowed'
              : 'border-gray-300'
            }`}
          placeholder="ahmet.yilmaz"
        />
        {isEdit && (
          <p className="text-xs text-gray-400 mt-1">Kullanıcı adı değiştirilemez.</p>
        )}
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">Ad Soyad *</label>
        <input
          required
          value={form.fullName}
          onChange={set('fullName')}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          placeholder="Ahmet Yılmaz"
        />
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">Rol</label>
        <select
          value={form.role}
          onChange={set('role')}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
        >
          <option value="USER">Personel</option>
          <option value="ADMIN">Yönetici</option>
        </select>
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          {isEdit ? 'Şifre (Değiştirmek istemiyorsanız boş bırakın)' : 'Şifre *'}
        </label>
        <input
          type="password"
          required={!isEdit}
          value={form.password}
          onChange={set('password')}
          className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          placeholder="••••••••"
        />
        {!isEdit && (
          <p className="text-xs text-gray-400 mt-1">Kullanıcının ilk giriş şifresi olarak atanır.</p>
        )}
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

export default function UsersPage() {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(true)
  const [errorMessage, setErrorMessage] = useState(null)
  const [successMessage, setSuccessMessage] = useState(null)
  const [search, setSearch] = useState('')

  // Modal state
  const [modal, setModal] = useState(null) // null | 'create' | { mode: 'edit', user }
  const [formError, setFormError] = useState(null)
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)

  // ── Initial Fetch ─────────────────────────────────────────────────────────
  const loadData = async () => {
    setLoading(true)
    setErrorMessage(null)
    try {
      const userList = await userService.getAllUsers()
      setUsers(userList || [])
    } catch (err) {
      setErrorMessage(err.message || 'Kullanıcılar yüklenirken bir hata oluştu.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadData()
  }, [])

  const flashSuccess = (msg) => {
    setSuccessMessage(msg)
    setTimeout(() => setSuccessMessage(null), 4000)
  }

  const filteredUsers = useMemo(() => {
    return users.filter((u) =>
      u.username.toLowerCase().includes(search.toLowerCase()) ||
      u.fullName.toLowerCase().includes(search.toLowerCase())
    )
  }, [users, search])

  // ── Actions ───────────────────────────────────────────────────────────────
  async function handleCreate(form) {
    setSaving(true)
    setFormError(null)
    try {
      await userService.createUser(form)
      setModal(null)
      flashSuccess('Kullanıcı başarıyla oluşturuldu.')
      await loadData()
    } catch (err) {
      setFormError(err.message || 'Kullanıcı oluşturulamadı.')
    } finally {
      setSaving(false)
    }
  }

  async function handleUpdate(form) {
    setSaving(true)
    setFormError(null)
    try {
      const payload = {
        username: form.username,
        fullName: form.fullName,
        role: form.role,
      }
      if (form.password) {
        payload.password = form.password
      }
      await userService.updateUser(modal.user.id, payload)
      setModal(null)
      flashSuccess('Kullanıcı başarıyla güncellendi.')
      await loadData()
    } catch (err) {
      setFormError(err.message || 'Kullanıcı güncellenemedi.')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(id) {
    setDeleting(true)
    setErrorMessage(null)
    try {
      await userService.deleteUser(id)
      setDeleteTarget(null)
      flashSuccess('Kullanıcı başarıyla silindi.')
      await loadData()
    } catch (err) {
      setErrorMessage(err.message || 'Kullanıcı silinemedi.')
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
            <h1 className="text-2xl font-bold text-gray-900">Kullanıcı Yönetimi</h1>
            <p className="text-sm text-gray-500 mt-0.5">{users.length} kullanıcı kayıtlı</p>
          </div>
          <button
            onClick={() => { setFormError(null); setModal('create') }}
            className="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-700 active:scale-95 transition-all"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
            Yeni Kullanıcı Ekle
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
            placeholder="Kullanıcı adı veya ad soyad ara…"
            className="w-full rounded-xl border border-gray-300 bg-white py-2.5 pl-10 pr-4 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>

        {/* ── Table ── */}
        <div className="overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-sm">
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-100">
              <thead className="bg-gray-50">
                <tr>
                  {['Kullanıcı Adı', 'Ad Soyad', 'Rol', 'Kayıt Tarihi', 'İşlemler'].map((h) => (
                    <th key={h} className="px-5 py-3.5 text-left text-xs font-semibold uppercase tracking-wide text-gray-500">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-50">
                {loading ? (
                  <tr>
                    <td colSpan={5} className="py-16 text-center text-sm text-gray-500">
                      <div className="flex justify-center items-center gap-2">
                        <svg className="animate-spin h-5 w-5 text-indigo-600" fill="none" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" /></svg>
                        Kullanıcılar yükleniyor...
                      </div>
                    </td>
                  </tr>
                ) : filteredUsers.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-16 text-center text-sm text-gray-400">
                      <div className="flex flex-col items-center gap-2">
                        <svg className="h-10 w-10 text-gray-300" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" /></svg>
                        Kullanıcı bulunamadı.
                      </div>
                    </td>
                  </tr>
                ) : (
                  filteredUsers.map((user) => (
                    <tr key={user.id} className="group hover:bg-indigo-50/40 transition-colors">
                      <td className="px-5 py-4 font-mono text-xs font-semibold text-gray-700">
                        @{user.username}
                      </td>
                      <td className="px-5 py-4 font-medium text-gray-800 text-sm">
                        {user.fullName}
                      </td>
                      <td className="px-5 py-4">
                        <RoleBadge role={user.role} />
                      </td>
                      <td className="px-5 py-4 text-xs text-gray-500">
                        {formatDate(user.createdAt)}
                      </td>
                      <td className="px-5 py-4">
                        <div className="flex items-center gap-1.5">
                          <button
                            title="Düzenle"
                            onClick={() => { setFormError(null); setModal({ mode: 'edit', user }) }}
                            className="flex h-7 items-center gap-1 rounded-lg bg-indigo-100 px-2.5 text-xs font-medium text-indigo-700 hover:bg-indigo-200 transition-colors"
                          >
                            <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
                            Düzenle
                          </button>
                          <button
                            title="Sil"
                            onClick={() => setDeleteTarget(user)}
                            className="flex h-7 items-center gap-1 rounded-lg bg-red-100 px-2.5 text-xs font-medium text-red-700 hover:bg-red-200 transition-colors"
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
        <Modal title="Yeni Kullanıcı Ekle" onClose={() => setModal(null)}>
          <UserForm onSubmit={handleCreate} onClose={() => setModal(null)} loading={saving} error={formError} />
        </Modal>
      )}

      {/* ── Edit Modal ── */}
      {modal?.mode === 'edit' && (
        <Modal title="Kullanıcıyı Düzenle" onClose={() => setModal(null)}>
          <UserForm initial={modal.user} isEdit={true} onSubmit={handleUpdate} onClose={() => setModal(null)} loading={saving} error={formError} />
        </Modal>
      )}

      {/* ── Delete Confirm Modal ── */}
      {deleteTarget && (
        <Modal title="Kullanıcıyı Sil" onClose={() => setDeleteTarget(null)}>
          <p className="text-sm text-gray-600 mb-6">
            <span className="font-semibold text-gray-800">{deleteTarget.fullName} (@{deleteTarget.username})</span> kullanıcısını silmek istediğinizden emin misiniz? Bu işlem geri alınamaz.
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
