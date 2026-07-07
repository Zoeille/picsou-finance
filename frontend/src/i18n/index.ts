import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import fr from './locales/fr.json'
import en from './locales/en.json'

function syncDocumentLanguage(language: string | undefined) {
  if (typeof document === 'undefined' || !language) return
  document.documentElement.lang = language.startsWith('fr') ? 'fr' : 'en'
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      fr: { translation: fr },
      en: { translation: en },
    },
    fallbackLng: 'fr',
    supportedLngs: ['fr', 'en'],
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
