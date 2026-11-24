import { Search, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { FIND_WORK_PATH, KEYWORD_PARAM, MAX_KEYWORD, PAGE_PARAM } from '@/features/job/findWorkUrl'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

/**
 * The top bar's search, from 768 px (ui-ux-pro-max `search-accessible`): the one search field of the app. A keyword
 * and Enter open Tìm việc with that keyword in its URL. On Tìm việc itself it is that page's Search bar (the layout
 * of 2026-11-24, where a second search card stood under the heading before): it shows the keyword the URL holds, a
 * new one keeps the page's filters and starts at the first page, and "Xóa từ khóa" returns to the whole list. The
 * filters and the rate-limit message stay the page's.
 */
export function TopSearch() {
  const { pathname, search } = useLocation()
  const onFindWork = pathname === FIND_WORK_PATH
  const current = onFindWork ? (new URLSearchParams(search).get(KEYWORD_PARAM) ?? '').trim().slice(0, MAX_KEYWORD) : ''
  // A new keyword in the URL (a search, "Xóa từ khóa", the back button) starts the field over at that keyword.
  return <Field key={`${onFindWork}:${current}`} current={current} filters={onFindWork ? search : ''} />
}

function Field({ current, filters }: { current: string; filters: string }) {
  const navigate = useNavigate()
  const [keyword, setKeyword] = useState(current)
  const go = (q: string | undefined) => {
    const params = new URLSearchParams(filters)
    params.delete(PAGE_PARAM)
    if (q === undefined) {
      params.delete(KEYWORD_PARAM)
    } else {
      params.set(KEYWORD_PARAM, q)
    }
    const query = params.toString()
    navigate(query === '' ? FIND_WORK_PATH : `${FIND_WORK_PATH}?${query}`)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const q = keyword.trim()
    if (q !== '') {
      go(q)
    }
  }
  return (
    <form role="search" aria-label={t('shell.search')} onSubmit={submit} className="relative w-full max-w-xl">
      <Search
        aria-hidden="true"
        className="pointer-events-none absolute top-1/2 left-3.5 size-4.5 -translate-y-1/2 text-muted-foreground"
      />
      <input
        type="search"
        aria-label={t('shell.search')}
        maxLength={MAX_KEYWORD}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        placeholder={t('shell.searchPlaceholder')}
        className={cn(
          'h-10 w-full rounded-full border border-transparent bg-muted pl-10 text-body-sm text-foreground placeholder:text-muted-foreground hover:border-border-strong focus:border-ring focus:bg-card [&::-webkit-search-cancel-button]:hidden',
          current === '' ? 'pr-4' : 'pr-11',
        )}
      />
      {current !== '' && (
        <button
          type="button"
          aria-label={t('screen.jobPost.clearKeyword')}
          onClick={() => go(undefined)}
          className="absolute top-1/2 right-1 flex size-8 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground hover:bg-card hover:text-foreground"
        >
          <X aria-hidden="true" className="size-4.5" />
        </button>
      )}
    </form>
  )
}
