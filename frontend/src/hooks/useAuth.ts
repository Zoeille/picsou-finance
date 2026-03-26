import { useState, useCallback } from 'react'
import { authApi } from '../lib/api'

interface AuthState {
  username: string | null
  isAuthenticated: boolean
}

// Simple auth state — persists username in sessionStorage
function getInitialState(): AuthState {
  const username = sessionStorage.getItem('picsou_user')
  return { username, isAuthenticated: !!username }
}

let globalState = getInitialState()
const listeners = new Set<() => void>()

function setState(next: AuthState) {
  globalState = next
  listeners.forEach(fn => fn())
}

export function useAuth() {
  const [, rerender] = useState(0)

  // Subscribe to global state changes
  const subscribe = useCallback(() => {
    const fn = () => rerender(n => n + 1)
    listeners.add(fn)
    return () => listeners.delete(fn)
  }, [])

  const login = async (username: string, password: string) => {
    const data = await authApi.login(username, password)
    sessionStorage.setItem('picsou_user', data.username)
    setState({ username: data.username, isAuthenticated: true })
  }

  const logout = async () => {
    await authApi.logout().catch(() => {})
    sessionStorage.removeItem('picsou_user')
    setState({ username: null, isAuthenticated: false })
  }

  return {
    ...globalState,
    login,
    logout,
    subscribe,
  }
}
