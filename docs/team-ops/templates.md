# Role-based Operating Templates (Draft)

Use these templates as copy-paste checklists in issue threads, PR descriptions, or workpad notes.

## 1) Contributor Template

### Context
- Issue:
- Branch:
- Scope (in/out):

### Daily plan
- [ ] Reconfirm acceptance criteria
- [ ] Keep change scope aligned with issue
- [ ] Run local checks before commit
- [ ] Capture validation evidence in thread

### Delivery checklist
- [ ] Code/docs change matches requested scope
- [ ] User-visible changes are documented
- [ ] Risk and rollback notes are written

---

## 2) Reviewer & Maintainer Template

### Review focus
- [ ] Scope matches issue intent
- [ ] Critical paths covered by tests/checks
- [ ] Docs/UX changes are discoverable from entry points
- [ ] Commit/PR message explains why + what changed

### Merge gate
- [ ] CI status green (or exceptions documented)
- [ ] Blocking comments resolved
- [ ] Release note/changelog impact reviewed

---

## 3) Release Operator Template

### Pre-release
- [ ] Confirm release target commit/tag
- [ ] Verify changelog entries are complete
- [ ] Run smoke checks on primary commands/flows

### Release execution
- [ ] Publish artifacts
- [ ] Announce changes + known limitations
- [ ] Monitor immediate post-release issues

---

## 4) Documentation Owner Template

### Documentation quality gate
- [ ] New docs are linked from top-level and docs landing pages
- [ ] Korean/English parity is considered
- [ ] Commands and paths in examples are valid
- [ ] Outdated references are removed or marked

### Handoff
- [ ] Add quick summary for contributors
- [ ] Record follow-up doc debt items
