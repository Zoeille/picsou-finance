import "@testing-library/jest-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { render, screen, waitFor } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type { ReactNode } from "react"
import type { FortuneoSessionStatus } from "@/types/api"

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock("@/lib/api-client", () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))
vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))
vi.mock("react-router-dom", () => ({ useNavigate: () => vi.fn() }))

const { SyncAllModal } = await import("./SyncAllModal")

const fortuneoAccount = {
  id: 1,
  name: "PEA",
  type: "PEA",
  provider: "Fortuneo",
  currentBalance: 1000,
  currency: "EUR",
  lastSyncedAt: "2026-07-26T10:00:00Z",
}

function status(syncStatus: FortuneoSessionStatus["syncStatus"]): FortuneoSessionStatus {
  return {
    isActive: true,
    expiresAt: null,
    syncStatus,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
  } as FortuneoSessionStatus
}

/** Routes each query the modal fires; only Fortuneo and accounts carry data. */
function arrangeApi(fortuneoSyncStatus: FortuneoSessionStatus["syncStatus"]) {
  apiGet.mockImplementation((url: string) => {
    if (url === "/fortuneo/status") return Promise.resolve({ data: status(fortuneoSyncStatus) })
    if (url === "/accounts") return Promise.resolve({ data: [fortuneoAccount] })
    if (url === "/sync/status" || url === "/crypto/exchange/status" || url === "/crypto/wallet") {
      return Promise.resolve({ data: [] })
    }
    return Promise.resolve({ data: null })
  })
}

function renderModal() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<SyncAllModal open onOpenChange={() => {}} />, { wrapper: Wrapper })
}

describe("SyncAllModal Fortuneo progress", () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
  })

  // POST /sync returns 202 immediately while the job runs for over a minute, so the
  // local optimistic flag is useless here -- without reading the backend's own status
  // the card looks idle and the sync appears to have hung.
  it("shows the backend sync as in progress while it is RUNNING", async () => {
    arrangeApi("RUNNING")
    renderModal()

    await waitFor(() => expect(screen.getByText("sync.fortuneo.syncing")).toBeInTheDocument())
  })

  it("distinguishes a queued job from a running one", async () => {
    arrangeApi("QUEUED")
    renderModal()

    await waitFor(() => expect(screen.getByText("sync.fortuneo.queued")).toBeInTheDocument())
  })

  it("shows the ordinary status once no job is in flight", async () => {
    arrangeApi("SUCCESS")
    renderModal()

    await waitFor(() => expect(screen.getByText("Fortuneo")).toBeInTheDocument())
    expect(screen.queryByText("sync.fortuneo.syncing")).not.toBeInTheDocument()
    expect(screen.queryByText("sync.fortuneo.queued")).not.toBeInTheDocument()
  })
})
