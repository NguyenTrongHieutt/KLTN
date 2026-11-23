import type { ReactNode } from 'react'
import vi from '@/i18n/vi.json'

/** A term of the vocabulary (`term.*` in `vi.json`). */
export type TermKey = keyof typeof vi.term

/**
 * Kept so screens can still mark where a group of terms is read; since 2026-11-23 it adds nothing (EXPERIENCE.md,
 * "UX changes of 2026-11-23": Term hints are removed).
 */
export function TermScope({ children }: { children: ReactNode }) {
  return <>{children}</>
}

interface TermHintProps {
  term: TermKey
  /** Start with a lower-case letter, for a term in the middle of a sentence. */
  lower?: boolean
}

/**
 * A term from the vocabulary, as plain text. Since 2026-11-23 the UI uses technical terms as they are, with no
 * dotted underline and no explanation popover (DESIGN.md, Term hint; PRD NFR-12 revised).
 */
export function TermHint({ term, lower = false }: TermHintProps) {
  const label: string = vi.term[term].label
  return <>{lower ? lowerFirst(label) : label}</>
}

/** Vietnamese lower case of the first letter only, so a proper noun later in the term is kept. */
function lowerFirst(text: string): string {
  return text.charAt(0).toLocaleLowerCase('vi') + text.slice(1)
}
