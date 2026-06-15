import router from '@/router'

const AUTH_TOKEN_KEY = 'auto_traces_auth_token_v1'

export function getAuthToken(): string {
  return localStorage.getItem(AUTH_TOKEN_KEY) || ''
}

export function applyAuthToken(token: string): void {
  const normalized = String(token || '').trim()
  if (normalized) {
    localStorage.setItem(AUTH_TOKEN_KEY, normalized)
  }
}

export function isLoggedIn(): boolean {
  return Boolean(getAuthToken())
}

export function isEmbedded(): boolean {
  if (typeof window === 'undefined') return false
  if (window.self !== window.top) return true
  try {
    const params = new URLSearchParams(window.location.search)
    return params.get('embed') === '1'
  } catch {
    return false
  }
}

export function installAuthFetchInterceptor(): void {
  const originalFetch = window.fetch.bind(window)
  window.fetch = (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    if (!url.includes('/api/')) {
      return originalFetch(input, init)
    }

    const headers = new Headers(init.headers || {})
    const token = getAuthToken()
    if (token && !headers.has('Authorization')) {
      headers.set('Authorization', `Bearer ${token}`)
    }

    return originalFetch(input, { ...init, headers })
  }
}

export function installAuthBridge(): void {
  window.addEventListener('message', (event) => {
    const data = event.data
    if (!data || typeof data !== 'object') return

    if (data.type === 'auto_traces_auth_token') {
      applyAuthToken(String(data.token || ''))
      return
    }

    if (data.type === 'mail_admin_navigate') {
      const rawPath = String(data.path || '').trim()
      if (!rawPath) return
      const normalized = rawPath.replace(/^\/mail-admin/, '') || '/email'
      void router.push(normalized.startsWith('/') ? normalized : `/${normalized}`)
    }
  })
}
