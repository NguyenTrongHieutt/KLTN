import { useEffect } from 'react'
import { t } from '@/i18n/t'

/** Sets the document title to "{screen} · vibefix" (Accessibility Floor). */
export function usePageTitle(screen: string) {
  useEffect(() => {
    document.title = t('shell.title', { screen })
  }, [screen])
}
