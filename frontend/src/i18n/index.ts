import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import { DEFAULT_LOCALE, SUPPORTED_LOCALES, resolveLocale } from './locales'
import fr from './locales/fr.json'
import en from './locales/en.json'
import de from './locales/de.json'
import es from './locales/es.json'

const translations: Record<string, object> = { fr, en, de, es }

// Keep <html lang> in sync with the active language: accessibility, and
// lib/utils.ts getLocale() reads it to pick the Intl locale for dates/numbers.
function syncDocumentLanguage(language: string | undefined) {
  if (typeof document === 'undefined' || !language) return
  document.documentElement.lang = resolveLocale(language).code
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: Object.fromEntries(
      SUPPORTED_LOCALES.map((l) => [l.code, { translation: translations[l.code] }])
    ),
    fallbackLng: DEFAULT_LOCALE.code,
    supportedLngs: SUPPORTED_LOCALES.map((l) => l.code),
    ns: ['translation'],
    defaultNS: 'translation',
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'picsou-locale',
    },
  })
  .then(() => syncDocumentLanguage(i18n.resolvedLanguage ?? i18n.language))

i18n.on('languageChanged', (language) => {
  syncDocumentLanguage(language)
})

export default i18n
