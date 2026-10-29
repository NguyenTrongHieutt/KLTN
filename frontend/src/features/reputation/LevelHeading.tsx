import type { ReactNode } from 'react'
import type { HeadingLevel } from './headingLevel'

/** A section heading at `level`: 2 on its own page, deeper inside a Sheet and its sections. */
export function LevelHeading({
  level,
  id,
  className,
  children,
}: {
  level: HeadingLevel
  id: string
  className: string
  children: ReactNode
}) {
  switch (level) {
    case 2:
      return (
        <h2 id={id} className={className}>
          {children}
        </h2>
      )
    case 3:
      return (
        <h3 id={id} className={className}>
          {children}
        </h3>
      )
    case 4:
      return (
        <h4 id={id} className={className}>
          {children}
        </h4>
      )
    default:
      return (
        <h5 id={id} className={className}>
          {children}
        </h5>
      )
  }
}
