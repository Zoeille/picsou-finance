import { useTranslation } from 'react-i18next'
import { resolveLocale } from '@/i18n/locales'

/**
 * Reactive BCP 47 locale for `Intl.*` formatting (e.g. "fr-FR").
 * Unlike `getLocale()` in lib/utils.ts, this re-renders the component
 * when the user switches language.
 */
export function useIntlLocale(): string {
  const { i18n } = useTranslation()
  return resolveLocale(i18n.language).intlLocale
}
