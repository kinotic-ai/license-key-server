import type { BillingPeriod, LicenseStatus, Plan, PublicityConsent } from '@/api/model'

/** Formatting shared by the public pages and the dashboard. */

export function formatMoney(amount: number | null | undefined, currency: string): string {
    return amount === null || amount === undefined
        ? '—'
        : new Intl.NumberFormat('en-US', { style: 'currency', currency: currency.toUpperCase(), maximumFractionDigits: 0 }).format(amount)
}

export function formatDate(value: string | null | undefined): string {
    return value ? new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(new Date(value)) : '—'
}

export function planName(plan: Plan | null | undefined): string {
    return plan ? plan.charAt(0) + plan.slice(1).toLowerCase() : '—'
}

export function billingPeriodName(period: BillingPeriod | null | undefined): string {
    let ret: string
    if (period === 'ANNUAL') {
        ret = 'Annual'
    } else if (period === 'MONTHLY') {
        ret = 'Monthly'
    } else {
        ret = '—'
    }
    return ret
}

export function publicityLabel(consent: PublicityConsent | null | undefined): string {
    let ret: string
    if (consent === 'CUSTOMER_NAME') {
        ret = 'May be named as a customer'
    } else if (consent === 'DESIGN_PARTNER') {
        ret = 'Design partner'
    } else {
        ret = 'None'
    }
    return ret
}

export function statusLabel(status: LicenseStatus | null | undefined): string {
    const labels: Record<LicenseStatus, string> = { ACTIVE: 'Active', PAST_DUE: 'Past due', LAPSED: 'Lapsed', CANCELED: 'Canceled', REVOKED: 'Revoked' }
    return status ? labels[status] : 'No license'
}

/** The PrimeVue Tag severity of a license status. */
export function statusSeverity(status: LicenseStatus | null | undefined): string {
    let ret: string
    if (status === 'ACTIVE') {
        ret = 'success'
    } else if (status === 'PAST_DUE') {
        ret = 'warn'
    } else if (status) {
        ret = 'danger'
    } else {
        ret = 'secondary'
    }
    return ret
}

/** Saves a key as a file named by its id. */
export function downloadKey(jti: string, key: string): void {
    const blob = new Blob([key + '\n'], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `kinotic-license-${jti}.key`
    anchor.click()
    URL.revokeObjectURL(url)
}
