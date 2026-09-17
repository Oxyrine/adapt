import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

// Registering this is what makes Chrome treat the app as installable (manifest link is in
// index.html; this is the other half of the requirement). Skipped in dev to avoid a stale SW
// fighting Vite's HMR -- only matters for the built/deployed site anyway.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // Installability just won't trigger -- the app still works fine as a regular page.
    })
  })
}
