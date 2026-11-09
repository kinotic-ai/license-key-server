<script setup lang="ts">
import Checkbox from 'primevue/checkbox'
import { SITE } from '@/states/site'

/**
 * The click-through: the order terms and the Kinotic Enterprise License, with the checkbox that accepts them. The
 * server records the acceptance with the version shown here.
 */
const accepted = defineModel<boolean>({ required: true })
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
          licenses the Enterprise Features of Kinotic to that organization for the term of each license key issued to
          it.
        </p>
        <p>
          Paid plans bill monthly through Stripe until canceled. Canceling stops new keys from being issued; a key
          already issued works until it expires. Prices are those shown at purchase. A free license is granted at the
          discretion of Minds Ignited to organizations that certify they qualify, and renews on re-certification.
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
        License on its behalf.
      </span>
    </label>
  </div>
</template>
