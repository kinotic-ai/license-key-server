import { apiUrl, readAuthError } from '@kinotic-ai/frontend-common'
import type { PendingProviderSignUp, SiteInfo } from './model'

/** What a page reads before it signs in, and the customer sign-in, sign-up and password routes. */

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

/** Signs the page in with a contact's email and password. */
export async function signIn(email: string, password: string): Promise<void> {
    await postJson('/api/auth/login', { email, password }, 'The email or password is not valid')
}

/**
 * Asks for the link that opens the sign-up form; an address with an account is sent a link to set a new password
 * instead, so the answer is the same either way. {@code referer} is the site path the link's page continues to.
 */
export async function requestSignUp(email: string, referer?: string): Promise<void> {
    await postJson('/api/auth/signup', { email, referer }, 'The link could not be sent')
}

/** Creates the account of the address the sign-up link was emailed to, and signs the page in to it. */
export async function completeSignUp(token: string, organization: string, contactName: string, password: string): Promise<void> {
    await postJson('/api/auth/signup/complete', { token, organization, contactName, password }, 'The account could not be created')
}

/** The sign-up a provider started in this browser; refused with 404 when there is none or it has expired. */
export function fetchPendingProviderSignUp(): Promise<PendingProviderSignUp> {
    return getJson('/api/auth/signup/provider')
}

/**
 * Creates the account of the sign-up a provider started in this browser for `email`, the address the form shows, and
 * signs the page in to it; refused when the browser's pending sign-up is for another address.
 */
export async function completeProviderSignUp(email: string, organization: string, contactName: string): Promise<void> {
    await postJson('/api/auth/signup/provider/complete', { email, organization, contactName }, 'The account could not be created')
}

/** Asks for a link to set a new password; the answer is the same whether or not the address has an account. */
export async function requestPasswordReset(email: string, referer?: string): Promise<void> {
    await postJson('/api/auth/password/forgot', { email, referer }, 'The link could not be sent')
}

/** Sets the password of the contact the reset link was emailed to, and signs the page in as it. */
export async function resetPassword(token: string, password: string): Promise<void> {
    await postJson('/api/auth/password/reset', { token, password }, 'The password could not be set')
}
