import { Navigate, type RouteObject } from 'react-router'
import { SignInPage } from '@/features/identity/SignInPage'
import { StartPage } from '@/features/identity/StartPage'
import { LandingPage } from '@/features/public/LandingPage'
import { PrivacyPage } from '@/features/public/PrivacyPage'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { PublicLayout } from './PublicLayout'

/**
 * The route table (EXPERIENCE.md Surfaces). Routes use Vietnamese slugs without diacritics; a
 * surface that is not built yet has no route, so its path shows the not-found screen. The signed-in
 * shell and its screens load as their own chunk, so a public visit downloads only what it shows
 * (NFR-14).
 */
export const routes: RouteObject[] = [
  {
    // Signed-in surfaces: the guard sends anyone else to PUB-2 or PUB-3.
    lazy: async () => ({ Component: (await import('./AppLayout')).AppLayout }),
    children: [
      {
        path: 'viec-can-lam',
        lazy: async () => ({ Component: (await import('@/features/notification/TodoListPage')).TodoListPage }),
      },
      {
        path: 'thong-bao',
        lazy: async () => ({
          Component: (await import('@/features/notification/NotificationsPage')).NotificationsPage,
        }),
      },
      {
        path: 'ho-so',
        lazy: async () => ({ Component: (await import('@/features/identity/ProfileStub')).ProfileStub }),
      },
    ],
  },
  {
    // PUB-6, linked from no public page (FR-2); its own header, outside the public layout.
    path: 'quan-tri/dang-nhap',
    lazy: async () => ({ Component: (await import('@/features/admin/AdminSignInPage')).AdminSignInPage }),
  },
  {
    // The Admin shell (UX-DR13): its guard sends anyone not signed in to PUB-6.
    path: 'quan-tri',
    lazy: async () => ({ Component: (await import('@/features/admin/AdminLayout')).AdminLayout }),
    children: [
      {
        index: true,
        lazy: async () => ({ Component: (await import('@/features/admin/AdminOverviewPage')).AdminOverviewPage }),
      },
      {
        path: 'the-sinh-vien',
        lazy: async () => ({ Component: (await import('@/features/admin/StudentCardsPage')).StudentCardsPage }),
      },
      {
        path: 'quan-tri-vien',
        lazy: async () => ({ Component: (await import('@/features/admin/AdministratorsPage')).AdministratorsPage }),
      },
    ],
  },
  {
    // A new Administrator's enrolment link (FR-2), emailed; outside the shell, for a visitor not signed in.
    path: 'quan-tri/kich-hoat/:token',
    lazy: async () => ({ Component: (await import('@/features/admin/EnrolmentPage')).EnrolmentPage }),
  },
  {
    element: <PublicLayout />,
    children: [
      { index: true, element: <LandingPage /> },
      { path: 'dang-nhap', element: <SignInPage /> },
      { path: 'bat-dau', element: <StartPage /> },
      { path: 'quyen-rieng-tu', element: <PrivacyPage /> },
      // The AI notice was removed with the AI (2026-10-18); its address now shows the privacy notice.
      { path: 'ai', element: <Navigate to="/quyen-rieng-tu" replace /> },
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
]
