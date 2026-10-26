import { render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, profileView, signedIn } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'

/**
 * The planted-payload suite (SG-30, NFR-3, AD-21): XSS strings saved in every user-written profile
 * field render as inert text in every view that shows them. Later epics add their fields and views.
 */

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
  it('stays text in every typed ACC-1 field, link, and repository link', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/profile': () =>
        json(
          profileView({
            kind: 'BUSINESS',
            displayName: payload,
            contactEmail: payload,
            phone: payload,
            experience: payload,
            links: [payload],
            repositories: [payload, `owner/${payload}`],
            skills: [{ id: 'retired-skill', name: payload, active: false }],
          }),
        ),
    })
    renderAt('/ho-so')

    expect(await screen.findByLabelText('Tên hiển thị *')).toHaveValue(payload)
    expect(screen.getByLabelText('Email liên hệ (không bắt buộc)')).toHaveValue(payload)
    expect(screen.getByLabelText('Số điện thoại (không bắt buộc)')).toHaveValue(payload)
    expect(screen.getByLabelText('Mô tả kinh nghiệm (không bắt buộc)')).toHaveValue(payload)
    expect(screen.getByLabelText('Liên kết 1')).toHaveValue(payload)
    for (const link of screen.getAllByRole('link', { name: /^github\.com\// })) {
      expect(link.getAttribute('href')).toMatch(/^https:\/\/github\.com\//)
    }
    expectInert()
  })

  it('stays text in the ACC-1 verified zone and the student details', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () =>
        json(
          profileView({
            displayName: 'Minh',
            studentDetails: { name: payload, school: payload, year: 3 },
            verified: {
              github: { login: payload, since: '2026-10-20T03:00:00Z' },
              student: { validUntil: '2027-09-30', method: 'SCHOOL_EMAIL' },
            },
          }),
        ),
    })
    renderAt('/ho-so')

    expect(await screen.findByText((text) => text.startsWith(`@${payload} · Xác minh qua GitHub`))).toBeInTheDocument()
    expect(screen.getAllByText(payload)).toHaveLength(2)
    expectInert()
  })

  it('stays text in the ACC-2 school email while a code waits', async () => {
    const email = `${payload}@sis.hust.edu.vn`
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/verification': () =>
        json({
          status: 'UNVERIFIED',
          pendingCode: { email, sentAt: '2026-10-19T02:41:00Z', expiresAt: '2026-10-19T02:51:00Z', attemptsLeft: 5, expired: false },
        }),
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByRole('textbox', { name: 'Email trường' })).toHaveValue(email)
    expectInert()
  })

  it('stays text in the ACC-2 student verified line', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/verification': () =>
        json({ status: 'VERIFIED', verifiedUntil: '2027-09-30', verifiedAt: '2026-10-19T02:45:00Z', schoolEmail: `x@${payload}` }),
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByText((text) => text.includes(`@${payload}`))).toBeInTheDocument()
    expectInert()
  })
})
