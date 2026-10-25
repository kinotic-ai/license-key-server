import { Kinotic, type IServiceProxy } from '@kinotic-ai/core'
import type {
    CustomerAccount,
    CustomerDetail,
    CustomerSummary,
    FreeLicenseCertification,
    FreeLicenseRequest,
    IssuedKey,
    License
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

    createPortalSession(): Promise<string> {
        return this.proxy.invoke('createPortalSession', [])
    }

    requestFreeRenewal(certification: FreeLicenseCertification): Promise<FreeLicenseRequest> {
        return this.proxy.invoke('requestFreeRenewal', [certification])
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

    issueTrialKey(): Promise<IssuedKey> {
        return this.proxy.invoke('issueTrialKey', [])
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
