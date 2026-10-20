import { ExternalLink } from 'lucide-react'
import Markdown, { type Components, type Options } from 'react-markdown'
import { t } from '@/i18n/t'

/** Only `https` addresses survive; every other scheme (`javascript:`, `data:`, `http:`, relative) is dropped. */
function httpsOnly(url: string): string {
  return /^https:\/\/[^\s/]+/i.test(url) ? url : ''
}

interface Node {
  type: string
  children?: Node[]
}

/**
 * A block of raw HTML stays text (react-markdown turns it into a text node), but on its own it would sit
 * bare between paragraphs; wrapping it in a paragraph keeps it a readable, inert line.
 */
function wrapRawBlocks() {
  return (tree: Node) => {
    tree.children = tree.children?.map((node) =>
      node.type === 'raw' ? ({ type: 'element', tagName: 'p', properties: {}, children: [node] } as Node) : node,
    )
  }
}

const rehypePlugins = [wrapRawBlocks] as NonNullable<Options['rehypePlugins']>

function domain(url: string): string {
  try {
    return new URL(url).hostname
  } catch {
    return ''
  }
}

const components: Components = {
  a: ({ href, children }) =>
    href ? (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-center gap-0.5 text-primary underline underline-offset-4"
      >
        {children}
        <span className="text-caption text-muted-foreground">({domain(href)})</span>
        <ExternalLink aria-hidden="true" className="size-3.5" />
        <span className="sr-only">{t('markdown.newTab')}</span>
      </a>
    ) : (
      <span>{children}</span>
    ),
  img: ({ alt }) => <span className="text-muted-foreground">{t('markdown.image', { alt: alt ?? '' })}</span>,
}

/**
 * The Markdown view (FR-29, NFR-3, AD-21, UX-DR39): `react-markdown` without raw HTML (it shows as
 * text), only `https` links as External links with their domain (any other link is plain text), and no
 * image loaded from anywhere (its alt text shows). Nothing in the text can run.
 */
export function SafeMarkdown({ children }: { children: string }) {
  return (
    <div className="markdown flex flex-col gap-3 text-body break-words [&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_h1]:text-h2 [&_h2]:text-h3 [&_h3]:text-label [&_li]:ml-5 [&_ol]:list-decimal [&_pre]:overflow-x-auto [&_pre]:rounded-md [&_pre]:bg-muted [&_pre]:p-3 [&_ul]:list-disc">
      <Markdown urlTransform={httpsOnly} components={components} rehypePlugins={rehypePlugins}>
        {children}
      </Markdown>
    </div>
  )
}
