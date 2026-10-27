import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { anonymous, fakeBackend } from '@/test/fakeBackend'
import { routes } from './routes'

vi.mock('@/features/shared/useEnvironment', () => ({
  useEnvironment: () => ({ data: { name: 'dev' }, isPending: false, isError: false }),
}))

beforeEach(() => {
  fakeBackend(anonymous)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('public routes (UX-DR65, UX-DR70, UX-DR26)', () => {
  it('PUB-1 explains vibefix, the 7 Stages, both notices, and the roles in three cards above one Google sign-in', async () => {
    renderAt('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Hoàn thiện ứng dụng bạn đã làm bằng AI' })).toBeInTheDocument()
    expect(document.title).toBe('Giới thiệu · vibefix')
    const stages = screen.getByRole('list', { name: 'Bảy bước của một công việc' })
    expect(stages.querySelectorAll('li')).toHaveLength(7)
    expect(stages).toHaveTextContent('Bàn giao và nghiệm thu')
    expect(screen.getByText('Tiền trên vibefix hiện là tiền thử nghiệm')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Dự án của bạn phải công khai trên GitHub/ })).toBeInTheDocument()
    const roles = screen.getByRole('region', { name: 'Bạn đến vibefix để làm gì?' })
    expect(within(roles).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual([
      'Bạn có sản phẩm cần hoàn thiện',
      'Bạn muốn làm người hướng dẫn',
      'Bạn muốn làm thành viên nhóm thực hiện',
    ])
    expect(within(roles).getAllByRole('link')).toHaveLength(1)
    expect(within(roles).getByRole('link', { name: 'Đăng nhập bằng Google' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/google',
    )
  })

  it('explains the unavoidable terms on their first occurrence', async () => {
    renderAt('/')

    await screen.findByRole('heading', { level: 1 })
    for (const term of ['GitHub', 'công khai', 'người hướng dẫn', 'giai đoạn', 'nghiệm thu']) {
      expect(screen.getAllByRole('button', { name: term })).toHaveLength(1)
    }
  })

  it('has a skip link, landmarks, and the privacy link in the footer', async () => {
    renderAt('/')

    await screen.findByRole('heading', { level: 1 })
    const skip = screen.getByRole('link', { name: 'Bỏ qua đến nội dung chính' })
    expect(skip).toHaveAttribute('href', '#noi-dung-chinh')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'noi-dung-chinh')
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Quyền riêng tư' })).toHaveAttribute('href', '/quyen-rieng-tu')
  })

  it('moves focus to the new page heading after a route change, not on the first load', async () => {
    const user = userEvent.setup()
    renderAt('/')
    await screen.findByRole('heading', { level: 1 })
    expect(document.activeElement).toBe(document.body)

    await user.click(screen.getByRole('link', { name: 'Quyền riêng tư' }))

    const heading = await screen.findByRole('heading', { level: 1, name: 'Quyền riêng tư' })
    await waitFor(() => expect(document.activeElement).toBe(heading))
    expect(document.title).toBe('Quyền riêng tư · vibefix')
  })

  it('PUB-7 shows the current notice with its version number', async () => {
    renderAt('/quyen-rieng-tu')

    expect(await screen.findByText('Thông báo quyền riêng tư · phiên bản 3.1')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'vibefix thu thập gì' })).toBeInTheDocument()
  })

  it('/ai redirects to the privacy notice and replaces the history entry', async () => {
    const router = renderAt('/ai')

    expect(await screen.findByRole('heading', { level: 1, name: 'Quyền riêng tư' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/quyen-rieng-tu')
    expect(router.state.historyAction).toBe('REPLACE')
  })

  it('an unknown route is the one not-found screen', async () => {
    renderAt('/khong-co')

    expect(await screen.findByRole('heading', { level: 1, name: 'Không tìm thấy nội dung này.' })).toBeInTheDocument()
    expect(screen.getByText('Có thể nó đã bị xóa hoặc bạn không có quyền xem.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về trang chính' })).toHaveAttribute('href', '/')
    expect(document.title).toBe('Không tìm thấy · vibefix')
  })

  it('injects no style element at run time on any route', { timeout: 15_000 }, async () => {
    for (const path of ['/', '/dang-nhap', '/quyen-rieng-tu', '/khong-co']) {
      renderAt(path)
      await screen.findAllByRole('heading', { level: 1 })
      for (const button of screen.queryAllByRole('button')) {
        await userEvent.click(button)
      }
    }

    expect(document.querySelectorAll('style')).toHaveLength(0)
    expect(document.adoptedStyleSheets ?? []).toHaveLength(0)
  })
})
