import { describe, expect, it } from 'vitest'
import { domainOf, linkReady, type EvidenceLink } from './evidenceRules'

const row = (url: string, label = 'Báo cáo đánh giá'): EvidenceLink => ({ id: 'r1', kind: 'DOCUMENT', label, url })

/** Story 24.3 (AD-32, AD-35): a link's domain is what the browser opens, and the form takes no link the server refuses. */
describe('a handover link’s domain', () => {
  it('is the host the browser opens, lowercased, read from the text alone', () => {
    expect(domainOf('https://docs.google.com/document/d/bao-cao')).toBe('docs.google.com')
    expect(domainOf('  https://YouTu.be:443/dat-ban?t=3  ')).toBe('youtu.be')
    expect(domainOf('https://vi.wikipedia.org/wiki/Hà_Nội#Lịch_sử')).toBe('vi.wikipedia.org')
    expect(domainOf('https://docs.example.test/bao%20cao')).toBe('docs.example.test')
  })

  it('shows an international name as the xn-- name it is typed as, and refuses one typed in Unicode', () => {
    expect(domainOf('https://xn--bcher-kva.example/video')).toBe('xn--bcher-kva.example')
    expect(domainOf('https://bücher.example/video')).toBeUndefined()
  })

  it('refuses a backslash, which the browser reads as a slash while the text reads otherwise', () => {
    expect(domainOf('https://evil.example\\@docs.google.com/x')).toBeUndefined()
    expect(domainOf('https://docs.google.com\\x')).toBeUndefined()
  })

  it('refuses user info, which hides the host from the reader', () => {
    expect(domainOf('https://mentor' + ':' + 'khoa@docs.example.test/x')).toBeUndefined()
    expect(domainOf('https://evil.example@docs.google.com/x')).toBeUndefined()
    expect(domainOf('https://@docs.google.com/x')).toBeUndefined()
  })

  it('refuses a URL with no host the server reads', () => {
    expect(domainOf('https:///bao-cao')).toBeUndefined()
    expect(domainOf('https://my_host.example/x')).toBeUndefined()
    expect(domainOf('https://-a.example/x')).toBeUndefined()
    expect(domainOf('https://ex%61mple.com/x')).toBeUndefined()
    expect(domainOf('https://')).toBeUndefined()
  })

  it('refuses another scheme and the characters the server refuses', () => {
    expect(domainOf('http://docs.google.com/x')).toBeUndefined()
    expect(domainOf('javascript:alert(1)')).toBeUndefined()
    expect(domainOf('https://docs.example.test/bao\u3000cao')).toBeUndefined()
    expect(domainOf('https://docs.example.test/bao\u0085cao')).toBeUndefined()
    expect(domainOf('https://docs.example.test/a|b')).toBeUndefined()
    expect(domainOf('https://docs.example.test/%zz')).toBeUndefined()
    expect(domainOf('https://docs.example.test/a#b#c')).toBeUndefined()
  })
})

describe('a complete link row', () => {
  it('needs a label of 1 to 80 characters and a URL with a domain', () => {
    expect(linkReady(row('https://docs.google.com/document/d/bao-cao'))).toBe(true)
    expect(linkReady(row('https://docs.google.com/document/d/bao-cao', ' '))).toBe(false)
    expect(linkReady(row('https://docs.google.com/document/d/bao-cao', 'a'.repeat(81)))).toBe(false)
    expect(linkReady(row('https://evil.example\\@docs.google.com/x'))).toBe(false)
    expect(linkReady(row('https://my_host.example/x'))).toBe(false)
    expect(linkReady(row('https://docs.example.test/' + 'a'.repeat(475)))).toBe(false)
  })
})
