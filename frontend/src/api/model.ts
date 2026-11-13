/** The wire types of the license key server, as its services and routes return them. */

export type Plan = 'FREE' | 'SMALL' | 'MEDIUM' | 'LARGE'
export type BillingPeriod = 'MONTHLY' | 'ANNUAL'
export type LicenseFlag = 'REVENUE_UNCONFIRMED'
export type LicenseStatus = 'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'LAPSED' | 'CANCELED' | 'EXPIRED' | 'REVOKED'
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

export interface TrialRequest {
    plan: Plan
    figures: CompanyFigures
}

export interface CheckoutRequest {
    plan: Plan
    billingPeriod: BillingPeriod
    figures: CompanyFigures
    /** How many clusters the license covers, each billed at the plan's price. */
    seats: number
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
    /** Null for a free license or a trial. */
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
    /** How many clusters may hold the license's keys at once. */
    seats: number
    /** What staff should look at. */
    flags: LicenseFlag[]
    termStart: string
    termEnd: string
    lastPaymentAt: string | null
    createdAt: string
}

/** A cluster that holds, or held, a seat of a license. */
export interface LicensedCluster {
    id: string
    licenseId: string
    /** The UID of the cluster's kube-system namespace, which its system console's License page shows. */
    clusterId: string
    name: string
    addedAt: string
    /** Null while the cluster holds a seat. */
    releasedAt: string | null
    /** Null when the cluster never renewed its key from this server. */
    lastRenewedAt: string | null
}

export interface IssuedKey {
    jti: string
    licenseId: string
    /** The licensed cluster the key works on. */
    licensedClusterId: string
    key: string
    issuedAt: string
    expiresAt: string
}

export interface LicenseWithKeys {
    license: License
    /** Every cluster that holds or held a seat, oldest first. */
    clusters: LicensedCluster[]
    /** The keys of its clusters, newest first. */
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
    designPartnerApplications: DesignPartnerApplication[]
    /** Null when the customer is not a design partner. */
    designPartner: DesignPartner | null
    /** Whether the customer may start a trial of a paid plan. */
    trialAvailable: boolean
}

export interface CustomerSummary {
    id: string
    legalName: string
    billingEmail: string | null
    createdAt: string
    licenseId: string | null
    plan: Plan | null
    status: LicenseStatus | null
    billingPeriod: BillingPeriod | null
    priceList: number | null
    /** The date the license is paid through. */
    paidThrough: string | null
    currentPeriodEnd: string | null
    /** The license's next anniversary. */
    termEnd: string | null
    lastPaymentAt: string | null
    /** How many clusters the license may hold. */
    seats: number | null
    /** How many clusters hold a seat of the license. */
    clusters: number | null
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

export type DesignPartnerApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type PublicationType = 'LOGO' | 'USE_DESCRIPTION' | 'CASE_STUDY'
export type CaseStudyStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'PUBLISHED'

/** What a customer gives when it applies to be a design partner. */
export interface DesignPartnerApplicationForm {
    figures: CompanyFigures
    billingPeriod: BillingPeriod
    intendedUse: string
}

export interface DesignPartnerApplication {
    id: string
    customerId: string
    organization: string
    contactName: string
    contactEmail: string
    plan: Plan
    billingPeriod: BillingPeriod
    figures: CompanyFigures
    intendedUse: string
    status: DesignPartnerApplicationStatus
    reviewedBy: string | null
    reviewedAt: string | null
    createdAt: string
}

export interface DesignPartnerPublication {
    id: string
    designPartnerId: string
    type: PublicationType
    /** The person at the customer who approved it; null until approved. */
    approvedBy: string | null
    approvedAt: string | null
}

export interface DesignPartner {
    id: string
    customerId: string
    organization: string
    applicationId: string
    /** Null until the discounted subscription is bought. */
    licenseId: string | null
    approvedAt: string
    /** Null until the discounted subscription is bought. */
    discountEndsAt: string | null
    caseStudyStatus: CaseStudyStatus
    publications: DesignPartnerPublication[]
}

/** The site a page was served on and the terms version it shows. */
export interface SiteInfo {
    site: Site
    termsVersion: string
}
