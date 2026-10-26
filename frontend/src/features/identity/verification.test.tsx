import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn, signedInWithGoogle } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const ADDRESS = 'minh@sis.hust.edu.vn'

/** A code sent at 09:41 in Vietnam to an address of hust.edu.vn; the view never holds the address. */
const pending = (attemptsLeft: number, expired = false) => ({
  status: 'UNVERIFIED',
  renewalOpen: false,
  pendingCode: {
    school: 'hust.edu.vn',
    sentAt: '2026-10-19T02:41:00Z',
    expiresAt: '2026-10-19T02:51:00Z',
    attemptsLeft,
    expired,
  },
})

const verified = {
  status: 'VERIFIED',
  method: 'SCHOOL_EMAIL',
  school: 'hust.edu.vn',
  name: 'Trần Quang Minh',
  major: 'Khoa học máy tính',
  verifiedUntil: '2027-09-30',
  verifiedAt: '2026-10-19T02:45:00Z',
  renewalOpen: false,
}

const refusal = (status: number, code: string, reason?: string, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify({ status, code, ...(reason ? { reason } : {}) }), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  })

/** ACC-2's student email row. */
async function row() {
  return (await screen.findByRole('heading', { name: 'Email sinh viên' })).closest('section')!
}

/** "Xác minh email sinh viên" (or another start), then N-26's "Đồng ý và tiếp tục". */
async function startAndAccept(user: ReturnType<typeof userEvent.setup>, button = 'Xác minh email sinh viên') {
  await user.click(await within(await row()).findByRole('button', { name: button }))
  const notice = await screen.findByRole('dialog', { name: 'Trước khi xác minh email sinh viên' })
  await user.click(within(notice).getByRole('button', { name: 'Đồng ý và tiếp tục' }))
}

/** Fills the first verification's form and presses "Gửi mã". */
async function fillAndSend(user: ReturnType<typeof userEvent.setup>, address = ADDRESS) {
  await user.type(screen.getByRole('textbox', { name: 'Email trường' }), address)
  await user.type(screen.getByRole('textbox', { name: 'Họ và tên' }), 'Trần Quang Minh')
  await user.type(screen.getByRole('textbox', { name: 'Ngành học' }), 'Khoa học máy tính')
  await user.click(screen.getByRole('button', { name: 'Gửi mã' }))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ACC-2 student email row (FR-5, FR-80, AD-42, UX-DR93, UX-DR94)', () => {
  it('shows N-26 first, then the form, and sends a code whose step names the school, never the address', async () => {
    const user = userEvent.setup()
    let view: object = { status: 'UNVERIFIED', renewalOpen: false }
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email': () => {
        view = pending(5)
        return json(view, 202)
      },
    })
    renderAt('/ho-so/xac-minh')

    const section = await row()
    expect(within(section).getByText('Không bắt buộc')).toBeInTheDocument()
    expect(await within(section).findByText('Chưa xác minh')).toBeInTheDocument()
    expect(within(section).queryByRole('textbox', { name: 'Email trường' })).not.toBeInTheDocument()

    await user.click(within(section).getByRole('button', { name: 'Xác minh email sinh viên' }))
    const notice = await screen.findByRole('dialog', { name: 'Trước khi xác minh email sinh viên' })
    expect(within(notice).getByText(/vibefix không lưu địa chỉ email này/)).toBeInTheDocument()
    expect(within(notice).getByText(/một email chỉ xác minh cho một tài khoản tại một thời điểm/)).toBeInTheDocument()
    expect(within(notice).getByText(/“Trường” hiện trên hồ sơ là tên miền của email này/)).toBeInTheDocument()
    expect(within(notice).getByText('Lưu ý khi xác minh email sinh viên · phiên bản 1.0')).toBeInTheDocument()
    await user.click(within(notice).getByRole('button', { name: 'Đồng ý và tiếp tục' }))

    const field = within(section).getByRole('textbox', { name: 'Email trường' })
    await waitFor(() => expect(field).toHaveFocus())
    expect(within(section).getAllByText('Sẽ được khóa sau khi xác minh')).toHaveLength(2)
    await fillAndSend(user)

    const codeField = await within(section).findByLabelText('Mã 6 số trong email')
    expect(codeField).toHaveAttribute('autocomplete', 'one-time-code')
    expect(codeField).toHaveAttribute('inputmode', 'numeric')
    expect(
      within(section).getByText('Đã gửi mã 6 số đến email trường của bạn tại hust.edu.vn. Mã hết hạn sau 10 phút.'),
    ).toBeInTheDocument()
    expect(within(section).getByText('Còn 5 lần thử.')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Gửi mã mới' })).toBeInTheDocument()
    expect(await screen.findByText('Đã gửi mã đến email của hust.edu.vn.')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain(ADDRESS)
    const request = seen.find((entry) => entry.method === 'POST')!
    expect(await request.clone().json()).toEqual({
      noticeVersion: '1.0',
      email: ADDRESS,
      name: 'Trần Quang Minh',
      major: 'Khoa học máy tính',
    })
  })

  it('closes N-26 without starting when the person waits', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle)
    renderAt('/ho-so/xac-minh')

    await user.click(await within(await row()).findByRole('button', { name: 'Xác minh email sinh viên' }))
    await user.click(within(await screen.findByRole('dialog')).getByText('Để sau'))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByRole('textbox', { name: 'Email trường' })).not.toBeInTheDocument()
  })

  it('checks the address, the name, and the major before sending', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInWithGoogle)
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user)

    await user.type(screen.getByRole('textbox', { name: 'Email trường' }), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }))

    expect(await screen.findByText('Địa chỉ email chưa đúng.')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Họ và tên' })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('textbox', { name: 'Ngành học' })).toHaveAttribute('aria-invalid', 'true')
    expect(seen.some((request) => request.method === 'POST')).toBe(false)
  })

  it.each([
    ['EMAIL_DOMAIN_NOT_ALLOWED', 'Email này không thuộc trường nào trong danh sách của vibefix. Hãy dùng email do trường cấp.'],
    ['STUDENT_EMAIL_IN_USE', 'Email này đã xác minh cho một tài khoản khác.'],
  ])('shows %s inline on the email field', async (reason, text) => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, {
      'POST /api/v1/verification/school-email': () => refusal(422, 'GUARD_FAILED', reason),
    })
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user)

    await fillAndSend(user, 'khoa@gmail.com')

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Email trường' })).toHaveAttribute('aria-invalid', 'true')
  })

  it('says when to try again after the send limit', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, {
      'POST /api/v1/verification/school-email': () =>
        refusal(429, 'RATE_LIMITED', undefined, { 'Retry-After': '2700' }),
    })
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user)

    await fillAndSend(user)

    expect(await screen.findByText(/^Bạn thao tác hơi nhanh\. Hãy thử lại sau \d{2}:\d{2} \d{2}\/\d{2}\.$/)).toBeInTheDocument()
  })

  it('shows the attempts left after a wrong code', async () => {
    const user = userEvent.setup()
    let view = pending(4)
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email/confirm': () => {
        view = pending(3)
        return refusal(422, 'GUARD_FAILED', 'WRONG_CODE')
      },
    })
    renderAt('/ho-so/xac-minh')

    await user.type(await screen.findByLabelText('Mã 6 số trong email'), '482100')
    await user.click(screen.getByRole('button', { name: 'Xác minh' }))

    expect(await screen.findByText('Mã chưa đúng. Bạn còn 3 lần thử.')).toBeInTheDocument()
  })

  it('offers a new code once the code expired', async () => {
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(pending(5, true)),
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByText('Mã đã hết hạn. Hãy gửi mã mới.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xác minh' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Gửi mã mới' })).toBeEnabled()
  })

  it('says the attempts are used up after the fifth wrong code', async () => {
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(pending(0)),
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByText('Bạn đã thử quá 5 lần. Hãy gửi mã mới.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xác minh' })).toBeDisabled()
  })

  it('asks for the address again for a new code after a reload, since vibefix keeps none, after N-26', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(pending(5)),
    })
    renderAt('/ho-so/xac-minh')

    await user.click(await screen.findByRole('button', { name: 'Gửi mã mới' }))
    // N-26 was not accepted in this visit, so it is shown before the version is sent again.
    const notice = await screen.findByRole('dialog', { name: 'Trước khi xác minh email sinh viên' })
    await user.click(within(notice).getByRole('button', { name: 'Đồng ý và tiếp tục' }))

    const field = await screen.findByRole('textbox', { name: 'Email trường' })
    await waitFor(() => expect(field).toHaveFocus())
    expect(field).toHaveValue('')
    expect(seen.some((request) => request.method === 'POST')).toBe(false)
  })

  it('shows N-26 again when the backend holds a newer version', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, {
      'POST /api/v1/verification/school-email': () => refusal(409, 'STALE'),
    })
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user)

    await fillAndSend(user)

    expect(await screen.findByRole('dialog', { name: 'Trước khi xác minh email sinh viên' })).toBeInTheDocument()
  })

  it('says why a new code was refused, over what the view says about the old one', async () => {
    const user = userEvent.setup()
    let view: object = { status: 'UNVERIFIED', renewalOpen: false }
    let sends = 0
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email': () => {
        sends += 1
        if (sends > 1) {
          return refusal(429, 'RATE_LIMITED', undefined, { 'Retry-After': '2700' })
        }
        view = pending(5, true)
        return json(view, 202)
      },
    })
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user)
    await fillAndSend(user)
    expect(await screen.findByText('Mã đã hết hạn. Hãy gửi mã mới.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Gửi mã mới' }))

    expect(await screen.findByText(/^Bạn thao tác hơi nhanh\. Hãy thử lại sau/)).toBeInTheDocument()
    expect(screen.queryByText('Mã đã hết hạn. Hãy gửi mã mới.')).not.toBeInTheDocument()
  })

  it('tells a refusal that spends the code as a toast, since the code step goes', async () => {
    const user = userEvent.setup()
    let view: object = pending(5)
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email/confirm': () => {
        view = { status: 'UNVERIFIED', renewalOpen: false }
        return refusal(422, 'GUARD_FAILED', 'STUDENT_EMAIL_IN_USE')
      },
    })
    renderAt('/ho-so/xac-minh')

    await user.type(await screen.findByLabelText('Mã 6 số trong email'), '482100')
    await user.click(screen.getByRole('button', { name: 'Xác minh' }))

    expect(await within(await row()).findByRole('button', { name: 'Xác minh email sinh viên' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Mã 6 số trong email')).not.toBeInTheDocument()
    expect(screen.getAllByText('Email này đã xác minh cho một tài khoản khác.').length).toBeGreaterThan(0)
  })

  it('sends again what this visit sent on "Gửi mã mới"', async () => {
    const user = userEvent.setup()
    let view: object = { status: 'UNVERIFIED', renewalOpen: false }
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email': () => {
        view = pending(5)
        return json(view, 202)
      },
    })
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user)
    await fillAndSend(user)

    await user.click(await screen.findByRole('button', { name: 'Gửi mã mới' }))

    await waitFor(() => expect(seen.filter((request) => request.method === 'POST')).toHaveLength(2))
    const second = seen.filter((request) => request.method === 'POST')[1]
    expect(await second.clone().json()).toMatchObject({ email: ADDRESS, name: 'Trần Quang Minh' })
  })

  it('verifies with the right code and shows the badge, the school, the month, until when, and the locked details', async () => {
    const user = userEvent.setup()
    let view: object = pending(5)
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email/confirm': () => {
        view = verified
        return json(view)
      },
    })
    renderAt('/ho-so/xac-minh')

    await user.type(await screen.findByLabelText('Mã 6 số trong email'), '4821a07')
    expect(screen.getByLabelText('Mã 6 số trong email')).toHaveValue('482107')
    await user.click(screen.getByRole('button', { name: 'Xác minh' }))

    expect(await screen.findByText('Đã xác minh qua email trường hust.edu.vn · đến 30/09/2027')).toBeInTheDocument()
    const section = await row()
    expect(within(section).getAllByText('Sinh viên đã xác minh').length).toBeGreaterThan(0)
    expect(within(section).getByText('hust.edu.vn')).toBeInTheDocument()
    expect(within(section).getByText('Qua email trường · 10/2026')).toBeInTheDocument()
    expect(within(section).getByText('30/09/2027')).toBeInTheDocument()
    expect(within(section).getByText('Trần Quang Minh')).toBeInTheDocument()
    expect(within(section).getAllByText('Khai khi xác minh')).toHaveLength(2)
    expect(within(section).queryByRole('textbox')).not.toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Bỏ xác minh' })).toBeInTheDocument()
    const request = seen.find((entry) => entry.url.endsWith('/confirm'))!
    expect(await request.clone().json()).toEqual({ code: '482107' })
  })

  it('"Bỏ xác minh" asks first, says the encrypted signal stays, and deletes the facts', async () => {
    const user = userEvent.setup()
    let view: object = verified
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'DELETE /api/v1/verification/student': () => {
        view = { status: 'UNVERIFIED', renewalOpen: false }
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/ho-so/xac-minh')

    await user.click(await within(await row()).findByRole('button', { name: 'Bỏ xác minh' }))
    const dialog = await screen.findByRole('dialog', { name: 'Bỏ xác minh sinh viên?' })
    expect(within(dialog).getByText(/Dấu vân tay một chiều của email vẫn được giữ 24 tháng/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Bỏ xác minh' }))

    expect(await screen.findByText('Đã bỏ xác minh sinh viên.')).toBeInTheDocument()
    expect(seen.some((request) => request.method === 'DELETE' && request.url.endsWith('/verification/student'))).toBe(true)
    expect(await within(await row()).findByRole('button', { name: 'Xác minh email sinh viên' })).toBeInTheDocument()
  })
})

describe('ACC-2 student email renewal and expiry (FR-5, UX-DR93)', () => {
  it('reads "Sắp hết hạn" in the last 14 days, and "Gia hạn" asks for the address only', async () => {
    const user = userEvent.setup()
    let view: object = { ...verified, renewalOpen: true }
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email': () => {
        view = { ...verified, renewalOpen: true, pendingCode: pending(5).pendingCode }
        return json(view, 202)
      },
    })
    renderAt('/ho-so/xac-minh')

    const section = await row()
    expect(await within(section).findByText('Sắp hết hạn')).toBeInTheDocument()
    expect(
      within(section).getByText('Huy hiệu sinh viên hết hạn ngày 30/09/2027. Gia hạn để giữ huy hiệu trên hồ sơ.'),
    ).toBeInTheDocument()
    await startAndAccept(user, 'Gia hạn')

    expect(within(section).getByText(/Họ tên và ngành giữ nguyên/)).toBeInTheDocument()
    expect(within(section).queryByRole('textbox', { name: 'Họ và tên' })).not.toBeInTheDocument()
    expect(within(section).queryByRole('textbox', { name: 'Ngành học' })).not.toBeInTheDocument()
    await user.type(within(section).getByRole('textbox', { name: 'Email trường' }), 'minh.k23@vnu.edu.vn')
    await user.click(within(section).getByRole('button', { name: 'Gửi mã' }))

    expect(await within(section).findByLabelText('Mã 6 số trong email')).toBeInTheDocument()
    const request = seen.find((entry) => entry.method === 'POST')!
    expect(await request.clone().json()).toEqual({ noticeVersion: '1.0', email: 'minh.k23@vnu.edu.vn' })
  })

  it('asks for the name and major on the email field when the verification lapsed before the renewal was sent', async () => {
    const user = userEvent.setup()
    let view: object = { ...verified, renewalOpen: true }
    const seen = fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/school-email': () => {
        view = { status: 'EXPIRED', method: 'SCHOOL_EMAIL', verifiedUntil: '2027-09-30', renewalOpen: false }
        return json(
          { status: 400, code: 'VALIDATION', errors: [{ field: 'name', code: 'NotBlank' }, { field: 'major', code: 'NotBlank' }] },
          400,
        )
      },
    })
    renderAt('/ho-so/xac-minh')
    await startAndAccept(user, 'Gia hạn')

    const field = screen.getByRole('textbox', { name: 'Email trường' })
    await user.type(field, 'minh.k23@vnu.edu.vn')
    await user.click(screen.getByRole('button', { name: 'Gửi mã' }))

    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    // The row is read again: the form now asks for what a new verification declares.
    expect(await screen.findByRole('textbox', { name: 'Họ và tên' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Ngành học' })).toBeInTheDocument()
    expect(seen.filter((request) => request.method === 'GET' && request.url.endsWith('/api/v1/verification')).length)
      .toBeGreaterThan(1)
  })

  it('shows no form before renewal opens', async () => {
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () => json(verified),
    })
    renderAt('/ho-so/xac-minh')

    expect(await screen.findByText('Qua email trường · 10/2026')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Email trường' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gia hạn' })).not.toBeInTheDocument()
  })

  it('reads "Chưa xác minh" under the expired alert, and verifying again declares the details again', async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/verification': () =>
        json({ status: 'EXPIRED', method: 'SCHOOL_EMAIL', verifiedUntil: '2026-09-30', renewalOpen: false }),
    })
    renderAt('/ho-so/xac-minh')

    const section = await row()
    expect(await within(section).findByText('Huy hiệu sinh viên đã hết hạn ngày 30/09')).toBeInTheDocument()
    expect(within(section).getByText('Chưa xác minh')).toBeInTheDocument()
    expect(within(section).queryByText('Sinh viên đã xác minh')).not.toBeInTheDocument()
    await startAndAccept(user, 'Xác minh lại')

    expect(within(section).getByRole('textbox', { name: 'Họ và tên' })).toBeInTheDocument()
    expect(within(section).getByRole('textbox', { name: 'Ngành học' })).toBeInTheDocument()
  })
})

describe('STU-1 verification banner (UX-DR27)', () => {
  it('says an expired verification needs renewing, linking to ACC-2', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/verification': () => json({ status: 'EXPIRED', verifiedUntil: '2026-09-30', renewalOpen: false }),
    })
    renderAt('/viec-can-lam')

    expect(await screen.findByText('Xác minh đã hết hạn ngày 30/09')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gia hạn' })).toHaveAttribute('href', '/ho-so/xac-minh')
    expect(screen.queryByText('Xác minh là sinh viên để vào nhóm của một người hướng dẫn')).not.toBeInTheDocument()
  })

  it('asks an unverified Student to verify, linking to ACC-2', async () => {
    fakeBackend(signedIn('STUDENT'))
    renderAt('/viec-can-lam')

    expect(await screen.findByText('Xác minh là sinh viên để vào nhóm của một người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xác minh ngay' })).toHaveAttribute('href', '/ho-so/xac-minh')
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
