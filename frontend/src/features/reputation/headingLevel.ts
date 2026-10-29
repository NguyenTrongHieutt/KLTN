/** A heading level a section can sit at: 2 on its own page, deeper inside a Sheet and its sections. */
export type HeadingLevel = 2 | 3 | 4 | 5

/** One level below `level`, at most 5. */
export function below(level: HeadingLevel): HeadingLevel {
  return level === 5 ? 5 : ((level + 1) as HeadingLevel)
}
