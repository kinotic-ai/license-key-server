<script setup lang="ts">
import { apiUrl } from '@kinotic-ai/frontend-common'
import githubLogo from '@/assets/github-icon.svg'
import googleLogo from '@/assets/google-icon.svg'
import microsoftLogo from '@/assets/microsoft_online-icon.svg'
import { SITE } from '@/states/site'

/**
 * The buttons that sign a customer up or in through the providers the server offers, above an "or" that separates
 * them from the page's own form. Each posts to the server, which sends the browser to the provider. Renders nothing
 * when the server offers no provider.
 */
const props = defineProps<{
  /** The verb the buttons read with. */
  intent: 'sign-in' | 'sign-up'
  /** The site path to land on once signed in. */
  referer?: string
}>()

// the name and logo of each provider, by the key the server lists it under; monochrome marks are drawn in the text color
const PROVIDERS: Record<string, { name: string, logo: string, monochrome: boolean }> = {
  'github': { name: 'GitHub', logo: githubLogo, monochrome: true },
  'azure-ad': { name: 'Microsoft', logo: microsoftLogo, monochrome: false },
  'google': { name: 'Google', logo: googleLogo, monochrome: false }
}

// quoted, since Vite inlines a small SVG as a data URL whose single quotes an unquoted url() rejects
function markStyle(logo: string) {
  return { '--provider-mark': `url("${logo}")` }
}

function startUrl(provider: string): string {
  const query = props.referer ? `?referer=${encodeURIComponent(props.referer)}` : ''
  return apiUrl(`/api/auth/provider/${provider}/start${query}`)
}
</script>

<template>
  <div v-if="SITE.signInProviders.length > 0" class="login-providers">
    <form v-for="provider in SITE.signInProviders" :key="provider" :action="startUrl(provider)" method="post" class="provider-form">
      <button type="submit" class="login-provider__button">
        <span v-if="PROVIDERS[provider].monochrome" class="provider-logo provider-logo--mark"
              :style="markStyle(PROVIDERS[provider].logo)" aria-hidden="true"></span>
        <img v-else :src="PROVIDERS[provider].logo" alt="" class="provider-logo" aria-hidden="true" />
        <span>{{ intent === 'sign-up' ? 'Sign up with' : 'Sign in with' }} {{ PROVIDERS[provider].name }}</span>
      </button>
    </form>
    <div class="login-divider"><span>or</span></div>
  </div>
</template>

<style scoped>
.provider-form {
  margin: 0;
}

.provider-logo {
  width: 1.25rem;
  height: 1.25rem;
  flex: 0 0 auto;
}

/* a CSS mask filled with the text color, so one asset reads on the light and the dark surface */
.provider-logo--mark {
  background: currentColor;
  mask: var(--provider-mark) center / contain no-repeat;
  -webkit-mask: var(--provider-mark) center / contain no-repeat;
}
</style>
