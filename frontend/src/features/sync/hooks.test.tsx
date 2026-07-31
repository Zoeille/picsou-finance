import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { act, renderHook, waitFor } from "@testing-library/react"
import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { BourseDirectSessionStatus } from "@/types/api"

const bourseDirectApi = vi.hoisted(() => ({
  getStatus: vi.fn(),
  initiateAuth: vi.fn(),
  completeAuth: vi.fn(),
  sync: vi.fn(),
  clearSession: vi.fn(),
}))

const cryptoExchangeApi = vi.hoisted(() => ({
  add: vi.fn(),
}))

vi.mock("./api", () => ({
  bankSyncApi: {},
  trApi: {},
  cryptoExchangeApi,
  cryptoWalletApi: {},
  finaryApi: {},
  boursoApi: {},
  bourseDirectApi,
}))

const {
  syncKeys,
  useBourseDirectStatus,
  useInitiateBourseDirectAuth,
  useCompleteBourseDirectAuth,
  useSyncBourseDirect,
  useClearBourseDirectSession,
  useAddCryptoExchange,
} = await import("./hooks")

const idleStatus: BourseDirectSessionStatus = {
  isActive: false,
  expiresAt: null,
  syncStatus: "IDLE",
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

const queuedStatus: BourseDirectSessionStatus = {
  ...idleStatus,
  isActive: true,
  syncStatus: "QUEUED",
}

function createHarness() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper }
}

describe("Bourse Direct hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("loads status through the dedicated query key", async () => {
    bourseDirectApi.getStatus.mockResolvedValue(idleStatus)
    const { client, wrapper } = createHarness()

    const { result } = renderHook(() => useBourseDirectStatus(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(bourseDirectApi.getStatus).toHaveBeenCalledOnce()
    expect(client.getQueryData(syncKeys.bourseDirect())).toEqual(idleStatus)
  })

  it("forwards credentials to authentication initiation", async () => {
    bourseDirectApi.initiateAuth.mockResolvedValue({
      processId: "process-123",
      mfaRequired: true,
      mfaType: "OTP",
    })
    const { wrapper } = createHarness()
    const { result } = renderHook(() => useInitiateBourseDirectAuth(), {
      wrapper,
    })

    await act(() =>
      result.current.mutateAsync({ login: "client-42", password: "secret" })
    )

    expect(bourseDirectApi.initiateAuth).toHaveBeenCalledWith(
      "client-42",
      "secret"
    )
  })

  it("stores the queued status returned after OTP completion", async () => {
    bourseDirectApi.completeAuth.mockResolvedValue(queuedStatus)
    const { client, wrapper } = createHarness()
    const { result } = renderHook(() => useCompleteBourseDirectAuth(), {
      wrapper,
    })

    await act(() =>
      result.current.mutateAsync({ processId: "process-123", code: "123456" })
    )

    expect(bourseDirectApi.completeAuth).toHaveBeenCalledWith(
      "process-123",
      "123456"
    )
    expect(client.getQueryData(syncKeys.bourseDirect())).toEqual(queuedStatus)
  })

  it("stores the status returned by a manual synchronization", async () => {
    bourseDirectApi.sync.mockResolvedValue(queuedStatus)
    const { client, wrapper } = createHarness()
    const { result } = renderHook(() => useSyncBourseDirect(), { wrapper })

    await act(() => result.current.mutateAsync())

    expect(bourseDirectApi.sync).toHaveBeenCalledOnce()
    expect(client.getQueryData(syncKeys.bourseDirect())).toEqual(queuedStatus)
  })

  it("invalidates broker status and account views after clearing a session", async () => {
    bourseDirectApi.clearSession.mockResolvedValue(undefined)
    const { client, wrapper } = createHarness()
    const invalidations = vi.spyOn(client, "invalidateQueries")
    const { result } = renderHook(() => useClearBourseDirectSession(), {
      wrapper,
    })

    await act(() => result.current.mutateAsync())

    expect(invalidations).toHaveBeenCalledWith({
      queryKey: syncKeys.bourseDirect(),
    })
    expect(invalidations).toHaveBeenCalledWith({ queryKey: ["accounts"] })
    expect(invalidations).toHaveBeenCalledWith({ queryKey: ["dashboard"] })
  })
})

describe("Crypto exchange hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("forwards an absent API secret unchanged for single-key exchanges", async () => {
    // Meria takes an API key alone; the field must reach the API layer as undefined so axios
    // drops it from the body rather than posting an empty string the backend would reject.
    cryptoExchangeApi.add.mockResolvedValue({})
    const { wrapper } = createHarness()
    const { result } = renderHook(() => useAddCryptoExchange(), { wrapper })

    await act(() =>
      result.current.mutateAsync({ type: "MERIA", apiKey: "meria-key", apiSecret: undefined }),
    )

    expect(cryptoExchangeApi.add).toHaveBeenCalledWith("MERIA", "meria-key", undefined)
  })
})
