---
name: code-review
description: Review code changes against project conventions before commit. Use when user asks to review, commit, or finalize changes.
---
## Instructions

1. Run `git diff` to see uncommitted changes
2. Check for:
   - Consistent naming (camelCase Java, kebab-case Next.js routes)
   - Missing error handling
   - RBAC checks on new endpoints (see rbac-audit skill)
3. Report issues as a bullet list, ranked by severity
4. Do NOT auto-fix — ask before editing
