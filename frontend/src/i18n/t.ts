import vi from './vi.json'

type Leaves<T, Prefix extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${Prefix}${K}` : Leaves<T[K], `${Prefix}${K}.`>
}[keyof T & string]

/** A dotted path to a string in `vi.json`, for example `error.NOT_FOUND`. */
export type MessageKey = Leaves<typeof vi>

/**
 * The Vietnamese text for `key`, with `{name}` placeholders filled from `params`. All UI text
 * comes from `vi.json`; the backend sends codes, never sentences.
 */
export function t(key: MessageKey, params: Record<string, string | number> = {}): string {
  const message = key
    .split('.')
    .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], vi)
  if (typeof message !== 'string') {
    return key
  }
  return message.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    name in params ? String(params[name]) : placeholder,
  )
}
