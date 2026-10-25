/** The wire types of the license key server, as its services and routes return them. */

export type Plan = 'FREE' | 'STARTUP' | 'BUSINESS' | 'ENTERPRISE'
export type LicenseStatus = 'ACTIVE' | 'PAST_DUE' | 'CANCELED' | 'REVOKED'
export type Feature = 'AZURE_KEY_VAULT' | 'ENTERPRISE_IDENTITY_PROVIDERS'
export type LicensePurpose = 'DEVELOPMENT_AND_TESTING' | 'PRODUCTION'
export type ContactRole = 'BILLING' | 'TECHNICAL'
export type FreeLicenseRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type ParticipantType = 'STAFF' | 'CUSTOMER'
export type Site = 'PUBLIC' | 'STAFF'

export interface PlanInfo {
    id: Plan
    name: string
    monthlyPrice: number
    currency: string
    features: Feature[]
    highlights: string[]
    requiresApproval: boolean
}

export interface Customer {
    id: string
    legalName: string
    stripeCustomerId: string | null
    createdAt: string
}

export interface CustomerContact {
    id: string
    customerId: string
    name: string
    email: string
    role: ContactRole
}

export interface AgreementAcceptance {
    id: string
    customerId: string
    termsVersion: string
    acceptedByName: string
    acceptedByEmail: string
    acceptedAt: string
}

export interface License {
    id: string
    customerId: string
    plan: Plan
    status: LicenseStatus
    features: Feature[]
    stripeSubscriptionId: string | null
    checkoutSessionId: string | null
    currentPeriodEnd: string | null
    termStart: string
    termEnd: string
    lastPaymentAt: string | null
    createdAt: string
}

export interface IssuedKey {
    jti: string
    licenseId: string | null
    purpose: LicensePurpose
    key: string
    issuedAt: string
    expiresAt: string | null
}

export interface LicenseWithKeys {
    license: License
    keys: IssuedKey[]
}

export interface FreeLicenseRequest {
    id: string
    customerId: string
    organization: string
    contactName: string
    contactEmail: string
    annualRevenue: number
    capitalRaised: number
    intendedUse: string
    status: FreeLicenseRequestStatus
    reviewedBy: string | null
    reviewedAt: string | null
    createdAt: string
}

export interface FreeLicenseCertification {
    annualRevenue: number
    capitalRaised: number
    intendedUse: string
}

export interface CustomerAccount {
    customer: Customer
    contacts: CustomerContact[]
    licenses: LicenseWithKeys[]
    freeLicenseRequests: FreeLicenseRequest[]
}

export interface CustomerSummary {
    id: string
    legalName: string
    billingEmail: string | null
    createdAt: string
    licenseId: string | null
    plan: Plan | null
    status: LicenseStatus | null
    currentPeriodEnd: string | null
    termEnd: string | null
    lastPaymentAt: string | null
    latestKeyJti: string | null
    latestKeyExpiresAt: string | null
}

export interface CustomerDetail {
    customer: Customer
    contacts: CustomerContact[]
    licenses: LicenseWithKeys[]
    acceptances: AgreementAcceptance[]
}

/** The success page's view of the key a Checkout Session bought. */
export interface CheckoutKey {
    status: 'PENDING' | 'READY' | 'SHOWN'
    key?: string
    expiresAt?: string
}

/** The site a page was served on and the terms version it shows. */
export interface SiteInfo {
    site: Site
    termsVersion: string
}
