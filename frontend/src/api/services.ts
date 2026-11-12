import { Kinotic, type IServiceProxy } from '@kinotic-ai/core'
import type {
    CustomerAccount,
    CustomerDetail,
    CustomerSummary,
    FreeLicenseCertification,
    FreeLicenseRequest,
    License,
    LicensedCluster,
    Plan,
    PlanPrice,
    CheckoutRequest,
    TrialRequest,
    CompanyFigures,
    RevenueConfirmation,
    CaseStudyStatus,
    DesignPartner,
    DesignPartnerApplication,
    DesignPartnerApplicationForm
} from './model'

/** The published services, addressed in their zones as the gateway routes them. */

const SERVICES = 'org.kinotic.licenses.api.services'

function proxy(zone: string, name: string): IServiceProxy {
    return Kinotic.serviceProxy(`${zone}~${SERVICES}.${name}`)
}

export class CustomerAccountService {
    private readonly proxy = proxy('customer', 'CustomerAccountService')

    getAccount(): Promise<CustomerAccount> {
        return this.proxy.invoke('getAccount', [])
    }

    /** The prices the account buys at, for every paid plan and billing period. */
    findPrices(): Promise<PlanPrice[]> {
        return this.proxy.invoke('findPrices', [])
    }

    /** The plan a company with these figures is on. */
    findPlanFor(figures: CompanyFigures): Promise<Plan> {
        return this.proxy.invoke('findPlanFor', [figures])
    }

    createPortalSession(): Promise<string> {
        return this.proxy.invoke('createPortalSession', [])
    }

    /** Starts the purchase; resolves to the Stripe Checkout URL to send the browser to. */
    startTrial(request: TrialRequest, termsVersion: string): Promise<License> {
        return this.proxy.invoke('startTrial', [request, termsVersion])
    }

    startCheckout(request: CheckoutRequest, termsVersion: string): Promise<string> {
        return this.proxy.invoke('startCheckout', [request, termsVersion])
    }

    /** Adds a cluster, by the id its system console's License page shows, to a license; the cluster gets its first key. */
    addCluster(licenseId: string, clusterId: string, name: string): Promise<LicensedCluster> {
        return this.proxy.invoke('addCluster', [licenseId, clusterId, name])
    }

    /** Releases a cluster's seat; its keys work until they expire. */
    releaseCluster(licenseId: string, id: string): Promise<LicensedCluster> {
        return this.proxy.invoke('releaseCluster', [licenseId, id])
    }

    /** Applies to be a design partner; staff approve or reject the application. */
    applyAsDesignPartner(form: DesignPartnerApplicationForm, termsVersion: string): Promise<DesignPartnerApplication> {
        return this.proxy.invoke('applyAsDesignPartner', [form, termsVersion])
    }

    /** Starts the discounted subscription of an approved application; resolves to the Stripe Checkout URL. */
    startDesignPartnerCheckout(applicationId: string): Promise<string> {
        return this.proxy.invoke('startDesignPartnerCheckout', [applicationId])
    }

    /** Confirms the company's figures for the anniversary the license's figures are due for. */
    confirmRevenue(licenseId: string, figures: CompanyFigures): Promise<RevenueConfirmation> {
        return this.proxy.invoke('confirmRevenue', [licenseId, figures])
    }

    /** Requests a free license; one the account holds renews with its yearly confirmation. */
    requestFreeLicense(certification: FreeLicenseCertification, termsVersion: string): Promise<FreeLicenseRequest> {
        return this.proxy.invoke('requestFreeLicense', [certification, termsVersion])
    }
}

export class StaffCustomerService {
    private readonly proxy = proxy('staff', 'StaffCustomerService')

    findCustomerSummaries(): Promise<CustomerSummary[]> {
        return this.proxy.invoke('findCustomerSummaries', [])
    }

    /** The customers whose license reached its anniversary with its figures unconfirmed. */
    findRevenueUnconfirmed(): Promise<CustomerSummary[]> {
        return this.proxy.invoke('findRevenueUnconfirmed', [])
    }

    getCustomerDetail(customerId: string): Promise<CustomerDetail> {
        return this.proxy.invoke('getCustomerDetail', [customerId])
    }

    revokeLicense(licenseId: string): Promise<License> {
        return this.proxy.invoke('revokeLicense', [licenseId])
    }


    /** The public key license keys are signed with, as Base64 X.509 SubjectPublicKeyInfo. */
    getSigningPublicKey(): Promise<string> {
        return this.proxy.invoke('getSigningPublicKey', [])
    }
}

export class StaffFreeLicenseRequestService {
    private readonly proxy = proxy('staff', 'StaffFreeLicenseRequestService')

    findPending(): Promise<FreeLicenseRequest[]> {
        return this.proxy.invoke('findPending', [])
    }

    approve(requestId: string): Promise<FreeLicenseRequest> {
        return this.proxy.invoke('approve', [requestId])
    }

    reject(requestId: string): Promise<FreeLicenseRequest> {
        return this.proxy.invoke('reject', [requestId])
    }
}

export class StaffDesignPartnerService {
    private readonly proxy = proxy('staff', 'StaffDesignPartnerService')

    findPendingApplications(): Promise<DesignPartnerApplication[]> {
        return this.proxy.invoke('findPendingApplications', [])
    }

    approveApplication(applicationId: string): Promise<DesignPartner> {
        return this.proxy.invoke('approveApplication', [applicationId])
    }

    rejectApplication(applicationId: string): Promise<DesignPartnerApplication> {
        return this.proxy.invoke('rejectApplication', [applicationId])
    }

    findDesignPartners(): Promise<DesignPartner[]> {
        return this.proxy.invoke('findDesignPartners', [])
    }

    /** Records that the person named at the customer approved the publication. */
    approvePublication(publicationId: string, approvedBy: string): Promise<DesignPartner> {
        return this.proxy.invoke('approvePublication', [publicationId, approvedBy])
    }

    setCaseStudyStatus(designPartnerId: string, status: CaseStudyStatus): Promise<DesignPartner> {
        return this.proxy.invoke('setCaseStudyStatus', [designPartnerId, status])
    }
}

export const customerAccountService = new CustomerAccountService()
export const staffCustomerService = new StaffCustomerService()
export const staffFreeLicenseRequestService = new StaffFreeLicenseRequestService()
export const staffDesignPartnerService = new StaffDesignPartnerService()
