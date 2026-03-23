---
name: ux-guidelines
description: UX usability and interaction design guidelines. Use when building UI components, reviewing user flows, fixing usability issues, or when user says "not intuitive", "confusing", "improve UX", "make it easier", "user friendly". Covers affordance, feedback, error states, form design, navigation, and accessibility basics.
---

# UX Guidelines Skill

Ensure every UI element is intuitive, provides feedback, and follows interaction design best practices.

## When to Use
- User says "not intuitive" / "confusing" / "improve UX"
- Building forms, buttons, navigation, modals
- Reviewing user flows
- Fixing interaction issues

---

## Core Principles

### 1. Every Action Needs Feedback
```
User clicks button → SOMETHING must happen visually

❌ Nothing happens (user clicks again, double-submits)
❌ Button stays the same (did it work?)

✅ Button shows loading state (spinner, "Creating...")
✅ Success: visual confirmation (green flash, toast, new item appears)
✅ Error: clear message (red border, error text)
```

### 2. Affordance — It Should Look Like What It Does
```
❌ "+" icon with no label (what does it create?)
❌ Clickable text that looks like regular text
❌ Non-clickable elements that look like buttons

✅ "Create" or "+ New Project" — verb + noun
✅ Underline or color for links
✅ cursor: pointer + hover state for clickable elements
```

### 3. Don't Punish Empty States
```
❌ Blank page with no guidance
❌ "No data" and nothing else

✅ Explain what goes here + how to get started
✅ Show illustration or icon for visual interest
✅ Include a primary action: "Upload your first document"
```

### 4. Progressive Disclosure
```
❌ Show everything at once (overwhelming)
❌ Hide essential controls behind menus

✅ Show primary actions first
✅ Reveal secondary options on hover or expand
✅ Use tabs/sections for complex content
```

---

## Button & Action Patterns

### Labels
```
❌ "+" (what does it do?)
❌ "Submit" (submit what?)
❌ "OK" (for what?)

✅ "Create Knowledge Base"
✅ "Upload PDF"
✅ "Compare Selected"
✅ Icon + label: "⬆ Upload" or "🔄 Re-extract"
```

### States (every button needs ALL of these)
```css
/* Default */
.btn { background: var(--accent); color: white; cursor: pointer; }

/* Hover — shows it's interactive */
.btn:hover { background: var(--accent-hover); }

/* Active — confirms the click registered */
.btn:active { transform: scale(0.98); }

/* Disabled — shows it can't be used yet */
.btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* Loading — shows work is happening */
.btn.loading { pointer-events: none; }
.btn.loading::after { content: '...'; }
```

### Destructive Actions
```
❌ Delete immediately on click
❌ No way to undo

✅ Confirm dialog: "Delete this document? This cannot be undone."
✅ Red-colored delete button (visual warning)
✅ Undo option when possible (toast with "Undo" link)
```

---

## Form & Input Patterns

### Input Feedback
```
❌ Submit, nothing happens, no error shown
❌ Error message far from the field

✅ Inline validation (red border + message below field)
✅ Shake animation on invalid submit attempt
✅ Focus the first invalid field automatically
✅ Clear placeholder text explaining expected format
```

### Placeholder vs Label
```
❌ Placeholder as the only label (disappears on focus)

✅ Placeholder as a hint: "e.g., Micron 2024 10-K"
✅ Visible label above/before the input for important fields
✅ For compact spaces: floating label or section label above group
```

### Empty Input Protection
```javascript
// ❌ Silent return — user thinks it's broken
if (!value) return;

// ✅ Visual feedback — user knows what to do
if (!value) {
    input.classList.add('shake');
    input.placeholder = 'Please enter a name...';
    setTimeout(() => input.classList.remove('shake'), 400);
    return;
}
```

---

## Loading & Progress

### Rule: Never Leave the User Staring at Nothing
```
❌ Blank screen while loading
❌ Spinner with no context ("Loading..." for 30 seconds)

✅ Skeleton screens (gray placeholders matching layout)
✅ Progress text: "Processing page 42 of 116..."
✅ Time estimate: "Usually takes about 30 seconds"
✅ Cancel option for long operations
```

### Optimistic UI
```
❌ Wait for server response before showing change
✅ Show the change immediately, rollback if server fails
   (e.g., item appears in list immediately on create)
```

---

## Navigation & Information Architecture

### Tab Navigation
```
❌ Tabs with no indication of content
❌ Active tab not visually distinct

✅ Active tab: bold text + accent underline/highlight
✅ Icon + label for quick scanning
✅ Tab count badges when relevant: "Documents (3)"
```

### Sidebar Patterns
```
❌ Flat list with no grouping
❌ No way to tell what's selected

✅ Section headers (KNOWLEDGE BASES, DOCUMENTS)
✅ Active item: background highlight + accent border
✅ Status indicators (green dot = ready, amber = processing)
✅ Collapsible sections for long lists
```

---

## Error Handling UX

### Error Messages
```
❌ "Error: 500"
❌ "Something went wrong"
❌ Technical stack trace shown to user

✅ "Could not create knowledge base. Please try again."
✅ "Upload failed: File must be a PDF under 200MB."
✅ Suggest next action: "Try refreshing the page."
```

### Error Recovery
```
❌ Error state with no way forward
✅ Retry button on failed operations
✅ Keep user's input on form errors (don't clear the form)
✅ Fallback content when data can't load
```

---

## Accessibility Basics

### Keyboard Navigation
- All interactive elements focusable (Tab order)
- Enter/Space activates buttons
- Escape closes modals/overlays
- Visible focus indicator (not just browser default)

### ARIA & Semantics
```html
<!-- ❌ div soup -->
<div class="btn" onclick="submit()">Submit</div>

<!-- ✅ semantic HTML -->
<button type="submit">Submit</button>

<!-- ❌ icon-only without label -->
<button><svg>...</svg></button>

<!-- ✅ accessible icon button -->
<button aria-label="Create knowledge base" title="Create knowledge base">
    <svg>...</svg>
</button>
```

### Color & Contrast
- Don't rely on color alone (add icons/text for status)
- Minimum 4.5:1 contrast ratio for text
- Test with color blindness simulator

---

## Quick Checklist

Before shipping any UI change:

- [ ] Every button has a hover state
- [ ] Every action gives visual feedback (loading, success, error)
- [ ] Empty states have guidance and a call to action
- [ ] Error messages are human-readable with recovery steps
- [ ] Interactive elements have cursor: pointer
- [ ] Labels describe actions (verb + noun), not just icons
- [ ] Destructive actions require confirmation
- [ ] Keyboard navigation works (Tab, Enter, Escape)
- [ ] Loading states shown for any operation > 300ms
