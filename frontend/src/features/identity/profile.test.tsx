import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { applyTheme } from '@/theme/accountTheme'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const customerProfile = (customer: Record<string, unknown> | null = null) => () =>
  json({ role: 'CUSTOMER', githubLogin: 'octo-cat', customer, student: null })

afterEach(() => {
  vi.unstubAllGlobals()
  applyTheme('SYSTEM')
})

describe('CUS-11 (FR-3, UX-DR44, UX-DR43)', () => {
  it('saves the profile, says so on the page and in a toast, and sends exactly the fields', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/profile': customerProfile(),
      'PUT /api/v1/profile/customer': () => new Response(null, { status: 204 }),
    })
    renderAt('/ho-so')

    await user.click(await screen.findByRole('radio', { name: 'Doanh nghiệp' }))
    await user.type(screen.getByLabelText('Tên hiển thị'), 'Tiệm bánh Lan')
    await user.type(screen.getByLabelText('Email liên hệ'), 'lan@tiembanh.vn')
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    expect(await screen.findByRole('status')).toHaveTextContent(/Đã lưu lúc/)
    expect(await screen.findByText('Đã lưu hồ sơ.')).toBeInTheDocument()
    const request = seen.find((entry) => entry.method === 'PUT')!
    expect(await request.clone().json()).toEqual({
      kind: 'BUSINESS',
      displayName: 'Tiệm bánh Lan',
      contactEmail: 'lan@tiembanh.vn',
    })
  })

  it('validates on blur and on submit, with a summary linking each field', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/profile': customerProfile() })
    renderAt('/ho-so')

    const email = await screen.findByLabelText('Email liên hệ')
    await user.type(email, 'not-an-email')
    await user.tab()
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Địa chỉ email chưa đúng.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    const summary = screen.getByRole('alert')
    expect(summary).toHaveTextContent('Hãy kiểm tra lại 2 mục được đánh dấu.')
    expect(summary).toHaveFocus()
    expect(within(summary).getByRole('link', { name: /Tên hiển thị/ })).toHaveAttribute('href', '#displayName')
    expect(seen.some((request) => request.method === 'PUT')).toBe(false)
    expect(email).toHaveValue('not-an-email')
  })

  it('maps the server field errors and keeps the input', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/profile': customerProfile({ kind: 'INDIVIDUAL', displayName: 'Lan', contactEmail: 'lan@x.vn' }),
      'PUT /api/v1/profile/customer': () =>
        json({ status: 400, code: 'VALIDATION', errors: [{ field: 'phone', code: 'Pattern' }] }, 400),
    })
    renderAt('/ho-so')

    const phone = await screen.findByLabelText('Số điện thoại (không bắt buộc)')
    await user.type(phone, '+84 912 345 678')
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    expect(await screen.findByText('Mục này chưa đúng dạng.')).toBeInTheDocument()
    expect(phone).toHaveAttribute('aria-invalid', 'true')
    expect(phone).toHaveValue('+84 912 345 678')
    expect(screen.getByLabelText('Tên hiển thị')).toHaveValue('Lan')
  })
})

describe('STU-5, the profile part (FR-4, FR-51)', () => {
  it('shows the GitHub username read-only, hides name and school by default, and saves repositories', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => json({ role: 'STUDENT', githubLogin: 'minh-dev', customer: null, student: null }),
      'PUT /api/v1/profile/student': () => new Response(null, { status: 204 }),
    })
    renderAt('/ho-so')

    expect(await screen.findByText('minh-dev')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /GitHub/ })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Hiện họ tên và trường/ })).not.toBeChecked()
    await user.type(screen.getByLabelText('Trường'), 'ĐH Bách khoa')
    await user.selectOptions(screen.getByLabelText('Năm học'), '3')
    await user.click(screen.getByRole('button', { name: 'Thêm kho mã' }))
    await user.type(screen.getByLabelText('Kho mã 1'), 'minh/shop-app')
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    await waitFor(() => expect(seen.some((request) => request.method === 'PUT')).toBe(true))
    const request = seen.find((entry) => entry.method === 'PUT')!
    expect(await request.clone().json()).toEqual({
      school: 'ĐH Bách khoa',
      yearOfStudy: 3,
      showNameAndSchool: false,
      repositories: ['minh/shop-app'],
    })
  })

  it('offers at most 5 repositories', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () =>
        json({
          role: 'STUDENT',
          githubLogin: 'minh-dev',
          customer: null,
          student: { showNameAndSchool: true, repositories: ['a/1', 'a/2', 'a/3', 'a/4'] },
        }),
    })
    renderAt('/ho-so')

    await user.click(await screen.findByRole('button', { name: 'Thêm kho mã' }))

    expect(screen.queryByRole('button', { name: 'Thêm kho mã' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'github.com/a/1' })).toHaveAttribute('href', 'https://github.com/a/1')
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
    expect(await screen.findByRole('menuitemradio', { name: 'Theo hệ thống' })).toHaveAttribute('aria-checked', 'true')
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
