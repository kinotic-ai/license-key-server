/** The wire types of the license key server, as its services and routes return them. */

export type Plan = 'FREE' | 'SMALL' | 'MEDIUM' | 'LARGE'
export type BillingPeriod = 'MONTHLY' | 'ANNUAL'
export type LicenseFlag = 'REVENUE_UNCONFIRMED'
export type LicenseStatus = 'ACTIVE' | 'PAST_DUE' | 'LAPSED' | 'CANCELED' | 'REVOKED'
export type ContactRole = 'BILLING' | 'TECHNICAL'
export type FreeLicenseRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type ParticipantType = 'STAFF' | 'CUSTOMER'
export type Site = 'PUBLIC' | 'STAFF'
/** What a customer let Minds Ignited publish about it when it accepted the terms. */
export type PublicityConsent = 'CUSTOMER_NAME' | 'DESIGN_PARTNER'

/** What a customer reports about its company, which sets its plan. */
export interface CompanyFigures {
    annualRevenue: number
    /** Required when the annual revenue is under $1M, where it decides between Free and Small. */
    capitalRaised: number | null
}

/** The price of a paid plan for one billing period on one price list. */
export interface PlanPrice {
    plan: Plan
    billingPeriod: BillingPeriod
    priceList: number
    /** What one billing period costs. */
    amount: number
    currency: string
}

export interface CheckoutRequest {
    plan: Plan
    billingPeriod: BillingPeriod
    figures: CompanyFigures
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
    /** Null for no publicity. */
    publicityConsent: PublicityConsent | null
    acceptedAt: string
}

export interface License {
    id: string
    customerId: string
    plan: Plan
    status: LicenseStatus
    /** Null for a free license. */
    billingPeriod: BillingPeriod | null
    /** The price list the license keeps while it stays active. */
    priceList: number
    /** The terms the license keeps while it stays active. */
    termsVersion: string
    stripeSubscriptionId: string | null
    checkoutSessionId: string | null
    currentPeriodEnd: string | null
    /** When the subscription ends because the customer cancelled it; null while it renews. */
    cancelsAt: string | null
    /** When the license fell past due; it lapses 30 days on. */
    pastDueSince: string | null
    /** What staff should look at. */
    flags: LicenseFlag[]
    termStart: string
    termEnd: string
    lastPaymentAt: string | null
    createdAt: string
}

export interface IssuedKey {
    jti: string
    licenseId: string
    key: string
    issuedAt: string
    expiresAt: string
}

export interface LicenseWithKeys {
    license: License
    keys: IssuedKey[]
    /** The date the license is paid through, which its keys follow. */
    paidThrough: string | null
    /** The anniversary the figures are due for, when they are due. */
    confirmationDueFor: string | null
    /** The figures confirmed for each anniversary, newest first. */
    confirmations: RevenueConfirmation[]
}

/** The figures a customer confirmed for one anniversary of its license, and the plan they set. */
export interface RevenueConfirmation {
    id: string
    licenseId: string
    anniversary: string
    figures: CompanyFigures
    plan: Plan
    licensePlan: Plan
    confirmedByName: string
    confirmedByEmail: string
    confirmedAt: string
}

export interface FreeLicenseRequest {
    id: string
    customerId: string
    organization: string
    contactName: string
    contactEmail: string
    certification: FreeLicenseCertification
    status: FreeLicenseRequestStatus
    reviewedBy: string | null
    reviewedAt: string | null
    createdAt: string
}

export interface FreeLicenseCertification {
    figures: CompanyFigures
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
    flags: LicenseFlag[]
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
