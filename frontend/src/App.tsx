import { useEnvironment } from './features/shared/useEnvironment'
import { t, type MessageKey } from './i18n/t'

const ENVIRONMENT_NAMES: Record<string, MessageKey> = {
  dev: 'environment.dev',
  prod: 'environment.prod',
}

export default function App() {
  const environment = useEnvironment()
  const nameKey = ENVIRONMENT_NAMES[environment.data?.name ?? '']

  return (
    <main>
      <h1>{t('app.name')}</h1>
      {environment.isPending && <p>{t('state.loading')}</p>}
      {environment.isError && <p role="alert">{t('error.unexpected')}</p>}
      {nameKey && (
        <p>
          {t('environment.label')}: {t(nameKey)}
        </p>
      )}
    </main>
  )
}
