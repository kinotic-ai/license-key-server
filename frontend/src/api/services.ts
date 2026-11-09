import { Kinotic, type IServiceProxy } from '@kinotic-ai/core'
import type {
    CheckoutKey,
    CustomerAccount,
    CustomerDetail,
    CustomerSummary,
    FreeLicenseCertification,
    FreeLicenseRequest,
    IssuedKey,
    License,
    Plan,
    PlanInfo
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

    findPlans(): Promise<PlanInfo[]> {
        return this.proxy.invoke('findPlans', [])
    }

    createPortalSession(): Promise<string> {
        return this.proxy.invoke('createPortalSession', [])
    }

    /** Starts the purchase; resolves to the Stripe Checkout URL to send the browser to. */
    startCheckout(plan: Plan, termsVersion: string): Promise<string> {
        return this.proxy.invoke('startCheckout', [plan, termsVersion])
    }

    findCheckoutKey(checkoutSessionId: string): Promise<CheckoutKey> {
        return this.proxy.invoke('findCheckoutKey', [checkoutSessionId])
    }

    /** Requests a free license, or the renewal of the one the account holds. */
    requestFreeLicense(certification: FreeLicenseCertification, termsVersion: string): Promise<FreeLicenseRequest> {
        return this.proxy.invoke('requestFreeLicense', [certification, termsVersion])
    }
}

export class StaffCustomerService {
    private readonly proxy = proxy('staff', 'StaffCustomerService')

    findCustomerSummaries(): Promise<CustomerSummary[]> {
        return this.proxy.invoke('findCustomerSummaries', [])
    }

    getCustomerDetail(customerId: string): Promise<CustomerDetail> {
        return this.proxy.invoke('getCustomerDetail', [customerId])
    }

    revokeLicense(licenseId: string): Promise<License> {
        return this.proxy.invoke('revokeLicense', [licenseId])
    }

    issueReplacementKey(licenseId: string): Promise<IssuedKey> {
        return this.proxy.invoke('issueReplacementKey', [licenseId])
    }

    resendKey(licenseId: string): Promise<void> {
        return this.proxy.invoke('resendKey', [licenseId])
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

export const customerAccountService = new CustomerAccountService()
export const staffCustomerService = new StaffCustomerService()
export const staffFreeLicenseRequestService = new StaffFreeLicenseRequestService()
