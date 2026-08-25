const BASE_URL = '/api';

// ── Auth Token Helper ────────────────────────────────────────────────────────
function getAuthHeaders() {
  const token = localStorage.getItem('token')
  const headers = { 'Content-Type': 'application/json' }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

async function handleResponse(response) {
  const json = await response.json().catch(() => ({}));

  // Token süresi dolmuşsa veya geçersizse login'e yönlendir
  if (response.status === 401 || response.status === 403) {
    const isLoginRequest = response.url?.includes('/api/auth/')
    if (!isLoginRequest) {
      // Sadece daha önce token varsa (oturum süresi dolmuşsa) reload yap
      // Token yoksa zaten login sayfasındayız, reload yapma (sonsuz döngü engeli)
      const hadToken = localStorage.getItem('token')
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      if (hadToken) {
        window.location.reload()
        return
      }
      throw new Error('Oturum gerekli.')
    }
  }

  if (!response.ok) {
    const errorMessage = json.message || `HTTP Hata: ${response.status}`;
    throw new Error(errorMessage);
  }
  return json.data;
}

// ── Auth Service (YENİ) ──────────────────────────────────────────────────────
export const authService = {
  async login(username, password) {
    const res = await fetch(`${BASE_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    return handleResponse(res);
  },
};

// ── Product Service ──────────────────────────────────────────────────────────
export const productService = {
  async getAllProducts() {
    const res = await fetch(`${BASE_URL}/products`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async getProductById(id) {
    const res = await fetch(`${BASE_URL}/products/${id}`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async createProduct(productData) {
    const res = await fetch(`${BASE_URL}/products`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify(productData),
    });
    return handleResponse(res);
  },

  async updateProduct(id, productData) {
    const res = await fetch(`${BASE_URL}/products/${id}`, {
      method: 'PUT',
      headers: getAuthHeaders(),
      body: JSON.stringify(productData),
    });
    return handleResponse(res);
  },

  async deleteProduct(id) {
    const res = await fetch(`${BASE_URL}/products/${id}`, {
      method: 'DELETE',
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async exportProductsExcel() {
    const token = localStorage.getItem('token');
    const headers = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`${BASE_URL}/products/export/excel`, { headers });
    if (!res.ok) {
      const json = await res.json().catch(() => ({}));
      throw new Error(json.message || 'Excel dosyası indirilemedi.');
    }
    return res.blob();
  },

  async exportProductsPdf() {
    const token = localStorage.getItem('token');
    const headers = {};
    if (token) headers['Authorization'] = `Bearer ${token}`;
    const res = await fetch(`${BASE_URL}/products/export/pdf`, { headers });
    if (!res.ok) {
      const json = await res.json().catch(() => ({}));
      throw new Error(json.message || 'PDF dosyası indirilemedi.');
    }
    return res.blob();
  },
};

// ── Category Service ─────────────────────────────────────────────────────────
export const categoryService = {
  async getAllCategories() {
    const res = await fetch(`${BASE_URL}/categories`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async getCategoryById(id) {
    const res = await fetch(`${BASE_URL}/categories/${id}`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async createCategory(categoryData) {
    const res = await fetch(`${BASE_URL}/categories`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify(categoryData),
    });
    return handleResponse(res);
  },

  async updateCategory(id, categoryData) {
    const res = await fetch(`${BASE_URL}/categories/${id}`, {
      method: 'PUT',
      headers: getAuthHeaders(),
      body: JSON.stringify(categoryData),
    });
    return handleResponse(res);
  },

  async deleteCategory(id) {
    const res = await fetch(`${BASE_URL}/categories/${id}`, {
      method: 'DELETE',
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },
};

// ── User Service ─────────────────────────────────────────────────────────────
export const userService = {
  async getAllUsers() {
    const res = await fetch(`${BASE_URL}/users`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async getUserById(id) {
    const res = await fetch(`${BASE_URL}/users/${id}`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async createUser(userData) {
    const res = await fetch(`${BASE_URL}/users`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify(userData),
    });
    return handleResponse(res);
  },

  async updateUser(id, userData) {
    const res = await fetch(`${BASE_URL}/users/${id}`, {
      method: 'PUT',
      headers: getAuthHeaders(),
      body: JSON.stringify(userData),
    });
    return handleResponse(res);
  },

  async deleteUser(id) {
    const res = await fetch(`${BASE_URL}/users/${id}`, {
      method: 'DELETE',
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  async updateProfile(profileData) {
    const res = await fetch(`${BASE_URL}/users/profile`, {
      method: 'PUT',
      headers: getAuthHeaders(),
      body: JSON.stringify(profileData),
    });
    return handleResponse(res);
  },
};

// ── Movement Service ─────────────────────────────────────────────────────────
export const movementService = {
  async createMovement(movementData) {
    const res = await fetch(`${BASE_URL}/movements`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify(movementData),
    });
    return handleResponse(res);
  },

  async createMovements(movements) {
    const res = await fetch(`${BASE_URL}/movements/bulk`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: JSON.stringify({ items: movements }),
    });
    return handleResponse(res);
  },
};

// ── İşlem Geçmişi Service (YENİ) ────────────────────────────────────────────
export const islemGecmisiService = {
  /** Tüm ham kayıtları getirir (eski endpoint, geriye dönük uyumluluk). */
  async getAll() {
    const res = await fetch(`${BASE_URL}/islemler`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  /**
   * Birleşik özet listesini getirir.
   * Toplu işlemler tek satıra çöküp toplamUrun ile gösterilir.
   */
  async getSummary() {
    const res = await fetch(`${BASE_URL}/islemler/summary`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  /**
   * Belirli bir toplu işlemin detay kayıtlarını getirir.
   * @param {string} batchId - UUID formatındaki batch kimliği
   */
  async getBatchDetail(batchId) {
    const res = await fetch(`${BASE_URL}/islemler/batch/${encodeURIComponent(batchId)}`, {
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },

  /**
   * Bir toplu işlemin stok etkisini geri alır. Bu endpoint yalnızca ADMIN içindir.
   * @param {string} batchId - UUID formatındaki batch kimliği
   */
  async rollbackBatch(batchId) {
    const res = await fetch(`${BASE_URL}/movements/rollback/${encodeURIComponent(batchId)}`, {
      method: 'POST',
      headers: getAuthHeaders(),
    });
    return handleResponse(res);
  },
};
