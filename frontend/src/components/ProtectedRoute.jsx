import { useAuth } from '../contexts/AuthContext'
import LoginPage from '../pages/LoginPage'

export default function ProtectedRoute({ children, adminOnly = false }) {
  const { user, loading, isAdmin } = useAuth()

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="text-lg text-gray-500">Yükleniyor...</div>
      </div>
    )
  }

  if (!user) {
    return <LoginPage />
  }

  if (adminOnly && !isAdmin) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="text-center">
          <p className="text-lg font-semibold text-gray-800">Erişim Reddedildi</p>
          <p className="text-sm text-gray-500 mt-1">Bu sayfayı görüntüleme yetkiniz yok.</p>
        </div>
      </div>
    )
  }

  return children
}
