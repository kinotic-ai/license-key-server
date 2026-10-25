import { apiUrl, readAuthError } from '@kinotic-ai/frontend-common'
import type { CheckoutKey, FreeLicenseCertification, Plan, PlanInfo, SiteInfo } from './model'

/** The REST routes anyone may call, and the customer sign-in routes. */

async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(apiUrl(path), { credentials: 'include' })
    if (!res.ok) {
        throw new Error(await readAuthError(res, `${path} failed with ${res.status}`))
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
        throw new Error(await readAuthError(res, fallback))
    }
    return res.status === 204 || res.status === 202 ? null : res.json()
}

export function fetchSite(): Promise<SiteInfo> {
    return getJson('/api/site')
}

export function fetchPlans(): Promise<PlanInfo[]> {
    return getJson('/api/plans')
}

export interface CheckoutRequest {
    plan: Plan
    legalName: string
    contactName: string
    contactEmail: string
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

export interface FreeRequest extends FreeLicenseCertification {
    organization: string
    contactName: string
    contactEmail: string
    acceptTerms: boolean
}

export async function submitFreeRequest(request: FreeRequest): Promise<string> {
    const result = await postJson<{ id: string }>('/api/free-requests', request, 'The request could not be submitted')
    return result!.id
}

/** Asks for a sign-in code; the server answers the same way whether or not the address has an account. */
export async function requestSignInCode(email: string): Promise<void> {
    await postJson('/api/auth/code/request', { email }, 'The code could not be sent')
}

/** Signs in with the emailed code; on success the server has set the session cookie. */
export async function verifySignInCode(email: string, code: string): Promise<void> {
    await postJson('/api/auth/code/verify', { email, code }, 'The code is not valid')
}
