---
description: Resume the Specter goal queue — read docs/GOAL.md and work the top unblocked item
---

Read `docs/GOAL.md`. It holds the end state, the working rules, and a ranked work queue.

Then:

1. **Report the state in 3 lines max** — what's done, what's in progress, what's next up.
2. **Take the top unblocked `todo`** (or resume an `in-progress` item) and work it, full TDD:
   write the failing test first, make it pass, prove it on-device where the item says to.
3. **Ship it.** Branch, commit, push, PR, run the bot loop plus a `code-reviewer` subagent, fix the
   real findings, merge it yourself. There is no merge gate — don't stop to ask.
4. **Update `docs/GOAL.md`** (tick the item, add a Log line) plus `CHANGELOG.md` / `IDEAS.md` /
   `DECISIONS.md` in the same commit.
5. **Then take the next item.** Keep going. Only surface for a genuine decision, a real finding, or a
   true blocker — not to ask permission for ordinary work.

If the user passed an argument, treat it as the item to work on or a new item to add to the queue,
rather than picking the top one yourself: $ARGUMENTS

Epistemic discipline applies to every report: label PROVEN (verified on-device or by a test) vs
HYPOTHESIS vs ASSUMPTION. Never present a lab result as proof we beat DoorDash.
