# Tailwind CSS 4.1.x Upgrade

This project has been upgraded to **Tailwind CSS 4.1.x** with modern features and improved performance.

## What's Changed

### ✅ **Upgraded Features:**

- **Tailwind CSS 4.1.x** with modern architecture
- **CSS-first configuration** using `@import "tailwindcss"`
- **CSS Variables** for theming with `oklch()` color space
- **Lightning-fast builds** with optimized CLI
- **Better browser caching** (removed unnecessary no-cache middleware)

### 🏗️ **New Build System:**

#### Development (with watch mode):

```bash
npm run css:dev
# or
npm run dev
```

#### Production Build:

```bash
npm run css:build
```

#### Using Make:

```bash
make build    # Builds CSS + templates + Go binary
make watch    # Live reload with Air
```

### 📁 **File Structure:**

```
static/css/
├── input.css     # Source CSS with @import "tailwindcss"
└── output.css    # Generated CSS (minified in production)
```

### 🎨 **New Features Available:**

#### Modern Color System:

- Uses `oklch()` color space for better color consistency
- Custom CSS properties for theming
- Better dark mode support

#### Custom Utilities:

```css
/* Available in @layer components */
.btn-primary {
  /* Pre-built button styles */
}
.input-field {
  /* Pre-built input styles */
}
```

#### Enhanced Configuration:

```javascript
// tailwind.config.js - Simplified for v4
export default {
  content: [
    "./internal/templates/**/*.{templ,go}",
    "./static/**/*.html",
    "./cmd/**/*.go",
  ],
};
```

### 🚀 **Performance Improvements:**

- **Faster builds** - Tailwind CSS 4.x is significantly faster
- **Better caching** - Static files now cached by browsers
- **Smaller output** - Improved CSS generation and minification
- **Modern syntax** - Uses latest CSS features

### 🔧 **Development Workflow:**

#### **🚀 Quick Start (Recommended):**

```bash
# Option 1: Using npm (runs both Air + Tailwind watch)
npm run dev

# Option 2: Using Make
make watch

# Option 3: Using PowerShell directly
./dev.ps1

# Option 4: Using Batch file
./dev.bat
```

#### **🎯 Individual Commands:**

```bash
# CSS only (watch mode)
npm run css:dev

# Go server only (with Air)
npm run start
# or
make watch-go

# Production build
npm run css:build
make build
```

#### **🛠️ What the dev script does:**

1. ✅ Installs npm dependencies if needed
2. 🎨 Starts Tailwind CSS in watch mode (background)
3. 🔥 Starts Air for Go hot reload (foreground)
4. 🧹 Cleans up processes on exit (Ctrl+C)

### 📚 **Migration Notes:**

#### From v3.4.x to v4.1.x:

- ✅ All existing Tailwind classes still work
- ✅ No breaking changes in template usage
- ✅ Better performance and faster builds
- ✅ Modern CSS architecture

#### What Developers Need to Know:

- Use `npm run css:dev` during development
- CSS files are automatically processed
- No manual CSS compilation needed
- All existing templates work unchanged

### 🛠️ **Troubleshooting:**

#### If styles aren't loading:

```bash
# Rebuild CSS
npm run css:build

# Check if output.css exists
ls static/css/output.css
```

#### If development watch isn't working:

```bash
# Kill existing processes and restart
npm run css:dev
```

#### For editor support:

Install the Tailwind CSS IntelliSense VS Code extension for better autocomplete and syntax highlighting.

---

**Your CloseAuth BFF is now running on Tailwind CSS 4.1.x! 🎉**
