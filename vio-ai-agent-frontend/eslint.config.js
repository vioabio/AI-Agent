import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      '.playwright-browsers/**',
      'test-results/**',
      'src/api/generated/**',
      'artifacts/**'
    ]
  },
  js.configs.recommended,
  // The existing SFCs intentionally use compact templates. The essential
  // preset keeps correctness/security rules without turning formatting
  // preferences into hundreds of release-blocking warnings.
  ...vue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    rules: {
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'vue/multi-word-component-names': 'off',
      'vue/no-unused-vars': 'error'
    }
  },
  {
    // CLI/report producers are expected to print their machine-readable
    // summary. The application source keeps the stricter no-console rule;
    // without this override --max-warnings=0 would make every successful
    // quality producer fail merely for writing its result to stdout.
    files: ['scripts/**/*.{js,mjs}'],
    rules: {
      'no-console': 'off'
    }
  }
]
