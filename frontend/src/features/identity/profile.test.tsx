import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, profileView, signedIn, signedInWithGoogle } from '@/test/fakeBackend'
import { applyTheme, DEFAULT_THEME } from '@/theme/accountTheme'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  vi.unstubAllGlobals()
  applyTheme(DEFAULT_THEME)
})

describe('ACC-1 "Bạn tự nhập" (Story 19.3, FR-3, FR-4, UX-DR44)', () => {
  it('saves a first profile with no role, says so, and sends exactly the typed fields', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedInWithGoogle, {
      'PUT /api/v1/profile': () => new Response(null, { status: 204 }),
    })
    renderAt('/ho-so')

    const typed = await screen.findByRole('region', { name: 'Bạn tự nhập' })
    await user.click(within(typed).getByRole('radio', { name: 'Doanh nghiệp' }))
    await user.type(within(typed).getByLabelText('Tên hiển thị *'), 'Tiệm bánh Lan')
    await user.type(within(typed).getByLabelText('Email liên hệ (không bắt buộc)'), 'lan@tiembanh.vn')
    await user.type(within(typed).getByLabelText('Mô tả kinh nghiệm (không bắt buộc)'), 'Bán bánh 5 năm.')
    await user.type(within(typed).getByLabelText('Số năm kinh nghiệm (không bắt buộc)'), '5')
    await user.click(within(typed).getByRole('button', { name: 'Thêm liên kết' }))
    await user.type(within(typed).getByLabelText('Liên kết 1'), 'https://tiembanhlan.vn')
    await user.click(within(typed).getByRole('button', { name: 'Thêm kho mã' }))
    await user.type(within(typed).getByLabelText('Kho mã 1'), 'lan/shop')
    // A row added and left blank is not sent.
    await user.click(within(typed).getByRole('button', { name: 'Thêm kho mã' }))
    await user.click(within(typed).getByRole('button', { name: 'Lưu hồ sơ' }))

    expect(await screen.findByRole('status')).toHaveTextContent(/Đã lưu lúc/)
    expect(await screen.findByText('Đã lưu hồ sơ.')).toBeInTheDocument()
    const request = seen.find((entry) => entry.method === 'PUT')!
    expect(await request.clone().json()).toEqual({
      displayName: 'Tiệm bánh Lan',
      kind: 'BUSINESS',
      contactEmail: 'lan@tiembanh.vn',
      experience: 'Bán bánh 5 năm.',
      yearsOfExperience: 5,
      skillIds: [],
      links: ['https://tiembanhlan.vn'],
      repositories: ['lan/shop'],
      hideStudentDetails: false,
    })
  })

  it('validates on blur and on submit, with a summary linking each field', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('CUSTOMER'))
    renderAt('/ho-so')

    const email = await screen.findByLabelText('Email liên hệ (không bắt buộc)')
    await user.type(email, 'not-an-email')
    await user.tab()
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Địa chỉ email chưa đúng.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thêm liên kết' }))
    await user.type(screen.getByLabelText('Liên kết 1'), 'http://lan.vn')

    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    const summary = screen.getByRole('alert')
    expect(summary).toHaveTextContent('Hãy kiểm tra lại 3 mục được đánh dấu.')
    expect(summary).toHaveFocus()
    expect(within(summary).getByRole('link', { name: /Tên hiển thị/ })).toHaveAttribute('href', '#displayName')
    expect(within(summary).getByRole('link', { name: /Liên kết/ })).toHaveAttribute('href', '#links')
    expect(seen.some((request) => request.method === 'PUT')).toBe(false)
    expect(email).toHaveValue('not-an-email')
  })

  it('maps the server field errors and keeps the input', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/profile': () => json(profileView({ displayName: 'Lan', contactEmail: 'lan@x.vn' })),
      'PUT /api/v1/profile': () =>
        json({ status: 400, code: 'VALIDATION', errors: [{ field: 'phone', code: 'Pattern' }] }, 400),
    })
    renderAt('/ho-so')

    const phone = await screen.findByLabelText('Số điện thoại (không bắt buộc)')
    await user.type(phone, '+84 912 345 678')
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    expect(await screen.findByText('Mục này chưa đúng dạng.')).toBeInTheDocument()
    expect(phone).toHaveAttribute('aria-invalid', 'true')
    expect(phone).toHaveValue('+84 912 345 678')
    expect(screen.getByLabelText('Tên hiển thị *')).toHaveValue('Lan')
  })

  it('marks every link "Tự khai", offers at most 5, and shows the repositories as links only', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () =>
        json(
          profileView({
            displayName: 'Minh',
            links: ['https://a.vn', 'https://b.vn', 'https://c.vn', 'https://d.vn'],
            repositories: ['a/1'],
          }),
        ),
    })
    renderAt('/ho-so')

    const typed = await screen.findByRole('region', { name: 'Bạn tự nhập' })
    expect(within(typed).getAllByText('Tự khai')).toHaveLength(4)
    await user.click(within(typed).getByRole('button', { name: 'Thêm liên kết' }))

    expect(within(typed).getAllByText('Tự khai')).toHaveLength(5)
    expect(within(typed).queryByRole('button', { name: 'Thêm liên kết' })).not.toBeInTheDocument()
    expect(within(typed).getByRole('link', { name: 'github.com/a/1' })).toHaveAttribute('href', 'https://github.com/a/1')
  })

  it('shows the student switch only with student details, off by default, and saves it', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () =>
        json(
          profileView({
            displayName: 'Minh',
            studentDetails: { name: 'Trần Quang Minh', school: 'hust.edu.vn', major: 'Khoa học máy tính' },
          }),
        ),
      'PUT /api/v1/profile': () => new Response(null, { status: 204 }),
    })
    renderAt('/ho-so')

    const toggle = await screen.findByRole('switch', { name: /Ẩn thông tin sinh viên trên hồ sơ công khai/ })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    await waitFor(() => expect(seen.some((request) => request.method === 'PUT')).toBe(true))
    const request = seen.find((entry) => entry.method === 'PUT')!
    expect(await request.clone().json()).toMatchObject({ displayName: 'Minh', hideStudentDetails: true })
  })

  it('has no student switch without student details', async () => {
    fakeBackend(signedInWithGoogle)
    renderAt('/ho-so')

    await screen.findByRole('region', { name: 'Bạn tự nhập' })
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })
})

describe('ACC-1 "Đã xác minh" (FR-4, AD-42, UX-DR90, UX-DR108)', () => {
  it('shows each verified fact with its source and the declared name and major, and no edit control', async () => {
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/profile': () =>
        json(
          profileView({
            displayName: 'Minh',
            studentDetails: { name: 'Trần Quang Minh', school: 'hust.edu.vn', major: 'Khoa học máy tính' },
            verified: {
              github: { login: 'minh-dev', since: '2026-10-20T03:00:00Z' },
              student: {
                validUntil: '2027-09-30',
                method: 'SCHOOL_EMAIL',
                school: 'hust.edu.vn',
                name: 'Trần Quang Minh',
                major: 'Khoa học máy tính',
                verifiedAt: '2026-10-19T03:00:00Z',
              },
            },
          }),
        ),
    })
    renderAt('/ho-so')

    const verified = await screen.findByRole('region', { name: 'Đã xác minh' })
    expect(within(verified).getByText('@minh-dev · Xác minh qua GitHub · 10/2026')).toBeInTheDocument()
    expect(within(verified).getByText('Sinh viên đã xác minh · Có hiệu lực đến 30/09/2027')).toBeInTheDocument()
    expect(within(verified).getByText('hust.edu.vn · Xác minh qua email trường · 10/2026')).toBeInTheDocument()
    expect(within(verified).getByText('Trần Quang Minh')).toBeInTheDocument()
    expect(within(verified).getByText('Khoa học máy tính')).toBeInTheDocument()
    expect(within(verified).getAllByText('Khai khi xác minh')).toHaveLength(2)
    // LinkedIn is not verified yet (Story 19.6).
    expect(within(verified).getAllByRole('link', { name: 'Chưa xác minh' })).toHaveLength(1)
    expect(within(verified).queryAllByRole('textbox')).toHaveLength(0)
    expect(within(verified).queryAllByRole('button')).toHaveLength(0)
    expect(within(verified).getByRole('link', { name: 'Quản lý xác minh' })).toHaveAttribute('href', '/ho-so/xac-minh')
  })

  it('shows "Chưa xác minh" at once, with no declared detail, while the student status is not current', async () => {
    fakeBackend(signedInWithGoogle, {
      'GET /api/v1/profile': () =>
        json(
          profileView({
            displayName: 'Minh',
            studentDetails: { name: 'Trần Quang Minh', school: 'hust.edu.vn', major: 'Khoa học máy tính' },
          }),
        ),
    })
    renderAt('/ho-so')

    const verified = await screen.findByRole('region', { name: 'Đã xác minh' })
    expect(within(verified).queryByText('Trần Quang Minh')).not.toBeInTheDocument()
    expect(within(verified).queryByText('Khai khi xác minh')).not.toBeInTheDocument()
    expect(within(verified).queryByText(/Sinh viên đã xác minh/)).not.toBeInTheDocument()
    expect(within(verified).getAllByRole('link', { name: 'Chưa xác minh' })).toHaveLength(3)
  })

  it('shows "Chưa xác minh" linking to ACC-2 for each verification not done', async () => {
    fakeBackend(signedInWithGoogle)
    renderAt('/ho-so')

    const verified = await screen.findByRole('region', { name: 'Đã xác minh' })
    const links = within(verified).getAllByRole('link', { name: 'Chưa xác minh' })
    expect(links).toHaveLength(3)
    for (const link of links) {
      expect(link).toHaveAttribute('href', '/ho-so/xac-minh')
    }
    expect(within(verified).queryByText('Khai khi xác minh')).not.toBeInTheDocument()
  })

  it('gives a Mentor the same one profile, with no Mentor application to open (Story 19.7)', async () => {
    fakeBackend(signedIn('MENTOR'))
    renderAt('/ho-so')

    expect(await screen.findByRole('heading', { level: 2, name: 'Bạn tự nhập' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Mở hồ sơ người hướng dẫn' })).not.toBeInTheDocument()
  })

  it('leads to ACC-2 from its tabs', async () => {
    fakeBackend(signedIn('CUSTOMER'))
    renderAt('/ho-so')

    const tabs = await screen.findByRole('navigation', { name: 'Hồ sơ và xác minh' })
    expect(within(tabs).getByRole('link', { name: 'Hồ sơ' })).toHaveAttribute('aria-current', 'page')
    expect(within(tabs).getByRole('link', { name: 'Xác minh' })).toHaveAttribute('href', '/ho-so/xac-minh')
  })
})

describe('the Theme toggle (UX-DR5)', () => {
  it('applies at once and saves to the account', async () => {
    const user = userEvent.setup()
    let saved: unknown = null
    fakeBackend(signedIn('CUSTOMER'), {
      'PUT /api/v1/account/theme': async (request) => {
        saved = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/viec-can-lam')

    await user.click(await screen.findByRole('button', { name: 'Tài khoản của octo-cat' }))
    expect(await screen.findByRole('menuitemradio', { name: 'Sáng' })).toHaveAttribute('aria-checked', 'true')
    await user.click(screen.getByRole('menuitemradio', { name: 'Tối' }))

    expect(document.documentElement).toHaveClass('dark')
    await waitFor(() => expect(saved).toEqual({ theme: 'DARK' }))
  })

  it('uses the account theme from the session', async () => {
    fakeBackend({ ...signedIn('STUDENT'), account: { ...signedIn('STUDENT').account!, theme: 'DARK' } })
    renderAt('/viec-can-lam')

    await screen.findByRole('heading', { level: 1, name: 'Việc cần làm' })
    expect(document.documentElement).toHaveClass('dark')
  })
})
