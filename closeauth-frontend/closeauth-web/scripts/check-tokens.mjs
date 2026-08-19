#!/usr/bin/env node
// Enforcement gate for CLOSEAUTH_FRONTEND_SPEC.md §3.8 ("Enforcement"):
// bans the three ways theme drift creeps back in — `dark:` Tailwind
// variants, raw palette utility classes, and colour literals outside
// styles/tokens.css. Run via `npm run lint:check` / `npm run ci`; wired so
// CI fails the build the same way a reintroduced `bg-white` would.
//
// Comments (// line, /* block */, and Vue <!-- template --> comments) are
// stripped before scanning, so documentation that MENTIONS these patterns
// (this file's own header, tokens.css's doc comments explaining the rule)
// doesn't trip its own gate. This is a heuristic text scan, not a real
// parser — good enough for a codebase this size, and the false-positive
// cost of a stray match is one extra line in COLOUR_LITERAL_EXCEPTIONS with
// a stated reason, not a broadened blanket exemption.
import { readFileSync, readdirSync } from 'node:fs'
import { join, relative, extname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('../src', import.meta.url))
const SCANNED_EXTENSIONS = new Set(['.vue', '.ts', '.css'])

// Files where a colour literal is legitimate DATA, not app styling — each
// entry states why. Not a blanket .ts/.vue exemption: hiding a real styling
// bug in a component that happens to live in one of these files would
// defeat the point of the gate.
const COLOUR_LITERAL_EXCEPTIONS = new Map([
  [
    'styles/tokens.css',
    'the single source of colour (spec §3.3/§3.8) — every other literal in the codebase must trace back to this file',
  ],
  [
    'views/tenant-admin/TenantSettingsView.vue',
    "tenant branding form defaults (primaryColor/backgroundColor/accentColor placeholders) — a TENANT's brand colour value, not CloseAuth's own app styling",
  ],
  [
    'components/common/TenantBrandingProvider.vue',
    "the sRGB hex equivalents of tokens.css's --ca-canvas literals, used for a WCAG contrast calculation that needs a plain numeric luminance — CSS can't export a JS-consumable value, so this is a deliberate, documented cross-reference (see the component's own comment), not drift",
  ],
])

const DARK_VARIANT = /\bdark:[a-zA-Z-]/
const RAW_PALETTE =
  /\b(?:bg|text|border|ring|from|to|via)-(?:zinc|slate|gray|neutral|stone|white|black|indigo|violet|blue|red|emerald|green|amber|yellow|orange|purple|pink|cyan|teal)(?:-\d{2,3})?(?:\/\d{1,3})?\b/
const COLOUR_LITERAL = /#[0-9a-fA-F]{3,8}\b|oklch\(|rgba?\(/

function stripComments(source, ext) {
  let s = source
  s = s.replace(/\/\*[\s\S]*?\*\//g, '')
  s = s.replace(/(^|[^:])\/\/.*$/gm, '$1')
  if (ext === '.vue') s = s.replace(/<!--[\s\S]*?-->/g, '')
  return s
}

function walk(dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      walk(full, out)
    } else if (SCANNED_EXTENSIONS.has(extname(entry.name)) && !entry.name.endsWith('.spec.ts')) {
      out.push(full)
    }
  }
  return out
}

const violations = []
for (const file of walk(SRC)) {
  const rel = relative(SRC, file).split('\\').join('/')
  const raw = readFileSync(file, 'utf8')
  const code = stripComments(raw, extname(file))

  code.split('\n').forEach((line, i) => {
    if (DARK_VARIANT.test(line)) {
      violations.push(
        `${rel}:${i + 1}  dark: variant — theming is data-theme-attribute only (§3.8)\n    ${line.trim()}`,
      )
    }
    if (RAW_PALETTE.test(line)) {
      violations.push(
        `${rel}:${i + 1}  raw palette utility — use a --ca-*-backed class instead\n    ${line.trim()}`,
      )
    }
    if (COLOUR_LITERAL.test(line) && !COLOUR_LITERAL_EXCEPTIONS.has(rel)) {
      violations.push(
        `${rel}:${i + 1}  colour literal outside styles/tokens.css\n    ${line.trim()}`,
      )
    }
  })
}

if (violations.length > 0) {
  console.error(`\n\u2716 check-tokens.mjs: ${violations.length} theme-token violation(s)\n`)
  for (const v of violations) console.error(v + '\n')
  process.exit(1)
}

console.log('\u2713 check-tokens.mjs: no dark:/raw-palette/colour-literal violations outside styles/tokens.css')
