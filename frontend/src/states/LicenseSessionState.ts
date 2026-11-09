import { reactive } from 'vue'
import type { Participant } from '@kinotic-ai/core'
import { SessionState } from '@kinotic-ai/frontend-common'
import type { ParticipantType } from '@/api/model'
import { SITE } from './site'

/** The participant the server authenticates a session as, with the fields beyond core's. */
export interface LicenseParticipant extends Participant {
    type: ParticipantType
    customerId: string | null
    email: string
    name: string
}

/**
 * The session of this site: a customer contact on the public site, a staff member on the staff site. A login of the
 * other kind is ended, so a staff session never drives the public pages nor a customer the dashboard.
 */
export class LicenseSessionState extends SessionState {

    public get participant(): LicenseParticipant | null {
        return (this.connectedInfo?.participant as LicenseParticipant | undefined) ?? null
    }

    public override async login(): Promise<void> {
        await super.login()
        const expected: ParticipantType = SITE.site === 'STAFF' ? 'STAFF' : 'CUSTOMER'
        if (this.participant?.type !== expected) {
            await this.logout()
            throw new Error(`This site requires a ${expected.toLowerCase()} sign-in`)
        }
    }
}

export const SESSION_STATE = reactive(new LicenseSessionState()) as LicenseSessionState
