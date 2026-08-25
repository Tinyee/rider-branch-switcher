# UI Refactor Verification (temporary)

A throwaway checklist for the two refactor commits on `refactor/ui-preview-and-controls`:

- `a1792c0` — `refactor(ui): return an accepted preview selection as a value`
- `16d5088` — `refactor(ui): derive preset editor controls from one state`

**Purpose:** confirm the two refactors preserved behavior before merging to `main`.
**Status:** temporary — delete this file after verification (see "Cleanup").

## Preconditions

- Build/install the plugin into a Rider sandbox (any recent Rider; it was built against
  `releaseCheck` for both RD-251.29188.33 and RD-262.8665.386).
- A controlled test project with a **main repo + at least one initialized submodule**, with
  clean working trees.

The pass/fail judgement for each item is the observable state listed under **Pass**. If any
item shows the broken state instead, the gate **fails** and the branch must not merge.

---

## Gate 1 — preview Cancel / OK and the persisted "auto-discard .meta" setting

The preview dialog's **«始终自动丢弃 .meta 文件» / "Always auto-discard .meta files"** checkbox
is the persistence surface: it is initialised from the persisted `service.autoDiscardMeta` and is
written back **only on OK**. The **«仅丢弃 .meta 文件,保留其他文件» / "Discard only .meta files"**
checkbox is a one-shot decision and is **not persisted** — do not use it as the readout.

### Setup a recoverable .meta collision

The collision requires a file that is **untracked locally but tracked on the target branch**.
In the main repo:

```bash
cd <main-repo>
git checkout -b target-feature
echo x > Assets/Foo.meta && git add Assets/Foo.meta && git commit -m "track Foo.meta"
git checkout <current-branch>          # e.g. main
echo local > Assets/Foo.meta           # untracked on current branch; target tracks it → collision
```

Then write a preset whose main branch is `target-feature` and use **Switch** → the preview shows
the collision card with the two checkboxes.

### Cancelling must not write back

1. Note the «始终自动丢弃 .meta 文件» checkbox's current state (this is the **persisted** value).
2. Toggle it to the **opposite** state.
3. Click **Cancel**.
4. Re-open the preview.

**Pass:** «始终自动丢弃 .meta 文件» is back at its **original** persisted state (the toggle did not
persist). A checkpoint — the main repo must **not** have checked out `target-feature` (Cancel never
executes the switch).

### OK writes back exactly what was confirmed

1. With the checkbox set to the state you want, click **OK**.
2. Re-open the preview (or open Settings → Version Control → Submodule Branch Switcher).

**Pass:** the checkbox shows **the state you confirmed** on OK (the switch DID execute, so the main
repo is now on `target-feature` — that is expected).

Symmetry check (optional): start persisted = **ON**; toggle to **OFF** → Cancel must leave it **ON**;
toggle to **OFF** → OK must leave it **OFF**.

### Regression (unchanged preview behavior)

Toggling the two checkboxes updates live, consistently:
- the «将丢弃 N 个文件 / Will discard N file(s)» summary line,
- per-file notes (replacement / auto / kept / deleted),
- the OK button label («确认丢弃 / Discard & Switch» vs «切换 / Switch»).

---

## Gate 2 — preset editor four-button gating

The editor's **保存 / Save**, **丢弃 / Discard**, **切换 / Switch**, **派生 / Derive** buttons.
Rules: **save/revert** = unsaved draft **and** not locally busy; **switch/derive** = global write
gate open **and** not locally busy.

1. **Cold start** — open a preset that matches its saved state.
   **Pass:** Save + Discard disabled; Switch + Derive enabled.
2. **Draft change** — change a branch combo or add/remove a submodule.
   **Pass:** Save + Discard enabled.
3. **Busy while loading** — expand a panel so branch lists load (combo shows **«加载中...» / "Loading..."**).
   **Pass:** all four buttons disabled while the combo holds the literal `Loading...` placeholder.
4. **Global write gate only blocks switch/derive** — start a switch/derive elsewhere so a write runs.
   **Pass:** Switch + Derive disabled; Save + Discard **remain enabled** (a preset-file save is not
   blocked by another repository's write).
5. **Releasing one gate must not re-enable while another still blocks** *(highest risk)*:
   - With a global write running **and** branches still loading, end the write.
   - **Pass:** if branches are still loading (combo still holds `Loading...`), Switch + Derive
     **stay disabled** — they must not spring back.
   - Mirror: if a branch load ends but the global write is still running, Save/Discard may return
     while Switch + Derive stay disabled.

---

## Summary of the deciding checks

- Gate 1: **Cancel leaves the persisted auto-discard value unchanged** (highest risk); OK writes the
  confirmed value.
- Gate 2: **all four disabled while loading**; **global write only blocks switch/derive**;
  **releasing one gate does not re-enable while another still blocks**.

---

## Cleanup

This file is temporary. After verification passes:

```bash
git rm docs/ui-refactor-verification.md
git commit -m "docs: drop temporary ui refactor verification checklist"
```

then fast-forward `main` to the branch tip and delete the branch. The checklist should not reach `main`.
