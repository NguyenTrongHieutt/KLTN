import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import './index.css'
import { createQueryClient } from './app/queryClient'
import { routes } from './app/routes'
import { applyTheme, DEFAULT_THEME } from './theme/accountTheme'

// Light before sign-in, as on the frames; a signed-in account's choice takes over (UX-DR5).
applyTheme(DEFAULT_THEME)

const queryClient = createQueryClient()
const router = createBrowserRouter(routes)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
