import { apiUrl, readAuthError } from '@kinotic-ai/frontend-common'
import type { CheckoutKey, FreeLicenseCertification, Plan, PlanInfo, SiteInfo } from './model'

/** The REST routes anyone may call, and the customer sign-in routes. */

/** A refused request, with the HTTP status the server answered. */
export class ApiError extends Error {
    constructor(message: string, readonly status: number) {
        super(message)
    }
}

async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(apiUrl(path), { credentials: 'include' })
    if (!res.ok) {
        throw new ApiError(await readAuthError(res, `${path} failed with ${res.status}`), res.status)
    }
    return res.json()
}

async function postJson<T>(path: string, body: unknown, fallback: string): Promise<T | null> {
    const res = await fetch(apiUrl(path), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    })
    if (!res.ok) {
        throw new ApiError(await readAuthError(res, fallback), res.status)
    }
    return res.status === 204 || res.status === 202 ? null : res.json()
}

export function fetchSite(): Promise<SiteInfo> {
    return getJson('/api/site')
}

export function fetchPlans(): Promise<PlanInfo[]> {
    return getJson('/api/plans')
}

/**
 * A purchase. A signed-in customer buys for its own account and names no licensee; anyone else names the licensee and
 * contact for the address the page confirmed.
 */
export interface CheckoutRequest {
    plan: Plan
    legalName?: string
    contactName?: string
    acceptTerms: boolean
}

/** Starts the purchase; resolves to the Stripe Checkout URL to send the browser to. */
export async function startCheckout(request: CheckoutRequest): Promise<string> {
    const result = await postJson<{ url: string }>('/api/checkout', request, 'The purchase could not be started')
    return result!.url
}

export function fetchCheckoutKey(sessionId: string): Promise<CheckoutKey> {
    return getJson(`/api/checkout/${encodeURIComponent(sessionId)}/key`)
}

/**
 * A free license request. A signed-in customer requests for its own account and names no organization; anyone else
 * names the organization and contact for the address the page confirmed.
 */
export interface FreeRequest extends FreeLicenseCertification {
    organization?: string
    contactName?: string
    acceptTerms: boolean
}

export async function submitFreeRequest(request: FreeRequest): Promise<string> {
    const result = await postJson<{ id: string }>('/api/free-requests', request, 'The request could not be submitted')
    return result!.id
}

/** Asks for a code; every address is sent one, so the answer says nothing about whether it has an account. */
export async function requestSignInCode(email: string): Promise<void> {
    await postJson('/api/auth/code/request', { email }, 'The code could not be sent')
}

/**
 * Confirms the address with the emailed code. When it belongs to an account the page is now signed in to it;
 * otherwise the page may sign up with it.
 */
export async function verifySignInCode(email: string, code: string): Promise<{ account: boolean }> {
    const result = await postJson<{ account: boolean }>('/api/auth/code/verify', { email, code }, 'The code is not valid')
    return result!
}
