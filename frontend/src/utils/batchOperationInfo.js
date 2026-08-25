const DOCUMENT_IMPORT_INFO = {
  text: 'Toplu İçe Aktarım',
  description: 'Toplu içe aktarım',
  icon: '📦',
  badgeColor: 'bg-violet-500/20 text-violet-600 border-violet-500/30',
  modalBadgeColor: 'bg-violet-500/20 text-violet-400 border-violet-500/30',
  iconColor: 'bg-violet-500/20',
  countColor: 'bg-violet-100 text-violet-700 border-violet-200',
  buttonColor: 'bg-violet-600 hover:bg-violet-700 shadow-violet-500/20',
  rowHover: 'hover:bg-violet-50/60',
  quantityColor: 'bg-violet-500/15 text-violet-300',
  quantityPrefix: '+',
  pdfHeadColor: [79, 70, 229],
}

const KARMA_ISLEM_INFO = {
  text: 'Karma İşlem',
  description: 'Karma stok işlemi (giriş + çıkış)',
  icon: '🔀',
  badgeColor: 'bg-blue-500/20 text-blue-700 border-blue-500/30',
  modalBadgeColor: 'bg-blue-500/20 text-blue-400 border-blue-500/30',
  iconColor: 'bg-blue-500/20',
  countColor: 'bg-blue-100 text-blue-700 border-blue-200',
  buttonColor: 'bg-blue-600 hover:bg-blue-700 shadow-blue-500/20',
  rowHover: 'hover:bg-blue-50/60',
  quantityColor: 'bg-blue-500/15 text-blue-300',
  quantityPrefix: '',
  pdfHeadColor: [37, 99, 235],
}

const BATCH_OPERATION_INFO = {
  STOK_GIRIS: {
    text: 'Toplu Stok Girişi',
    description: 'Toplu stok girişi',
    icon: '📥',
    badgeColor: 'bg-emerald-500/20 text-emerald-700 border-emerald-500/30',
    modalBadgeColor: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30',
    iconColor: 'bg-emerald-500/20',
    countColor: 'bg-emerald-100 text-emerald-700 border-emerald-200',
    buttonColor: 'bg-emerald-600 hover:bg-emerald-700 shadow-emerald-500/20',
    rowHover: 'hover:bg-emerald-50/60',
    quantityColor: 'bg-emerald-500/15 text-emerald-300',
    quantityPrefix: '+',
    pdfHeadColor: [5, 150, 105],
  },
  STOK_CIKIS: {
    text: 'Toplu Stok Çıkışı',
    description: 'Toplu stok çıkışı',
    icon: '📤',
    badgeColor: 'bg-red-500/20 text-red-700 border-red-500/30',
    modalBadgeColor: 'bg-red-500/20 text-red-400 border-red-500/30',
    iconColor: 'bg-red-500/20',
    countColor: 'bg-red-100 text-red-700 border-red-200',
    buttonColor: 'bg-red-600 hover:bg-red-700 shadow-red-500/20',
    rowHover: 'hover:bg-red-50/60',
    quantityColor: 'bg-red-500/15 text-red-300',
    quantityPrefix: '−',
    pdfHeadColor: [220, 38, 38],
  },
  PDF_YUKLEME: DOCUMENT_IMPORT_INFO,
  TOPLU_ICE_AKTARIM: DOCUMENT_IMPORT_INFO,
  KARMA_ISLEM: KARMA_ISLEM_INFO,
}

export function getBatchOperationInfo(islemTipi) {
  return BATCH_OPERATION_INFO[islemTipi] || DOCUMENT_IMPORT_INFO
}
