import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const profile = () => json({ role: 'STUDENT', githubLogin: 'minh-dev', customer: null, student: null })

/** A code sent at 09:41 in Vietnam. */
const pending = (attemptsLeft: number, expired = false) => ({
  status: 'UNVERIFIED',
  pendingCode: {
    email: 'minh@sis.hust.edu.vn',
    sentAt: '2026-10-19T02:41:00Z',
    expiresAt: '2026-10-19T02:51:00Z',
    attemptsLeft,
    expired,
  },
})

const verified = {
  status: 'VERIFIED',
  verifiedUntil: '2027-09-30',
  verifiedAt: '2026-10-19T02:45:00Z',
  schoolEmail: 'minh@sis.hust.edu.vn',
}

const refusal = (status: number, code: string, reason?: string, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify({ status, code, ...(reason ? { reason } : {}) }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('STU-5 school email code (FR-5, UX-DR19, UX-DR70)', () => {
  it('sends a code to the school email, then asks for it with a one-time-code numeric field', async () => {
    const user = userEvent.setup()
    let view: object = { status: 'UNVERIFIED' }
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email': () => {
        view = pending(5)
        return json(view, 202)
      },
    })
    renderAt('/ho-so')

    const card = (await screen.findByRole('heading', { name: 'Xác minh sinh viên' })).closest('section')!
    expect(await within(card).findByText('Chưa xác minh')).toBeInTheDocument()
    await user.type(within(card).getByRole('textbox', { name: 'Email trường' }), 'minh@sis.hust.edu.vn')
    await user.click(within(card).getByRole('button', { name: 'Gửi mã' }))

    const codeField = await within(card).findByLabelText('Mã 6 số trong email')
    expect(codeField).toHaveAttribute('autocomplete', 'one-time-code')
    expect(codeField).toHaveAttribute('inputmode', 'numeric')
    expect(within(card).getByText('Đã gửi mã đến email này lúc 09:41.')).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Gửi mã mới' })).toBeInTheDocument()
    expect(await screen.findByText('Đã gửi mã đến minh@sis.hust.edu.vn.')).toBeInTheDocument()
    const request = seen.find((entry) => entry.method === 'POST')!
    expect(await request.clone().json()).toEqual({ email: 'minh@sis.hust.edu.vn' })
  })

  it('offers the email field when the backend writes no code as null', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () =>
        json({
          status: 'UNVERIFIED',
          method: null,
          verifiedUntil: null,
          verifiedAt: null,
          schoolEmail: null,
          pendingCode: null,
          studentCard: null,
          renewalOpen: false,
        }),
    })
    renderAt('/ho-so')

    const card = (await screen.findByRole('heading', { name: 'Xác minh sinh viên' })).closest('section')!
    expect(await within(card).findByRole('button', { name: 'Gửi mã' })).toBeInTheDocument()
    expect(within(card).queryByLabelText('Mã 6 số trong email')).not.toBeInTheDocument()
  })

  it('sends a new code, not the code entry, on Enter in the email field', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(pending(5)),
      'POST /api/v1/verification/school-email': () => json(pending(5), 202),
    })
    renderAt('/ho-so')

    await user.type(await screen.findByLabelText('Mã 6 số trong email'), '123456')
    await user.type(screen.getByRole('textbox', { name: 'Email trường' }), '{Enter}')

    await waitFor(() => expect(seen.some((request) => request.url.endsWith('/school-email'))).toBe(true))
    expect(seen.some((request) => request.url.endsWith('/confirm'))).toBe(false)
  })

  it('says an address outside the allowlist may use the student card instead', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'POST /api/v1/verification/school-email': () => refusal(422, 'GUARD_FAILED', 'EMAIL_DOMAIN_NOT_ALLOWED'),
    })
    renderAt('/ho-so')

    const field = await screen.findByRole('textbox', { name: 'Email trường' })
    await user.type(field, 'khoa@gmail.com')
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }))

    expect(
      await screen.findByText('Email này không thuộc trường trong danh sách. Bạn có thể tải thẻ sinh viên lên.'),
    ).toBeInTheDocument()
    expect(field).toHaveAttribute('aria-invalid', 'true')
  })

  it('checks the address before sending', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': profile })
    renderAt('/ho-so')

    await user.type(await screen.findByRole('textbox', { name: 'Email trường' }), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }))

    expect(await screen.findByText('Địa chỉ email chưa đúng.')).toBeInTheDocument()
    expect(seen.some((request) => request.method === 'POST')).toBe(false)
  })

  it('says when to try again after the send limit', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'POST /api/v1/verification/school-email': () =>
        refusal(429, 'RATE_LIMITED', undefined, { 'Retry-After': '2700' }),
    })
    renderAt('/ho-so')

    await user.type(await screen.findByRole('textbox', { name: 'Email trường' }), 'minh@sis.hust.edu.vn')
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }))

    expect(await screen.findByText(/^Bạn thao tác hơi nhanh\. Hãy thử lại sau \d{2}:\d{2} \d{2}\/\d{2}\.$/)).toBeInTheDocument()
  })

  it('shows the attempts left after a wrong code', async () => {
    const user = userEvent.setup()
    let view = pending(4)
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email/confirm': () => {
        view = pending(3)
        return refusal(422, 'GUARD_FAILED', 'WRONG_CODE')
      },
    })
    renderAt('/ho-so')

    await user.type(await screen.findByLabelText('Mã 6 số trong email'), '482100')
    await user.click(screen.getByRole('button', { name: 'Xác minh' }))

    expect(await screen.findByText('Mã chưa đúng. Bạn còn 3 lần thử.')).toBeInTheDocument()
  })

  it('offers a new code once the code expired', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(pending(5, true)),
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Mã đã hết hạn. Hãy gửi mã mới.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xác minh' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Gửi mã mới' })).toBeEnabled()
  })

  it('says the attempts are used up after the fifth wrong code', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(pending(0)),
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Bạn đã thử quá 5 lần. Hãy gửi mã mới.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xác minh' })).toBeDisabled()
  })

  it('verifies with the right code and shows until when', async () => {
    const user = userEvent.setup()
    let view: object = pending(5)
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email/confirm': () => {
        view = verified
        return json(view)
      },
    })
    renderAt('/ho-so')

    await user.type(await screen.findByLabelText('Mã 6 số trong email'), '4821a07')
    expect(screen.getByLabelText('Mã 6 số trong email')).toHaveValue('482107')
    await user.click(screen.getByRole('button', { name: 'Xác minh' }))

    expect(await screen.findByText('Đã xác minh đến 30/09/2027.')).toBeInTheDocument()
    const card = screen.getByRole('heading', { name: 'Xác minh sinh viên' }).closest('section')!
    expect(within(card).getByText('Đã xác minh')).toBeInTheDocument()
    expect(within(card).getByText(/email trường @sis\.hust\.edu\.vn ngày 19\/10\/2026/)).toBeInTheDocument()
    expect(within(card).queryByRole('textbox', { name: 'Email trường' })).not.toBeInTheDocument()
    const request = seen.find((entry) => entry.url.endsWith('/confirm'))!
    expect(await request.clone().json()).toEqual({ code: '482107' })
  })
})

describe('STU-5 renewal and expiry (FR-5, UX-DR27)', () => {
  const expired = {
    status: 'EXPIRED',
    verifiedUntil: '2026-09-30',
    verifiedAt: '2025-10-01T02:00:00Z',
    schoolEmail: 'minh.tq@vnu.edu.vn',
    renewalOpen: false,
  }

  it('says the verification expired, what still works, and renews from the school email field', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(expired),
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Xác minh đã hết hạn ngày 30/09')).toBeInTheDocument()
    expect(screen.getByText(/Công việc đang làm vẫn tiếp tục/)).toBeInTheDocument()
    const card = screen.getByRole('heading', { name: 'Xác minh sinh viên' }).closest('section')!
    expect(within(card).getByText('Hết hạn')).toBeInTheDocument()
    expect(within(card).getByText('Đã xác minh bằng email trường minh.tq@vnu.edu.vn đến 30/09/2026.')).toBeInTheDocument()
    const field = within(card).getByRole('textbox', { name: 'Email trường' })
    expect(field).toHaveValue('minh.tq@vnu.edu.vn')

    await user.click(screen.getByRole('button', { name: 'Gia hạn' }))
    expect(field).toHaveFocus()
  })

  it('offers renewal in the last 14 days, while still verified', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json({ ...verified, renewalOpen: true }),
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Đã xác minh đến 30/09/2027.')).toBeInTheDocument()
    expect(
      screen.getByText('Xác minh hết hạn ngày 30/09/2027. Gia hạn để tiếp tục được chọn vào nhóm thực hiện.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Email trường' })).toHaveValue('minh@sis.hust.edu.vn')
    expect(screen.getByRole('button', { name: 'Gửi mã' })).toBeEnabled()
  })

  it('shows no code form to a verified Student before renewal opens', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json({ ...verified, renewalOpen: false }),
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Đã xác minh đến 30/09/2027.')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Email trường' })).not.toBeInTheDocument()
  })
})

describe('STU-1 verification banner (UX-DR27)', () => {
  it('says an expired verification needs renewing, linking to STU-5', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/verification': () =>
        json({ status: 'EXPIRED', verifiedUntil: '2026-09-30', schoolEmail: 'minh.tq@vnu.edu.vn', renewalOpen: false }),
    })
    renderAt('/viec-can-lam')

    expect(await screen.findByText('Xác minh đã hết hạn ngày 30/09')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gia hạn' })).toHaveAttribute('href', '/ho-so')
    expect(screen.queryByText('Xác minh là sinh viên để vào nhóm của một người hướng dẫn')).not.toBeInTheDocument()
  })

  it('asks an unverified Student to verify, linking to STU-5', async () => {
    fakeBackend(signedIn('STUDENT'))
    renderAt('/viec-can-lam')

    expect(await screen.findByText('Xác minh là sinh viên để vào nhóm của một người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xác minh ngay' })).toHaveAttribute('href', '/ho-so')
  })

  it('is gone once the Student is verified, and never shown to other roles', async () => {
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/verification': () => json(verified) })
    renderAt('/viec-can-lam')
    await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Xác minh ngay' })).not.toBeInTheDocument())
  })

  it('is not asked for by a Customer', async () => {
    const seen = fakeBackend(signedIn('CUSTOMER'))
    renderAt('/viec-can-lam')

    await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })
    expect(screen.queryByRole('link', { name: 'Xác minh ngay' })).not.toBeInTheDocument()
    expect(seen.some((request) => request.url.endsWith('/api/v1/verification'))).toBe(false)
  })
})
