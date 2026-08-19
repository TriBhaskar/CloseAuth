import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import pluginOxlint from 'eslint-plugin-oxlint'
import skipFormatting from 'eslint-config-prettier/flat'

// To allow more languages other than `ts` in `.vue` files, uncomment the following lines:
// import { configureVueProject } from '@vue/eslint-config-typescript'
// configureVueProject({ scriptLangs: ['ts', 'tsx'] })
// More info at https://github.com/vuejs/eslint-config-typescript/#advanced-setup

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,ts,mts,tsx}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**']),

  ...pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,

  // shadcn-vue primitives under components/ui/** are generated, single-word
  // by convention (Button.vue, Card.vue, ...) and never registered globally
  // — they're imported and composed locally (spec §9 rule 6: "shadcn-vue
  // primitives... generated; do not hand-edit"). vue/multi-word-component-
  // names exists to prevent collisions with native HTML elements in global
  // registration, which doesn't apply here; this is the standard shadcn-vue
  // override, not a project-specific exception.
  {
    name: 'app/shadcn-primitives',
    files: [
      'src/components/ui/**/*.vue',
      // Toaster.vue's name is spec-mandated verbatim (CLOSEAUTH_FRONTEND_SPEC.md
      // §5's shared-component inventory names it exactly "Toaster") — renaming
      // it to satisfy this lint rule would contradict the spec it implements.
      'src/components/app/Toaster.vue',
    ],
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },

  ...pluginOxlint.buildFromOxlintConfigFile('.oxlintrc.json'),

  skipFormatting,
)
