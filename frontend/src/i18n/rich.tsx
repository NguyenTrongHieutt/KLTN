import { Fragment, type ReactNode } from 'react'
import { t, type MessageKey } from './t'

/**
 * The text of `key` with each `{name}` placeholder replaced by an element, for a sentence that
 * holds a Term hint or a link. Placeholders without an element stay as written.
 */
export function rich(key: MessageKey, parts: Record<string, ReactNode>): ReactNode {
  return t(key)
    .split(/(\{\w+\})/)
    .map((piece, index) => {
      const name = /^\{(\w+)\}$/.exec(piece)?.[1]
      return name !== undefined && name in parts ? <Fragment key={index}>{parts[name]}</Fragment> : piece
    })
}
