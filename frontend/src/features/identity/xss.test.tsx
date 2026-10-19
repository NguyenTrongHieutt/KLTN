import { render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

/**
 * The planted-payload suite (SG-30, NFR-3, AD-21): XSS strings saved in every user-written profile
 * field render as inert text in every view that shows them. Later epics add their fields and views.
 */
export const PAYLOADS = [
  '<img src=x onerror=alert(1)>',
  '<script>alert(1)</script>',
  '"><svg onload=alert(1)>',
  'javascript:alert(1)',
  "'-alert(1)-'",
  '<a href="javascript:alert(1)">bấm</a>',
]

function renderAt(path: string) {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />
    </QueryClientProvider>,
  )
}

/** The attributes a browser follows as a URL. */
const URL_ATTRIBUTES = ['href', 'src', 'action', 'formaction', 'xlink:href', 'srcset']

/** No element, attribute, or URL in the page came from a payload. */
function expectInert() {
  const main = document.querySelector('main')!
  expect(main.querySelectorAll('script, img, svg[onload], iframe, object, embed')).toHaveLength(0)
  for (const element of Array.from(main.querySelectorAll('*'))) {
    for (const attribute of Array.from(element.attributes)) {
      expect(attribute.name.startsWith('on'), `${element.tagName} ${attribute.name}`).toBe(false)
      if (URL_ATTRIBUTES.includes(attribute.name)) {
        expect(attribute.value.trim().toLowerCase(), `${element.tagName} ${attribute.name}`).not.toMatch(/^(?:javascript|data|vbscript):/)
      }
    }
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe.each(PAYLOADS)('the payload %s', (payload) => {
  it('stays text in every CUS-11 field', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/profile': () =>
        json({
          role: 'CUSTOMER',
          githubLogin: payload,
          customer: { kind: 'BUSINESS', displayName: payload, contactEmail: payload, phone: payload },
          student: null,
        }),
    })
    renderAt('/ho-so')

    expect(await screen.findByLabelText('Tên hiển thị')).toHaveValue(payload)
    expect(screen.getByLabelText('Email liên hệ')).toHaveValue(payload)
    expect(screen.getByLabelText('Số điện thoại (không bắt buộc)')).toHaveValue(payload)
    expectInert()
  })

  it('stays text in every STU-5 field and repository link', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () =>
        json({
          role: 'STUDENT',
          githubLogin: payload,
          customer: null,
          student: {
            realName: payload,
            school: payload,
            linkedinUrl: payload,
            showNameAndSchool: true,
            repositories: [payload, `owner/${payload}`],
          },
        }),
    })
    renderAt('/ho-so')

    expect(await screen.findByLabelText('Họ và tên')).toHaveValue(payload)
    expect(screen.getByLabelText('Trường')).toHaveValue(payload)
    expect(screen.getByLabelText('LinkedIn (không bắt buộc)')).toHaveValue(payload)
    expect(screen.getByText(payload, { selector: 'p' })).toBeInTheDocument()
    for (const link of screen.getAllByRole('link', { name: /^github\.com\// })) {
      expect(link.getAttribute('href')).toMatch(/^https:\/\/github\.com\//)
    }
    expectInert()
  })

  it('stays text in the STU-5 school email while a code waits', async () => {
    const email = `${payload}@sis.hust.edu.vn`
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => json({ role: 'STUDENT', githubLogin: 'minh-dev', customer: null, student: null }),
      'GET /api/v1/verification': () =>
        json({
          status: 'UNVERIFIED',
          pendingCode: { email, sentAt: '2026-10-19T02:41:00Z', expiresAt: '2026-10-19T02:51:00Z', attemptsLeft: 5, expired: false },
        }),
    })
    renderAt('/ho-so')

    expect(await screen.findByRole('textbox', { name: 'Email trường' })).toHaveValue(email)
    expectInert()
  })

  it('stays text in the STU-5 verified line', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => json({ role: 'STUDENT', githubLogin: 'minh-dev', customer: null, student: null }),
      'GET /api/v1/verification': () =>
        json({ status: 'VERIFIED', verifiedUntil: '2027-09-30', verifiedAt: '2026-10-19T02:45:00Z', schoolEmail: `x@${payload}` }),
    })
    renderAt('/ho-so')

    expect(await screen.findByText((text) => text.includes(`@${payload}`))).toBeInTheDocument()
    expectInert()
  })
})
