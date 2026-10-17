import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { domAnimation, LazyMotion, MotionConfig } from 'motion/react'
import { App } from './app/App'
import './app/styles.css'
import 'react-diff-view/style/index.css'
import './app/workspace.css'
import './app/tech-theme.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <MotionConfig reducedMotion="user"><LazyMotion features={domAnimation} strict><App /></LazyMotion></MotionConfig>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
