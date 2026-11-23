import { clsx, type ClassValue } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * tailwind-merge told about the DESIGN.md type ramp (`--text-*` in index.css). Without it, a size such as
 * `text-caption` reads as a text color, and a later `text-primary` silently drops the size.
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: ['display', 'display-mobile', 'h1', 'h2', 'h3', 'body', 'body-sm', 'label', 'caption', 'tag', 'mono'],
    },
  },
})

/** Joins class names and lets later Tailwind utilities override earlier ones (shadcn/ui). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
