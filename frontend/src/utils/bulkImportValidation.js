const MAX_TEXT_LENGTH = 255

function normalizedText(value) {
  return String(value ?? '').trim().replace(/\s+/g, ' ')
}

function hasControlCharacter(value) {
  return [...value].some((character) => {
    const codePoint = character.codePointAt(0)
    return codePoint < 32 || codePoint === 127
  })
}

export function validatePreviewRow(row) {
  const errors = []
  const productName = normalizedText(row.productName)
  const rawProductCode = String(row.productCode ?? '').trim()
  const productCode = normalizedText(rawProductCode)

  if (!productName) {
    errors.push('Ürün adı boş olamaz')
  } else if (productName.length > MAX_TEXT_LENGTH) {
    errors.push('Ürün adı en fazla 255 karakter olabilir')
  }

  if (productCode.length > MAX_TEXT_LENGTH) {
    errors.push('Ürün kodu en fazla 255 karakter olabilir')
  }
  if (rawProductCode && hasControlCharacter(rawProductCode)) {
    errors.push('Ürün kodu satır sonu veya kontrol karakteri içeremez')
  }
  if (productCode && productName && productCode.toLocaleLowerCase('tr-TR') === productName.toLocaleLowerCase('tr-TR')) {
    errors.push('Ürün kodu, ürün adından kopyalanamaz')
  }

  const quantity = Number(row.quantity)
  if (row.quantity === null || row.quantity === '' || !Number.isInteger(quantity) || quantity <= 0) {
    errors.push('Miktar pozitif bir tam sayı olmalıdır')
  }
  if (row.price !== null && row.price !== undefined && Number(row.price) < 0) {
    errors.push('Fiyat 0\'dan küçük olamaz')
  }

  return {
    ...row,
    valid: errors.length === 0 && !row.reviewRequired,
    errorMessage: errors.length > 0 ? errors.join('; ') : null,
  }
}

export function applyPreviewEdit(row, field, value) {
  const updated = { ...row, [field]: value }
  if (
    field === 'productName' &&
    normalizedText(value) !== normalizedText(row.productName)
  ) {
    updated.reviewRequired = false
    updated.reviewMessage = null
    updated.documentReviewRequired = false
    updated.documentReviewMessage = null
  }
  if (field === 'productName') {
    updated.matchStatus = null
    updated.matchReviewRequired = false
    updated.resolutionType = null
    updated.selectedProductId = null
    updated.matchFingerprint = null
    updated.matchCandidates = []
  }
  return validatePreviewRow(updated)
}
