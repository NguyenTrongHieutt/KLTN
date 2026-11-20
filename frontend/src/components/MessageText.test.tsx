import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MessageText } from './MessageText'

function show(text: string) {
  const { container } = render(
    <p className="whitespace-pre-line">
      <MessageText text={text} />
    </p>,
  )
  return container.querySelector('p') as HTMLParagraphElement
}

describe('MessageText (FR-46, FR-77, Story 24.4a)', () => {
  it('links a document URL with its domain, opening a new tab without the opener, and leaves the full stop outside', () => {
    const body = show('Xem https://docs.google.com/document/d/abc.')

    const link = screen.getByRole('link', { name: 'https://docs.google.com/document/d/abc' })
    expect(link).toHaveAttribute('href', 'https://docs.google.com/document/d/abc')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText('(docs.google.com)')).toBeInTheDocument()
    expect(body.textContent).toBe('Xem https://docs.google.com/document/d/abc (docs.google.com).')
  })

  it('leaves http: and javascript: as plain text', () => {
    const body = show('Bản cũ ở http://x.vn hoặc javascript:alert(1)')

    expect(screen.queryByRole('link')).toBeNull()
    expect(body.textContent).toBe('Bản cũ ở http://x.vn hoặc javascript:alert(1)')
  })

  it('keeps every trailing mark of a sentence outside the link', () => {
    for (const mark of ['.', ',', ';', ':', '!', '?', ')', ']', '}', "'", '"', '”', '’', '…', '`']) {
      const { unmount } = render(<MessageText text={`Mở https://so-dat-ban.vercel.app/dat${mark}`} />)
      expect(screen.getByRole('link')).toHaveAttribute('href', 'https://so-dat-ban.vercel.app/dat')
      unmount()
    }
    show('(xem https://figma.com/file/abc).')
    expect(screen.getByRole('link')).toHaveAttribute('href', 'https://figma.com/file/abc')
  })

  it('links two URLs in one body, each with its own domain', () => {
    const body = show('Bản thiết kế: https://www.figma.com/file/x và tài liệu https://docs.google.com/d/y')

    const links = screen.getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'https://www.figma.com/file/x',
      'https://docs.google.com/d/y',
    ])
    expect(screen.getByText('(www.figma.com)')).toBeInTheDocument()
    expect(screen.getByText('(docs.google.com)')).toBeInTheDocument()
    expect(body.textContent).toBe(
      'Bản thiết kế: https://www.figma.com/file/x (www.figma.com) và tài liệu https://docs.google.com/d/y (docs.google.com)',
    )
  })

  it('keeps the line breaks of the body', () => {
    const body = show('Dòng một\nhttps://a.vn/x\nDòng ba')

    expect(screen.getByRole('link')).toHaveAttribute('href', 'https://a.vn/x')
    expect(body.textContent).toBe('Dòng một\nhttps://a.vn/x (a.vn)\nDòng ba')
  })

  it('names the host the browser would open, never the look-alike text in front of it', () => {
    show('Đăng nhập ở https://docs.google.com@evil.example/login và https://gооgle.com/b')

    expect(screen.getAllByRole('link')).toHaveLength(2)
    expect(screen.getByText('(evil.example)')).toBeInTheDocument()
    expect(screen.getByText('(xn--ggle-55da.com)')).toBeInTheDocument()
  })

  it('links an https part inside another URL or glued to a word with its own host, and keeps markup as text', () => {
    const body = show(
      'http://x.vn/?u=https://evil.example, http://x.vn/a:https://b.example -https://a.vn tại.https://c.vn abchttps://d.vn <img src=x onerror=alert(1)>',
    )

    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      'https://evil.example',
      'https://b.example',
      'https://a.vn',
      'https://c.vn',
      'https://d.vn',
    ])
    for (const host of ['evil.example', 'b.example', 'a.vn', 'c.vn', 'd.vn']) {
      expect(screen.getByText(`(${host})`)).toBeInTheDocument()
    }
    expect(body.querySelector('img')).toBeNull()
    expect(body.textContent).toBe(
      'http://x.vn/?u=https://evil.example (evil.example), http://x.vn/a:https://b.example (b.example) ' +
        '-https://a.vn (a.vn) tại.https://c.vn (c.vn) abchttps://d.vn (d.vn) <img src=x onerror=alert(1)>',
    )
  })

  it('ends a URL at a bidi control and keeps the domain left to right', () => {
    show('Xem ‮https://evil.example/a và https://docs.example/‮moc.elgoog')

    const links = screen.getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['https://evil.example/a', 'https://docs.example/'])
    for (const link of links) {
      expect(link.getAttribute('href')).not.toMatch(/[‪-‮⁦-⁩]/)
    }
    for (const host of ['evil.example', 'docs.example']) {
      const label = screen.getByText(`(${host})`)
      expect(label.tagName).toBe('BDI')
      expect(label).toHaveAttribute('dir', 'ltr')
    }
  })

  it('marks a link as opening outside with an icon that keeps the URL as its name', () => {
    show('Xem https://docs.google.com/d/y')

    const link = screen.getByRole('link', { name: 'https://docs.google.com/d/y' })
    expect(link.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })
})
