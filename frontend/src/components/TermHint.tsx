import { Info } from 'lucide-react'
import {
  createContext,
  useContext,
  useId,
  useLayoutEffect,
  useState,
  useSyncExternalStore,
  type FocusEvent,
  type ReactNode,
} from 'react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import vi from '@/i18n/vi.json'

/** A term of the Plain-Term List (`term.*` in `vi.json`, EXPERIENCE.md). */
export type TermKey = keyof typeof vi.term

interface Term {
  label: string
  hint?: string
}

/**
 * Which occurrence of each term came first inside one scope. Occurrences register in tree order
 * (layout effects run in that order), and the first registered one owns the term; the store lives
 * outside React state, so registering never sets state in an effect.
 */
class TermClaims {
  private readonly claims = new Map<string, string[]>()

  private readonly listeners = new Set<() => void>()

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  owner(term: string): string | undefined {
    return this.claims.get(term)?.[0]
  }

  register(term: string, id: string) {
    const ids = this.claims.get(term) ?? []
    if (!ids.includes(id)) {
      this.claims.set(term, [...ids, id])
      this.notify()
    }
  }

  unregister(term: string, id: string) {
    const ids = (this.claims.get(term) ?? []).filter((claimed) => claimed !== id)
    this.claims.set(term, ids)
    this.notify()
  }

  private notify() {
    this.listeners.forEach((listener) => listener())
  }
}

const TermScopeContext = createContext<TermClaims | null>(null)

const noSubscription = () => () => {}

/** One screen's scope: inside it, only the first occurrence of a term is a Term hint (UX-DR29). */
export function TermScope({ children }: { children: ReactNode }) {
  const [claims] = useState(() => new TermClaims())
  return <TermScopeContext.Provider value={claims}>{children}</TermScopeContext.Provider>
}

interface TermHintProps {
  term: TermKey
  /** Start with a lower-case letter, for a term in the middle of a sentence. */
  lower?: boolean
}

/**
 * A listed term on a Customer-facing screen (NFR-12). Its first occurrence in the scope is a
 * `button` with `aria-expanded` that opens the plain explanation in a Popover of at most 280 px:
 * a mouse opens it by hovering, keyboard focus opens it, and a tap toggles it; Esc or a tap outside
 * closes it. Later occurrences, and terms without an explanation, are plain text.
 */
export function TermHint({ term, lower = false }: TermHintProps) {
  const entry: Term = vi.term[term]
  const label = lower ? lowerFirst(entry.label) : entry.label
  const claims = useContext(TermScopeContext)
  const id = useId()
  const [open, setOpen] = useState(false)

  useLayoutEffect(() => {
    if (!claims) {
      return
    }
    claims.register(term, id)
    return () => claims.unregister(term, id)
  }, [claims, term, id])

  const owner = useSyncExternalStore(
    claims?.subscribe ?? noSubscription,
    () => claims?.owner(term),
    () => undefined,
  )
  const first = owner === undefined || owner === id

  if (!entry.hint || !first) {
    return <>{label}</>
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        openOnHover
        delay={200}
        onFocus={(event: FocusEvent<HTMLElement>) => {
          if (isKeyboardFocus(event.currentTarget)) {
            setOpen(true)
          }
        }}
        className="inline cursor-help rounded-sm underline decoration-muted-foreground decoration-dotted decoration-1 underline-offset-4"
      >
        {label}
        <Info aria-hidden="true" className="ms-0.5 hidden size-3.5 align-text-top pointer-coarse:inline" />
      </PopoverTrigger>
      <PopoverContent className="w-auto max-w-[min(280px,calc(100vw-32px))] text-body-sm">{entry.hint}</PopoverContent>
    </Popover>
  )
}

/** Vietnamese lower case of the first letter only, so a proper noun later in the term is kept. */
function lowerFirst(text: string): string {
  return text.charAt(0).toLocaleLowerCase('vi') + text.slice(1)
}

function isKeyboardFocus(element: HTMLElement): boolean {
  try {
    return element.matches(':focus-visible')
  }
  catch {
    return false
  }
}
