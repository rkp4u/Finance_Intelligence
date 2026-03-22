---
name: ui-design-patterns
description: Modern UI/UX design patterns for web applications. Use when creating UI components, designing layouts, building dashboards, styling pages, or when user says "improve UI", "make it look better", "redesign", "modern design", or "fix the layout". Covers CSS patterns, responsive design, data visualization, dark mode, accessibility, and financial/data-heavy UI conventions.
---

# UI Design Patterns Skill

Modern, professional UI patterns for web applications — especially data-heavy dashboards and financial tools.

## When to Use
- User says "improve UI" / "make it look better" / "redesign"
- Building new pages or components
- Fixing layout or styling issues
- Creating dashboards, tables, or data displays

---

## Design System Foundation

### CSS Variables (Design Tokens)

```css
/* ❌ BAD: Hardcoded values scattered everywhere */
.card { background: #1a1b23; border: 1px solid #27272a; }
.header { background: #1a1b23; border-bottom: 1px solid #27272a; }

/* ✅ GOOD: Design tokens via CSS variables */
:root {
    /* Colors */
    --bg-primary: #0a0b10;
    --bg-secondary: #12131a;
    --bg-card: #161821;
    --text-primary: #e4e4e7;
    --text-secondary: #71717a;
    --text-muted: #52525b;
    --accent: #6366f1;
    --accent-hover: #4f46e5;
    --border: #27272a;
    --success: #22c55e;
    --warning: #eab308;
    --error: #ef4444;

    /* Spacing (consistent scale) */
    --space-xs: 4px;
    --space-sm: 8px;
    --space-md: 16px;
    --space-lg: 24px;
    --space-xl: 32px;

    /* Typography */
    --font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
    --font-mono: 'SF Mono', 'Fira Code', 'Consolas', monospace;
    --text-xs: 0.7rem;
    --text-sm: 0.8rem;
    --text-base: 0.88rem;
    --text-lg: 1.1rem;
    --text-xl: 1.3rem;

    /* Borders & Radius */
    --radius-sm: 6px;
    --radius-md: 8px;
    --radius-lg: 12px;
}
```

### Typography Hierarchy

```css
/* Use a clear, consistent hierarchy */
h1 { font-size: var(--text-xl); font-weight: 700; letter-spacing: -0.02em; }
h2 { font-size: var(--text-lg); font-weight: 600; }
h3 { font-size: var(--text-base); font-weight: 600; }

/* Section labels */
.label {
    font-size: var(--text-xs);
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: var(--text-muted);
    font-weight: 600;
}

/* Numeric/financial values — ALWAYS use monospace + tabular */
.value {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-weight: 600;
}
```

---

## Layout Patterns

### Sidebar + Main Content

```css
/* ✅ Standard app layout */
.app {
    display: flex;
    height: 100vh;
}

.sidebar {
    width: 280px;
    flex-shrink: 0;
    background: var(--bg-secondary);
    border-right: 1px solid var(--border);
    display: flex;
    flex-direction: column;
    overflow: hidden;
}

.main {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;  /* Scrolling happens inside child containers */
}
```

### Card Grid (Dashboard Layout)

```css
/* ✅ Responsive grid that adapts to content */
.card-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
    gap: var(--space-md);
    padding: var(--space-md);
}

/* ❌ BAD: Fixed columns that break on resize */
.card-grid { grid-template-columns: 1fr 1fr 1fr; }
```

### Tab Navigation

```css
.tab-bar {
    display: flex;
    border-bottom: 1px solid var(--border);
    background: var(--bg-secondary);
    padding: 0 var(--space-md);
    flex-shrink: 0;  /* Never collapse */
}

.tab {
    padding: 12px 20px;
    border-bottom: 2px solid transparent;
    color: var(--text-secondary);
    transition: color 0.2s, border-color 0.2s;
}

.tab.active {
    color: var(--accent);
    border-bottom-color: var(--accent);
}

/* Tab icon + text alignment */
.tab { display: flex; align-items: center; gap: 6px; }
```

---

## Component Patterns

### Cards

```css
/* ✅ Consistent card pattern */
.card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 20px;
}

.card-title {
    font-size: var(--text-xs);
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: var(--text-muted);
    margin-bottom: var(--space-md);
    padding-bottom: var(--space-sm);
    border-bottom: 1px solid var(--border);
    font-weight: 600;
}

/* ❌ BAD: Cards with inconsistent padding, borders, radius */
.card-1 { padding: 15px; border-radius: 4px; }
.card-2 { padding: 20px; border-radius: 12px; }
```

### Status Badges

```css
/* ✅ Consistent badge pattern with soft backgrounds */
.badge {
    padding: 4px 12px;
    border-radius: 12px;
    font-size: var(--text-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.3px;
}

.badge.success { background: rgba(34, 197, 94, 0.12); color: var(--success); }
.badge.warning { background: rgba(234, 179, 8, 0.12); color: var(--warning); }
.badge.error   { background: rgba(239, 68, 68, 0.12); color: var(--error); }
.badge.info    { background: rgba(99, 102, 241, 0.1); color: var(--accent); }

/* ❌ BAD: Solid colored badges — too loud, poor contrast */
.badge.success { background: #22c55e; color: white; }
```

### Data Rows (Key-Value Lists)

```css
/* ✅ For financial data, metrics, settings */
.data-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 7px 0;
    border-bottom: 1px solid rgba(39, 39, 42, 0.5);
}

.data-row:last-child { border-bottom: none; }

.data-label { color: var(--text-secondary); font-size: var(--text-sm); }
.data-value {
    font-weight: 600;
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
}
```

### Buttons

```css
/* Primary action */
.btn-primary {
    padding: 8px 16px;
    background: var(--accent);
    color: white;
    border: none;
    border-radius: var(--radius-sm);
    font-weight: 500;
    cursor: pointer;
    transition: background 0.15s;
}
.btn-primary:hover { background: var(--accent-hover); }
.btn-primary:disabled { opacity: 0.4; cursor: not-allowed; }

/* Secondary / ghost action */
.btn-secondary {
    padding: 4px 10px;
    background: var(--bg-tertiary);
    border: 1px solid var(--border);
    color: var(--text-secondary);
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: all 0.15s;
}
.btn-secondary:hover { border-color: var(--accent); color: var(--accent); }
```

---

## Data Table Patterns

### Financial Comparison Table

```css
.data-table {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--text-sm);
}

.data-table th {
    position: sticky;
    top: 0;
    background: var(--bg-secondary);
    padding: 10px 16px;
    text-align: right;
    border-bottom: 2px solid var(--accent);
    font-weight: 600;
    z-index: 1;
}

.data-table th:first-child { text-align: left; }

.data-table td {
    padding: 8px 16px;
    border-bottom: 1px solid var(--border);
    text-align: right;
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
}

.data-table td:first-child {
    text-align: left;
    font-family: var(--font-sans);
    color: var(--text-secondary);
}

.data-table tr:hover td { background: var(--bg-tertiary); }

/* Section divider rows */
.data-table .section-divider td {
    padding: 12px 16px 6px;
    font-size: var(--text-xs);
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: var(--text-muted);
    font-weight: 600;
    font-family: var(--font-sans);
    border-bottom: 1px solid var(--border);
}
```

**Key rules for data tables:**
- Numbers always right-aligned
- Labels always left-aligned
- Use `font-variant-numeric: tabular-nums` so digits align vertically
- Sticky headers for scrollable tables
- Subtle row hover for scanability
- Section dividers for grouping related rows

---

## Financial UI Conventions

### Number Formatting

```javascript
// ✅ GOOD: Consistent financial number formatting
function formatCurrency(value, currencyCode) {
    const symbols = { USD: '$', INR: '₹', SGD: 'S$', EUR: '€', GBP: '£' };
    const symbol = symbols[currencyCode] || currencyCode + ' ';
    return symbol + new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value);
}

// ❌ BAD: Inconsistent formatting
// "25111" vs "$25,111" vs "25111.00" in same view
```

### Validation Check Display

```css
.check-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
}

.check-icon.pass { color: var(--success); }
.check-icon.fail { color: var(--error); }

/* Use checkmark/cross characters, not emojis */
/* ✓ and ✗ are universally rendered */
```

### Confidence Score Display

```css
/* ✅ Visual confidence bar */
.confidence-bar {
    display: flex;
    align-items: center;
    gap: 8px;
}

.confidence-track {
    width: 60px;
    height: 4px;
    background: var(--bg-tertiary);
    border-radius: 2px;
    overflow: hidden;
}

.confidence-fill {
    height: 100%;
    background: var(--accent);
    border-radius: 2px;
    transition: width 0.3s;
}
```

---

## Dark Mode Best Practices

```css
/* ✅ Layer backgrounds — not all the same dark color */
--bg-primary: #0a0b10;     /* Deepest — page background */
--bg-secondary: #12131a;   /* Sidebar, tab bar */
--bg-card: #161821;        /* Cards, content containers */
--bg-tertiary: #1a1c25;    /* Inputs, hover states */

/* ✅ Text hierarchy — not just "white" */
--text-primary: #e4e4e7;   /* Headings, values */
--text-secondary: #71717a; /* Labels, descriptions */
--text-muted: #52525b;     /* Disabled, placeholders */

/* ❌ BAD: Pure white text on pure black background — harsh contrast */
body { background: #000; color: #fff; }

/* ✅ GOOD: Soft whites on near-black — easier on eyes */
body { background: #0a0b10; color: #e4e4e7; }

/* Borders should be subtle, not prominent */
/* ❌ */ border: 1px solid #555;
/* ✅ */ border: 1px solid #27272a;
```

---

## Loading & Empty States

```css
/* Always show loading state — never leave blank */
.loading-spinner {
    display: inline-block;
    width: 14px;
    height: 14px;
    border: 2px solid var(--text-muted);
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

/* Empty states — helpful message + action */
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    color: var(--text-muted);
    text-align: center;
}
```

---

## Common Mistakes

| Mistake | Fix |
|---|---|
| Inconsistent spacing (12px, 15px, 20px randomly) | Use spacing scale (4, 8, 16, 24, 32) |
| Different border-radius on similar elements | Define `--radius-sm/md/lg` and reuse |
| Text too small to read (<12px) | Minimum 12px (0.75rem) for body text |
| No hover feedback on clickable elements | Add `cursor: pointer` + subtle background change |
| Scroll on the whole page instead of panels | Use `overflow: hidden` on parent, `overflow-y: auto` on scrollable child |
| Numbers not aligned in columns | Use `font-variant-numeric: tabular-nums` + `text-align: right` |
| Too many colors competing for attention | Max 1 accent color + status colors (success/warning/error) |
| Status badges that all look the same | Use soft background tints, not just text color |

---

## Responsive Basics (if needed)

```css
/* Mobile-first: sidebar collapses */
@media (max-width: 768px) {
    .app { flex-direction: column; }
    .sidebar { width: 100%; height: auto; max-height: 40vh; }
}
```

---

## Quick Reference

| Element | Background | Border | Radius | Font |
|---|---|---|---|---|
| Page | `--bg-primary` | none | none | `--font-sans` |
| Sidebar | `--bg-secondary` | right border | none | `--font-sans` |
| Card | `--bg-card` | `--border` | `--radius-lg` | `--font-sans` |
| Input | `--bg-tertiary` | `--border` | `--radius-sm` | `--font-sans` |
| Data value | inherit | none | none | `--font-mono` |
| Badge | rgba tint | none | 12px | `--font-sans` |
| Button primary | `--accent` | none | `--radius-sm` | `--font-sans` |
