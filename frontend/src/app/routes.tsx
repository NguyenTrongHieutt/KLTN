import { Navigate, type RouteObject } from 'react-router'
import { TodoStub } from '@/features/home/TodoStub'
import { SignInPage } from '@/features/identity/SignInPage'
import { StartPage } from '@/features/identity/StartPage'
import { MentorProfileStub } from '@/features/mentor/MentorProfileStub'
import { LandingPage } from '@/features/public/LandingPage'
import { PrivacyPage } from '@/features/public/PrivacyPage'
import { NotFoundScreen } from '@/features/shared/NotFoundScreen'
import { AppLayout } from './AppLayout'
import { PublicLayout } from './PublicLayout'

/**
 * The route table (EXPERIENCE.md Surfaces). Routes use Vietnamese slugs without diacritics; a
 * surface that is not built yet has no route, so its path shows the not-found screen.
 */
export const routes: RouteObject[] = [
  {
    // Signed-in surfaces: the guard sends anyone else to PUB-2 or PUB-3.
    element: <AppLayout />,
    children: [
      { path: 'viec-can-lam', element: <TodoStub /> },
      { path: 'ho-so', element: <MentorProfileStub /> },
    ],
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
