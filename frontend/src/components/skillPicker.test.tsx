import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

const many = Array.from({ length: 20 }, (_, index) => ({ id: `s${index}`, name: `Kỹ năng ${index}`, hint: 'x' }))

const catalog = () =>
  json({
    categories: [
      {
        id: 'c1',
        name: 'Giao diện web',
        skills: [
          { id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' },
          { id: 'ts', name: 'TypeScript', hint: 'JavaScript có kiểm tra kiểu.' },
        ],
      },
      {
        id: 'c6',
        name: 'Bảo mật và kiểm thử',
        skills: [
          { id: 'owasp', name: 'Bảo mật web (OWASP Top 10)', hint: 'Chặn lỗi bảo mật thường gặp.' },
          { id: 'unit', name: 'Kiểm thử đơn vị', hint: 'Mã tự kiểm tra.' },
        ],
      },
      { id: 'c9', name: 'Khác', skills: many },
    ],
  })

const profile = (skills: { id: string; name: string; active: boolean }[] = []) =>
  json({
    role: 'STUDENT',
    githubLogin: 'minh-dev',
    customer: null,
    student: { showNameAndSchool: false, repositories: [], skills },
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the Skill picker on STU-5 (FR-4, UX-DR75, SG-50)', () => {
  it('filters without accents or letter case, groups by Category, and saves the chosen IDs', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => profile(),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile/student': async (request) => {
        body = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/ho-so')

    const input = await screen.findByRole('combobox', { name: 'Kỹ năng' })
    await user.type(input, 'BAO MAT')
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByRole('option', { name: 'Bảo mật web (OWASP Top 10)' })).toBeInTheDocument()
    expect(within(listbox).queryByRole('option', { name: 'React' })).not.toBeInTheDocument()
    expect(within(listbox).getByText('Bảo mật và kiểm thử')).toBeInTheDocument()
    await user.click(within(listbox).getByRole('option', { name: 'Bảo mật web (OWASP Top 10)' }))

    expect(await screen.findByRole('button', { name: 'Bỏ Bảo mật web (OWASP Top 10)' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))
    await waitFor(() => expect(body).toMatchObject({ skillIds: ['owasp'] }))
  })

  it('says when nothing matches', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': () => profile(), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    await user.type(await screen.findByRole('combobox', { name: 'Kỹ năng' }), 'cobol')

    expect(await screen.findByText('Không có kỹ năng “cobol” trong danh sách.')).toBeInTheDocument()
  })

  it('waits for the IME to finish a letter before filtering', async () => {
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': () => profile(), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')
    const input = await screen.findByRole('combobox', { name: 'Kỹ năng' })

    fireEvent.compositionStart(input)
    fireEvent.change(input, { target: { value: 'ks' } })
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByRole('option', { name: 'React' })).toBeInTheDocument()

    fireEvent.change(input, { target: { value: 'kiể' } })
    fireEvent.compositionEnd(input)
    await waitFor(() => expect(within(listbox).queryByRole('option', { name: 'React' })).not.toBeInTheDocument())
    expect(within(listbox).getByRole('option', { name: 'Kiểm thử đơn vị' })).toBeInTheDocument()
  })

  it('stops at 20 and keeps a retired Skill as a token', async () => {
    const user = userEvent.setup()
    const twenty = [
      { id: 'retired', name: 'Kỹ năng cũ', active: false },
      ...many.slice(0, 19).map((skill) => ({ id: skill.id, name: skill.name, active: true })),
    ]
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': () => profile(twenty), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    expect(await screen.findByRole('button', { name: 'Bỏ Kỹ năng cũ' })).toBeInTheDocument()
    expect(screen.getByText('(ngừng dùng)')).toBeInTheDocument()
    await user.click(screen.getByRole('combobox', { name: 'Kỹ năng' }))
    const listbox = await screen.findByRole('listbox')
    expect(await screen.findByText('Tối đa 20 kỹ năng')).toBeInTheDocument()
    expect(within(listbox).getByRole('option', { name: 'React' })).toHaveAttribute('aria-disabled', 'true')
  })

  it("shows the server's Skill refusal on the field", { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => profile([{ id: 'react', name: 'React', active: true }]),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile/student': () =>
        new Response(JSON.stringify({ status: 400, code: 'VALIDATION', reason: 'SKILL_LIMIT', limit: 20 }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    })
    renderAt('/ho-so')

    await user.click(await screen.findByRole('button', { name: 'Lưu hồ sơ' }))

    expect(await screen.findAllByText('Tối đa 20 kỹ năng.')).not.toHaveLength(0)
    expect(screen.getByRole('combobox', { name: 'Kỹ năng' })).toHaveAttribute('aria-invalid', 'true')
  })
})
