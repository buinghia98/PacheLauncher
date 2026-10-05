# TOUCH-GAMEPAD-SPEC — touch targets on a RadialGamePad-based on-screen gamepad

**Applies to:** any Android app using `com.github.Swordfish90:radialgamepad` with **one
`RadialGamePad` per control**.
**Implemented by:** `touchpad/` in this repo — `TouchGamepadLayout.kt`,
`TouchGamepadEditorActivity.kt`.

Every concrete number here is from **one** device and **one** default layout. They are here so you
can check the arithmetic against something real, **not to copy**. §5 is the part that travels.

| tag | meaning |
|---|---|
| `[MEASURED]` | measured on a real device, or read straight from source |
| `[DERIVED]` | arithmetic on a `[MEASURED]` value |
| `[UNVERIFIED]` | **not checked. Verify before relying on it.** |

---

## 1. The problem

The symptoms, in the order a player reported them:

1. *"Start has far too big a touch area, it's very easy to hit by accident."*
2. *"LB / LT / RT / RB are still big and fighting each other."*
3. *"Their touch areas cover Y and make Y very hard to press."*

All three have **one root cause**, and it is not "the touch areas are too big" in the obvious sense.

---

## 2. Two library constraints — read these first, they block most of the obvious fixes

### 2.1 Button size is locked to view size

`[MEASURED]` A `PrimaryDialConfig.PrimaryButtons` with only a `center` action (i.e. a single
button) **always** draws that button with radius:

```
r_button = view/2 × 0.95/2 × 0.8 = 0.19 × view
→ button diameter = 0.38 × view
```

So drawing a 61 dp button needs a **161 dp view**. No parameter changes that 0.38.

> **Consequence: every factor you multiply into the view multiplies the button too.** If you plan
> to "reduce the touch ratio from 2.6x to 1.5x" by scaling the view box — that is not what it does.
> It makes the button smaller and leaves the ratio exactly where it was. It took two revisions to
> see this.

A real dial (Stick / Cross / multi-button PrimaryButtons) is measured as `min(w,h)/2` and drawn to
fill the view — 1:1, none of this applies.

### 2.2 There is no way to forward a touch

`[MEASURED]` `RadialGamePad.onTouchEvent` returns `true` **unconditionally**.

> **Consequence: an `OnTouchListener` that filters touches can only DISCARD what it rejects, never
> hand it to the view below.** Returning `false` runs the pad's own `onTouchEvent`, which consumes
> it anyway.

This is the constraint that kills the event-filtering approach — see §6.

---

## 3. The design that works: split the view that DRAWS from the view that RECEIVES

§2.1 locks the drawing view's size and §2.2 forbids filtering, so the way out is to **stop the
drawing view from receiving touches at all**.

```
hit  : plain FrameLayout, sized to the desired press target   ← positioned, receives touches
 └── view : RadialGamePad, sized to button / 0.38             ← draws only, NEGATIVE margins
```

`view` is centred in `hit` and hangs out on all four sides. `clipChildren = false` on `hit` **and**
on every container above it is what lets it draw there.

```kotlin
val hit = FrameLayout(context).apply { clipChildren = false }
hit.addView(radialGamePad)          // WRAP_CONTENT
container.addView(hit)              // container.clipChildren = false
```

Placement:

```kotlin
val drawnPx = dp(drawnDp)                    // the button diameter you want to see
val boxPx   = dp(drawnDp / 0.38f)            // what the RadialGamePad needs
val hitPx   = dp(drawnDp * hitRatio)         // the press target you want

// pad: centred in hit, hanging out of it
padLp.width = boxPx; padLp.height = boxPx
padLp.leftMargin = (hitPx - boxPx) / 2       // NEGATIVE
padLp.topMargin  = (hitPx - boxPx) / 2

// hit: sized to the press target, and it is what gets clamped to the screen
hitLp.width = hitPx; hitLp.height = hitPx
hitLp.leftMargin = (xFraction * W - hitPx / 2f).roundToInt().coerceIn(0, W - hitPx)
hitLp.topMargin  = (yFraction * H - hitPx / 2f).roundToInt().coerceIn(0, H - hitPx)
```

### Why this is right

**Android hit-tests children by their bounds.** A touch outside `hit` is **never offered** to that
control — it falls straight through to whatever is underneath. Nothing swallowed, nothing discarded.

Three things come free:

1. **No dead ring.** There is no longer anywhere that you can press and have nothing happen.
2. **MOVE-after-DOWN is correct by construction.** Android keeps the target until `UP`, so a finger
   sliding off still delivers its release. No special rule to write, and none to break later.
3. **Multi-touch is the framework's own per-pointer dispatch**, not your code.

### The trade

The press target is the **square** bounds of `hit`, not a circle. The four corners are ~27% more
area than an inscribed circle. That is the price of swallowing nothing, and it is the right way
round.

`[UNVERIFIED]` We never read the library's source (it comes off JitPack as an artifact). This
design **does not depend** on how the pad hit-tests internally, because `hit` stops the touch at the
front door — which is one more reason to prefer it over tuning behaviour inside the pad.

---

## 4. Three rules that go with it

### 4.1 `hitRatio` is PER CONTROL, not per kind

The press target should be `button × hitRatio`. The values in the reference implementation:

| group | hitRatio | why |
|---|---|---|
| button in open space | 1.2 | pressed reflexively, a miss is cheap, there is room to be generous |
| button in a crowded area or at a screen edge | **1.0** | target is exactly the button |
| button whose mis-press is expensive (Start / Back) | **1.0** | "you have to press it properly" is the requirement, not a side effect |
| dial (stick, d-pad, ABXY) | 1.0 | the box already is the control |

**The two halves of the pad need not be symmetric.** In the reference layout the right half is the
crowded one (RT, RB and Start share it with the ABXY diamond below), so it takes 1.0 while the left
half keeps 1.2. Do not "tidy" that into symmetry.

### 4.2 Z-order: the more dead space a control's box has, the LOWER it goes

`[MEASURED]` With `RadialGamePad`, `addView` order **is** z-order.

A button whose box is 2.63x the button means **86% of that box draws nothing**. Under the §3 design
that dead area no longer receives touches — but **while you are still on the event-filtering path it
deletes the inputs of everything beneath it.**

> More dead space → lower. A dial (box = control, no dead ring) belongs on top.

Only **within one group** do you order by the cost of a mis-press. In the reference layout:
`RB = a wasted cast` < `Y = a missed dodge` < `START = the menu opens mid-fight`, so Start and Back
go to the bottom of the button group.

`[MEASURED]` We ordered by cost-of-mis-press at the top level for four revisions and were wrong all
four times. It is the tie-breaker, not the rule.

### 4.3 Clamp to the screen by the PRESS TARGET, not by the drawing view

Clamping the whole 161 dp drawing view pushes an edge control inward by half its dead ring — **18 dp**
in the reference layout — enough to put an edge button into its neighbour at a position the layout
never asked for.

Under §3 this is automatic: you clamp `hit`, and `hit` *is* the press target.

---

## 5. How to verify — measure TWO quantities

For every **pair** of controls (not just neighbours within one row):

```
r_live(X) = button(X) / 2 × hitRatio(X)     ← press-target radius
r_box(X)  = drawing view(X) / 2             ← drawing-view radius
d         = hypot( Δx × W_dp , Δy × H_dp )  ← centre-to-centre distance

real contention = r_live(A) + r_live(B) − d      → two controls answer one touch
dead zone       = r_box(A)  + r_live(B) − d      → A is ON TOP; A's box deletes B's presses
```

**`dead zone` is the quantity that survived four revisions unnoticed.** It can be **large and
positive** while `real contention` is **negative** — meaning nothing is mis-pressed, there are simply
places you can press where nothing happens. Users describe that as *"Y is very hard to press"*, not
*"pressing Y fires RB"*, and that difference in wording is the tell.

Under the §3 design `r_box` no longer takes part in hit-testing, so only `real contention` needs
watching — but measure both when auditing a port you are not certain has applied all of §3.

### Getting the screen size

```bash
adb shell "wm size; wm density"
```

`dp = px / (density / 160)`.

### Quick threshold for an evenly spaced row

```
required W_dp ≥ press-target diameter / fractional spacing
```

`[MEASURED]` In the reference layout, six buttons spaced 0.110 of the width apart:

| press target | threshold | on the 833 dp test device |
|---|---|---|
| 161.1 dp (untouched) | 1465 dp | overlapping by 69.5 dp |
| 91.8 dp (hitRatio 1.5) | 835 dp | clear by **0.2 dp** |
| 73.4 dp (hitRatio 1.2) | 667 dp | clear by 18.2 dp |

The middle row is the lesson: **a threshold that lands within a dp of the device you measured on is
not solved, it is merely not yet visible.** The player still reported contention at that setting.

---

## 6. Approaches that were tried and failed — do not repeat them

| approach | why it cannot work |
|---|---|
| Scale the view-size factor down to "tighten the touch ratio" | §2.1 — scales box and button together; ratio unchanged |
| `OnTouchListener` dropping DOWN events outside a radius | §2.2 — can only discard, never forward. The box still blocks everything under it, converting "mis-press" into "**deleted press**" |
| Lower `hitRatio` alone (2.63 → 1.5 → 1.2 → 1.0) | Right direction, not sufficient: the real problem is the box still blocking, not the radius |
| Order z by cost-of-mis-press at the top level | §4.2 — that is the tie-breaker, not the rule |
| Check overlap only within one group of controls | §5 — misses button-over-dial, which is exactly what the player hit |
| A custom `dispatchTouchEvent` on the container | Unnecessary. §3 gets the same result from built-in hit-testing and keeps the framework's multi-touch |

---

## 7. Still open — a problem any project of this shape has

**Position is in screen fractions; size is in absolute dp.**

On a narrower screen the gaps between controls shrink and the controls do not. That is why a default
layout tuned on a tablet is always wrong on a phone.

`[DERIVED]` The reference layout's default size needs 667 dp; but at the largest size a player can
select (`250 dp` × `scale 1.6`) the press target is 172.8 dp and the threshold jumps to **1571 dp** —
the overlap is straight back.

Proposed, **not implemented and not approved**:

* bound size by real width, `min(baseSizeDp, W_dp × k)`; or
* move size to fractions as well, like position.

Both change how the pad feels in the hand, so `k` needs a human decision.

Alongside it: the default positions deserve another look — spread horizontally **and stagger
vertically**, since a diagonal buys √2 of distance without spending any width.

---

## 8. Checklist when porting this to another project

1. **Re-verify §2.1** for your library version: build one single button and measure the drawn
   diameter against the view size. If it is not 0.38, every factor in this document must be redone.
2. **Re-verify §2.2**: does `onTouchEvent` still return `true` unconditionally?
3. §3 only holds when **each control is its own `RadialGamePad`**. If your project uses one pad with
   several secondary dials, the library decides where those dials sit and none of this applies —
   split them out first.
4. `clipChildren = false` on **every** ViewGroup from `hit` up to wherever drawing is permitted.
5. If you have a drag-and-drop editor: put the listener on `hit`, not on the pad. Dragging a control
   by its visible face is also what a user expects.
6. If the layout is persisted by control key: **renaming a key resets every player's layout.** Leave
   them alone.
7. If two processes build the pad (game and editor), **the geometry must live in exactly one place.**
   An editor that disagrees with the real pad is worse than no editor.
8. Run §5 on **at least two aspect ratios** before concluding. One device is not enough — that is
   what caused three of the wasted revisions.
