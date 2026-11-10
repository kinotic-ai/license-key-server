<script setup lang="ts">
import Checkbox from 'primevue/checkbox'
import type { PublicityConsent } from '@/api/model'
import { SITE } from '@/states/site'

/**
 * The click-through: the order terms and the Kinotic Enterprise License, with the checkbox that accepts them, and the
 * publicity the page's terms ask for. The server records the acceptance with the version shown here and the consent.
 */
const accepted = defineModel<boolean>({ required: true })

defineProps<{
  /** The publicity the customer consents to by accepting; none when unset. */
  publicity?: PublicityConsent
}>()
</script>

<template>
  <div class="app-card-surface rounded-xl border p-4">
    <details class="mb-3">
      <summary class="app-heading-text cursor-pointer text-sm font-medium">
        Order terms and Kinotic Enterprise License (version {{ SITE.termsVersion }})
      </summary>
      <div class="app-muted-text mt-3 space-y-2 text-sm leading-6">
        <p>
          By accepting you agree, on behalf of the organization named above, to the Kinotic Enterprise License, which
          licenses the Enterprise Features of Kinotic to that organization until each license key issued to it expires.
          A key expires seven days after the date your license is paid through, and a new key is issued with each
          payment.
        </p>
        <p>
          Your plan is set by your organization's annual revenue, including any companies it owns, and for the free
          plan also by the capital it has raised. You confirm these figures when you buy and once a year after, 30 days
          before each anniversary of your license; when they set another plan, your license moves to it at that
          anniversary.
        </p>
        <p>
          Paid plans bill monthly or annually through Stripe until cancelled. Your license keeps the price list and
          these terms in effect the day it starts for as long as it stays active: never cancelled, with every failed
          payment settled within 30 days. A license whose failed payment goes unsettled for 30 days lapses and its
          subscription is cancelled; buying again starts on the price list and terms then in effect. No new key is issued
          for a license that is past due, lapsed, cancelled or revoked; a key already issued works until it expires.
        </p>
        <p>
          A free license is granted at the discretion of Minds Ignited to organizations that certify they qualify.
        </p>
        <p v-if="publicity === 'CUSTOMER_NAME'">
          <strong>Publicity.</strong> In return for the free license, Minds Ignited may name your organization as a
          Kinotic customer, on its site and in what it publishes about Kinotic.
        </p>
        <p v-if="publicity === 'DESIGN_PARTNER'">
          <strong>Publicity.</strong> As a design partner, Minds Ignited may name your organization as a Kinotic
          customer, describe what you use Kinotic for in words you approve, and publish a case study written together
          with you, only once you approve it. You approve everything published about you before it goes out. Minds
          Ignited never publishes your code, your data or anything you mark confidential.
        </p>
        <p>
          The full text of the Kinotic Enterprise License is published with Kinotic at
          <a class="underline" href="https://github.com/kinotic-ai/kinotic/blob/develop/LICENSE-ENTERPRISE" target="_blank" rel="noopener">
            LICENSE-ENTERPRISE</a>.
        </p>
      </div>
    </details>
    <label class="flex items-start gap-3 text-sm">
      <Checkbox v-model="accepted" binary input-id="accept-terms" />
      <span class="app-heading-text">
        I have authority to bind the organization named above, and I accept the order terms and the Kinotic Enterprise
        License on its behalf{{ publicity ? ', including the publicity they describe' : '' }}.
      </span>
    </label>
  </div>
</template>
