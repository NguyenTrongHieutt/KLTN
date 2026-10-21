import { render, screen } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, signedIn } from '@/test/fakeBackend'

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
})

describe('"Đăng nhập lại bằng GitHub" (FR-13, author decision 2026-10-21)', () => {
  it('starts GitHub sign-in again for a signed-in Customer and returns to the to-do list', { timeout: 15_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'))
    renderAt('/dang-nhap-lai')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đăng nhập lại bằng GitHub' })).toBeInTheDocument()
    expect(screen.getByText(/Bạn không mất dữ liệu nào/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đăng nhập lại bằng GitHub' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/github?quay-lai=%2Fviec-can-lam',
    )
  })
})
