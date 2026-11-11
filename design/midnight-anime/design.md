# Anilili Design System — Midnight Anime

**Status:** Proposed UI direction 02  
**Theme:** Midnight Anime  
**Platforms:** Phone / tablet / TV  
**Primary brand colors:** Purple + near-black  
**Reference implementation:** `mock.html`

---

## 1. Design intent

Anilili should feel like a **premium anime streaming product**, not a generic dark-mode utility app with anime content placed inside it.

The visual system is intentionally restrained. The anime artwork provides richness and emotion; the UI provides structure, clarity, and interaction.

The core visual formula is:

> **Near-black canvas + editorial typography + anime artwork + sparse violet state cues.**

Purple is not decoration. Purple communicates **brand, selection, focus, and primary action**.

The redesign specifically avoids the common “generated component library” look caused by:

- rounded cards nested inside rounded cards;
- pill controls for every option;
- purple icon tiles on every settings row;
- large soft glows around all interactive elements;
- multiple competing accent colors;
- excessive borders and surface layers;
- using cards when simple spacing and dividers are sufficient.

The UI should feel designed through **hierarchy and restraint**, not through the number of visible components.

---

# 2. Core principles

## 2.1 Content is the visual hero

Anime artwork, titles, episode information, and playback context should carry more visual weight than the UI chrome.

When a modal or sheet refers to a specific anime or episode, show a small artwork thumbnail or backdrop crop whenever useful.

Controls remain visually quiet so they do not compete with media.

### Good

- anime cover + title + episode metadata;
- black interface surrounding the artwork;
- subtle purple selection rail;
- one clear primary action.

### Avoid

- decorative anime stickers in settings;
- fake Japanese text used only for aesthetics;
- large permanent neon glows;
- every control having a colored container.

---

## 2.2 Purple has meaning

Purple is the single brand accent.

Use purple for:

1. selected state;
2. focused state;
3. primary CTA;
4. section identity markers;
5. important active values;
6. subtle media illumination.

Do **not** use purple merely because an element needs decoration.

If everything is purple, nothing feels selected.

---

## 2.3 Prefer flat hierarchy over containers

Default hierarchy should come from:

- spacing;
- typography;
- dividers;
- selection rails;
- alignment;
- contrast.

A component should only receive a filled container when the container communicates something meaningful, such as:

- a modal boundary;
- a bottom sheet;
- active/focused state;
- blocked/error state;
- primary action.

Settings groups do **not** require individual rounded cards.

---

## 2.4 Selection and focus are different states

This is especially important on TV.

**Selected** means:

> This is the currently stored value.

**Focused** means:

> This is where the user's cursor currently is.

A selected option may not currently be focused.

A focused option may not yet be selected.

Never replace selected state with focus state.

---

## 2.5 Anime identity without anime clichés

The app should feel anime-native through:

- media artwork;
- strong title hierarchy;
- cinematic dark surfaces;
- elegant transitions;
- restrained violet lighting;
- metadata presentation.

Avoid making it anime-native through:

- manga speed lines;
- random Japanese glyphs;
- chibi decoration;
- saturated rainbow UI colors;
- oversized neon cyberpunk effects unless a specific screen calls for them.

---

# 3. Color system

## 3.1 Core tokens

```css
:root {
  --bg: #040306;
  --bg-2: #07050a;

  --panel: #0b0810;
  --panel-2: #100c16;
  --hover: #15101d;

  --line: #21182d;
  --line-2: #2e2140;

  --text: #f7f5fa;
  --text-2: #aaa3b2;
  --text-3: #716a79;

  --purple: #8b5cf6;
  --purple-2: #a78bfa;
  --purple-soft: rgba(139, 92, 246, .12);
  --purple-glow: rgba(139, 92, 246, .22);

  --red: #ff5263;
  --red-soft: rgba(255, 82, 99, .10);

  --green: #4dd49a;
  --amber: #f0bd5c;
}
```

---

## 3.2 Color usage

### Background

`--bg` is the default application canvas.

It should visually read as black, but not pure `#000` across every surface. Pure black is reserved for device shells, video blackout states, or very high-contrast boundaries.

### Elevated surfaces

Use `--panel` only for meaningful elevation:

- bottom sheet;
- dialog;
- TV picker;
- contextual modal.

Avoid creating a new surface token for each component.

### Purple

`--purple` is the functional brand color.

Use it for:

- underline selection;
- 2px selection rail;
- switch ON state;
- active primary CTA;
- focused TV edge;
- small section marker.

`--purple-2` is primarily for text labels, small metadata, and less forceful accents.

### Semantic colors

Green, red, and amber are not part of the decorative brand palette.

Use them only when the color itself communicates status.

Examples:

- Green: account connected / successful sync.
- Red: destructive action / insufficient storage / blocked action.
- Amber: warning requiring attention but not failure.

Do not color AniList states green, blue, yellow, and red just because those colors are available.

---

# 4. Surface model

The product should have a deliberately small surface hierarchy.

## Level 0 — Canvas

Near-black app background.

```text
#040306
```

## Level 1 — Content plane

Used for normal screens and settings.

```text
#07050A → #0B0810
```

## Level 2 — Modal / sheet

Used only when content has been lifted above the screen.

```text
rgba(8, 6, 11, .98)
```

## Level 3 — Active / focus

Do not create another full card surface. Instead use:

- `--purple-soft` background wash;
- purple rail;
- subtle focus outline.

---

# 5. Radius system

Use only a few radii.

```css
--radius-xs: 6px;
--radius-sm: 10px;
--radius-lg: 16px;
```

### 6px

Use for:

- artwork thumbnails;
- small controls;
- tiny media tiles.

### 8–10px

Use for:

- primary button;
- small modal action;
- TV card corner on the non-accent side.

### 12px

Allowed for centered confirmation dialogs.

### 16px

Reserved for the top corners of phone bottom sheets.

### Avoid

- 24px cards;
- 28px dialogs;
- nested rounded rectangles;
- pill radius unless the component is genuinely a switch or physical capsule control.

---

# 6. Typography

## 6.1 Typeface

Primary UI stack:

```css
font-family:
  Inter,
  ui-sans-serif,
  -apple-system,
  BlinkMacSystemFont,
  "Segoe UI",
  sans-serif;
```

A future branded font can replace Inter, but hierarchy should remain the same.

Recommended brand alternatives:

- Geist;
- Manrope;
- Sora;
- Space Grotesk for selected display use.

Do not use stereotypical anime or manga fonts for normal controls.

---

## 6.2 Hierarchy

### Screen title

Phone:

```text
24px / 820
letter-spacing: -0.03em
```

### Dialog/media title

Phone:

```text
17px / ~780
line-height: 1.12
```

TV:

```text
18px / 800
```

### Normal setting/choice title

```text
12.5px / 640–650
```

### Description

```text
10–10.5px
muted
```

### Kicker / section label

```text
9–9.5px
800–850
uppercase
0.16–0.18em tracking
```

Uppercase micro-labels are reserved for structure:

- Download;
- Player;
- Subtitles;
- Quality;
- Save to;
- Storage.

They should not be used for ordinary button labels or descriptive copy.

---

# 7. Spacing language

The interface should feel compact but not cramped.

Recommended base spacing scale:

```text
4
6
8
10
12
14
16
18
24
32
```

Common patterns:

- label → control: `8px`;
- settings row internal vertical padding: `8px`;
- media thumbnail → copy: `12px`;
- content group separation: `16–18px`;
- bottom sheet horizontal padding: `18px`;
- modal padding: `18px`;
- TV modal/card padding: `18–20px`.

Do not use padding to simulate cards around every group.

---

# 8. Dividers

Dividers replace much of the previous card chrome.

Default divider:

```css
border-bottom: 1px solid var(--line);
```

Use a stronger divider only at elevated container boundaries:

```css
border-color: var(--line-2);
```

Dividers should usually span the logical control width rather than always edge-to-edge across the entire device.

---

# 9. Artwork component

## Purpose

Artwork connects an action to its anime context.

Use artwork in contextual actions such as:

- Download;
- Continue Watching;
- My List;
- episode actions;
- series actions;
- TV modal overlays.

## Phone thumbnail

```text
56 × 76px
radius: 6px
```

## Header structure

```text
[artwork]  KICKER
           Show title
           Episode / status metadata
                                     ×
```

The artwork should be recognizable at a glance but not dominate the modal.

## Production artwork behavior

Use real anime cover/backdrop media where available.

Recommended processing:

- crop rather than stretch;
- preserve poster ratio;
- add subtle black fade when text overlays artwork;
- avoid over-saturating source images;
- allow purple environmental light only when naturally compatible.

---

# 10. Bottom sheet

## Purpose

Primary phone container for contextual choices and lightweight workflows.

Examples:

- Download;
- My List;
- Continue Watching;
- quality picker;
- download destination picker.

## Anatomy

```text
╭──────────────────────────────────╮
│             handle               │
│ artwork + title/meta          ×  │
│ ───────────────────────────────  │
│                                  │
│ workflow content                 │
│                                  │
│ primary action                   │
│ danger action          secondary │
╰──────────────────────────────────╯
```

## Spec

```text
background: rgba(8,6,11,.98)
corner radius: 16px 16px 0 0
horizontal padding: 18px
bottom padding: 18px
border-top: 1px solid --line-2
```

## Handle

```text
30 × 3px
```

The handle is informational. It should not become a decorative pill.

## Scrolling

The body can scroll independently when needed.

Primary action and important footer actions should remain visually easy to reach.

Recommended body max-height from mock:

```text
476px
```

Exact production height should adapt to viewport and safe areas.

---

# 11. Media header

## Anatomy

1. optional artwork;
2. kicker;
3. primary media title;
4. metadata line;
5. dismiss control.

Example:

```text
[poster]  DOWNLOAD
          Frieren: Beyond Journey's End
          Episode 5 · The Phantoms of the Dead     ×
```

## Rules

- Show title gets more visual weight than the action type.
- Action context uses the kicker.
- Metadata remains muted.
- Long titles use ellipsis rather than reducing typography aggressively.
- Close is quiet, not another filled circular button.

---

# 12. Underline selector

## Purpose

Use for small mutually exclusive option sets when every value is short and comparable.

Primary example:

```text
360p      720p      1080p
                     ━━━
```

Appropriate for:

- resolution;
- subtitle size presets;
- simple content filters;
- short TV option rows.

Do not use when option labels need explanations.

Use a choice list instead.

## Selected state

```css
color: var(--text);
```

with a `2px` purple underline.

Underline width should be visually shorter than the full tab width.

Mock uses approximately:

```text
left: 24%
right: 24%
```

The underline may use a subtle purple glow.

Do not fill the entire tab purple.

---

# 13. Choice list

## Purpose

Use for mutually exclusive choices that benefit from a title and optional explanation.

Examples:

- App Library;
- Device + App;
- This Episode;
- Next 12 Episodes;
- All Remaining Episodes.

## Anatomy

```text
┃ Primary label                      ○
  optional secondary text
──────────────────────────────────────
```

## Default row

```text
minimum height: 46px
left content padding: 16px
bottom divider: 1px
```

## Selected row

Selected state uses three signals:

1. brighter text;
2. very subtle purple gradient wash;
3. `2px` violet rail on the left.

Optional radio control also reflects selection.

### Selected rail

```text
2px wide
9px inset from top/bottom
```

This rail is one of the primary Anilili identity elements.

## Avoid

Do not turn choice rows into independent cards.

Do not use colored icons for simple options.

Do not show a heavy purple border around the entire row.

---

# 14. Status list

## Purpose

Shared component for anime library states.

Examples:

- Watching;
- Plan to Watch;
- Completed;
- On Hold;
- Dropped.

## Behavior

Statuses are visually neutral by default.

Current status uses the standard purple selection rail and optional checkmark.

Example:

```text
┃ Watching                           ✓
  Plan to Watch
  Completed
  On Hold
  Dropped
```

Do not assign a full rainbow palette to status items.

`Dropped` may use a slightly muted reddish text treatment, but it should not overpower the selected state.

---

# 15. Storage strip

## Purpose

Communicate required space and remaining storage without creating another large card.

## Anatomy

```text
≈ 5.9 GB                  12 episodes · 1080p
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
86.4 GB free                   80.5 GB after
```

## Spec

The storage summary is defined by horizontal rules, not a card container.

Progress bar:

```text
height: 2px
```

Active fill uses purple.

## Insufficient storage

Use red only when the state is actually blocked.

Change:

- required size to red;
- progress fill to red;
- shortage label to red;
- supporting notice to error state;
- primary CTA to disabled state.

Do not make the entire storage area a large red card.

---

# 16. Notice / inline feedback

## Purpose

Short informational or error message tied to the section above it.

## Anatomy

```text
┃ Already in your offline library — this adds a device copy only.
```

Instead of a filled blue/purple alert box, use a small vertical marker.

### Information

```text
marker: purple
text: secondary
```

### Error

```text
marker: red
text: softened red
```

This keeps feedback visible without adding another rounded component.

---

# 17. Primary button

## Purpose

One dominant action per workflow.

Example:

```text
DOWNLOAD 12 EPISODES  ↓
```

## Spec

```text
height: 44px
radius: 8px
background: --purple
text: very dark near-black
font-size: 12.5px
font-weight: 850
```

The primary button should normally be full-width inside phone sheets.

## Label rules

Prefer explicit labels:

- `DOWNLOAD EPISODE`
- `DOWNLOAD 12 EPISODES`
- `UPDATE`
- `SAVE CHANGES`

Avoid ambiguous labels such as:

- `OK`
- `DONE`
- `CONTINUE`

unless the context genuinely makes the action obvious.

## Glow

Default state should not have a large neon shadow.

Focused/pressed states may receive a subtle purple bloom.

---

# 18. Disabled primary action

Blocked actions are visually quiet.

```text
background: #17121e
text: #5e5668
```

Example:

```text
NOT ENOUGH SPACE
```

The error explanation should appear near the cause, not only inside the disabled button.

---

# 19. Footer actions

Bottom-sheet footer hierarchy:

```text
Remove download                         Cancel
```

### Destructive

Red text, left side.

### Quiet/secondary

Muted text, right side.

Avoid presenting destructive and cancel actions as equal sibling buttons unless the user is already inside a destructive confirmation modal.

---

# 20. Confirmation modal

## Purpose

Use when an action has meaningful consequences and must be explicitly confirmed.

Examples:

- remove one downloaded episode;
- remove all downloaded episodes;
- destructive account action.

## Container

```text
left/right margin: 24px on phone mock
radius: 12px
padding: 18px
border: 1px solid --line-2
```

## Anatomy

```text
REMOVE DOWNLOAD
Remove this episode?
This deletes the local copy. Your progress is untouched.

                         Cancel   REMOVE
```

## Button hierarchy

- Cancel = quiet text.
- Destructive confirmation = filled red button.

The destructive button becomes red only at the final confirmation step.

---

# 21. Update modal

Update prompt uses the same centered modal system.

## Content order

1. Update kicker;
2. version information;
3. short changelog;
4. Not now;
5. Update.

The changelog is separated by horizontal rules rather than nested inside another rounded card.

Long changelogs may scroll or fade near the bottom.

Primary update action remains purple.

---

# 22. Settings page

## Intent

Settings should feel like an **editorial list**, not a dashboard made of card groups.

Primary pattern:

```text
┃ PLAYER

Autoplay next episode                         ●
Continue automatically after credits
───────────────────────────────────────────────
Default quality                        Auto · 720p  ›
Used when playback begins
───────────────────────────────────────────────
Skip button length                           85s  ›
Fallback when timing metadata is missing
```

---

## 22.1 Section header

```text
┃ PLAYER
```

The `2px` purple marker is the category identity.

Spec:

```text
font-size: 9px
tracking: .18em
uppercase
```

Do not wrap the whole section in a rounded group card.

---

## 22.2 Settings row

### Anatomy

```text
Title                              value/switch  ›
One-line description
```

### Spec

```text
minimum height: 53px
vertical padding: 8px
bottom divider: 1px
```

### Row title

```text
12.5px / 650
```

### Description

```text
10.2px
muted
single line
ellipsis if necessary
```

### Value

```text
11px
purple-2
700
```

Values are displayed as text.

Do **not** put each value inside a pill.

---

## 22.3 Settings icons

Default setting rows should have **no icon tile**.

Icons may be used only when they provide immediate unique recognition or are required for an action.

If an icon is used:

- monochrome;
- small;
- no default rounded purple square behind it.

---

# 23. Switch

Switch is one of the few legitimately pill-shaped controls.

## Spec

```text
width: 34px
height: 18px
thumb: 12px
```

### Off

Muted charcoal track and muted thumb.

### On

Purple track and white thumb.

The entire setting row should also be tappable when appropriate.

---

# 24. Connected service status

Example:

```text
OpenSubtitles                  ● Connected  ›
Connected as mira
```

Green is allowed here because it communicates actual connection state.

Connection dot:

```text
5 × 5px
```

A tiny glow is acceptable to reinforce live/connected status.

Do not convert the entire row green.

---

# 25. TV philosophy

TV is not simply a scaled-up phone layout.

TV has different requirements:

- viewing distance;
- directional navigation;
- persistent cursor focus;
- larger target sizes;
- stronger environmental artwork;
- fewer simultaneous small labels.

The same component logic should be reused, but density and focus treatment change.

---

# 26. TV backdrop

TV dialogs should sit over a cinematic anime backdrop when media context exists.

The backdrop should be darkened enough that the dialog remains readable.

Recommended structure:

```text
media backdrop
+ dark horizontal gradient
+ subtle violet environmental light
+ dialog
```

Avoid blur so strong that the backdrop becomes a generic color blob.

The anime should still be identifiable where possible.

---

# 27. TV card

## Default media dialog

The TV card uses an asymmetric treatment:

```text
2px purple left edge
thin neutral borders on remaining edges
0px radius on accent side
10px radius on far side
```

Example mock geometry:

```text
width: 500px
left: 48px
padding: 18px 20px
```

This creates a recognizable TV-specific Anilili language without relying on a glowing rounded card.

---

# 28. TV focus system

## Minimum target

Focusable controls should remain at least:

```text
48dp
```

where the production framework's TV accessibility/navigation guidance requires it.

The mock may render some visual rows slightly smaller, but production focus targets should not.

---

## 28.1 Selected

Selected state uses:

- purple rail or underline;
- bright text;
- optional soft purple background wash.

---

## 28.2 Focused

Focused state uses:

- thin outline;
- or a stronger cursor rail;
- slight background contrast.

Recommended outline:

```css
outline: 1px solid rgba(167,139,250,.55);
```

---

## 28.3 Selected + focused

Both states must remain legible at the same time.

Example:

- selected rail remains visible;
- focus outline appears around row;
- text becomes bright;
- optional restrained purple wash.

Do not collapse both concepts into one state.

---

## 28.4 No scale animation

Do not scale focused controls by 5%.

Focus scaling causes:

- layout instability;
- visual noise;
- collisions between adjacent options;
- exaggerated “TV template” appearance.

Use edge, opacity, outline, and background transition instead.

---

# 29. TV option row

Short options such as quality can reuse the underline selector.

Example:

```text
360p        720p        1080p
                          ━━━
                          ┃ focus cursor
```

Selection and focus can coexist:

- underline = selected;
- vertical cursor rail / background = focused.

---

# 30. D-pad traversal

Directional behavior should remain predictable.

## Horizontal option row

```text
Left / Right = move between options
OK = apply / choose
Up / Down = move to previous/next logical section
```

## Vertical choice list

```text
Up / Down = move between rows
OK = choose
Left / Right = no-op unless the row explicitly contains a horizontal control
```

## Back

Back dismisses the dialog unless:

- a blocking operation is actively in flight;
- the platform requires an explicit confirmation flow.

Avoid trapping users inside modal navigation.

---

# 31. Initial focus

When opening a picker with an existing value:

> Initial focus should land on the current selected value.

This prevents the UI from implying a different default.

When opening a confirmation with no existing selectable value:

- focus the safest logical primary navigation point;
- for destructive confirmations, consider platform convention carefully;
- avoid accidental destructive confirmation from a repeated OK press.

---

# 32. Motion

Motion should reinforce state rather than attract attention to itself.

Recommended duration:

```text
120–160ms
```

Animate:

- opacity;
- background wash;
- underline movement;
- rail appearance;
- subtle focus outline;
- sheet entrance/exit.

Avoid:

- focus zoom;
- elastic overshoot;
- long glowing pulses;
- bouncing settings rows;
- large blur transitions.

---

# 33. Sheet motion

Suggested phone sheet entrance:

```text
translateY: 12–24px → 0
opacity: .92 → 1
120–180ms
```

Scrim should fade independently.

Dismiss should be slightly faster than entrance when practical.

---

# 34. Purple illumination

Glow is a secondary effect, never the primary state indicator.

Use only on:

- active underline;
- selection rail;
- focused TV cursor edge;
- occasional primary interaction edge.

Example:

```css
box-shadow: 0 0 14px var(--purple-glow);
```

The glow should remain narrow.

Never use a large purple halo around every card.

---

# 35. Anime metadata

Metadata may include:

- episode number;
- episode title;
- runtime;
- total episodes;
- list sync state;
- subtitle/audio language;
- downloaded/offline state.

Hierarchy example:

```text
Frieren: Beyond Journey's End
Episode 5 · The Phantoms of the Dead
```

The show title is primary.

Episode metadata is secondary.

---

# 36. Japanese typography

Japanese title text may be displayed only when it is genuine metadata or intentionally part of supplied artwork.

Do not insert random Japanese characters as generic decoration in production UI.

If Japanese metadata is displayed:

- use a proper Japanese-capable fallback font;
- preserve correct title text;
- keep it secondary to the localized title unless the product intentionally prioritizes original titles.

---

# 37. Error language

Errors should be specific and actionable.

### Good

```text
Not enough room. Pick fewer episodes, use a lower resolution, or free some storage.
```

### Weak

```text
Something went wrong.
```

Error UI should identify:

1. what happened;
2. what is affected;
3. what the user can do next.

---

# 38. Destructive language

Be explicit about scope.

### Single item

```text
Remove this episode?
```

### Series

```text
Remove all downloaded episodes?
```

Supporting copy should state whether the action affects:

- local download;
- device MP4;
- streaming availability;
- watch progress;
- AniList/MyAnimeList state.

Do not make users infer consequences from the button label alone.

---

# 39. Responsive behavior

The design system should adapt rather than merely scale.

## Phone

- bottom sheets;
- touch targets;
- compact media header;
- single-column settings.

## Tablet

- sheet can have a capped max width;
- optional centered modal for wider layouts;
- preserve same primitives.

## TV

- centered/asymmetric card;
- large focus targets;
- D-pad traversal;
- environmental backdrop;
- visible focus independent of selection.

---

# 40. Accessibility

## Contrast

Primary text must remain high-contrast on all dark surfaces.

Muted text should still be readable and not rely on very low opacity.

## Color independence

Selection must not rely only on purple color.

Use:

- rail;
- underline;
- checkmark;
- focus outline;
- text contrast.

## Focus visibility

TV and keyboard focus must remain clearly visible at viewing distance.

## Touch targets

Production touch targets should comply with platform recommendations even if the visual element itself is smaller.

## Motion reduction

Respect reduced motion settings:

- remove non-essential movement;
- keep state transitions immediate or opacity-only;
- avoid animated glow.

---

# 41. Component inventory

The initial system should stay intentionally small.

### Containers

- `BottomSheet`
- `Modal`
- `TvDialog`

### Media

- `MediaHeader`
- `ArtworkThumb`
- `Backdrop`

### Selection

- `UnderlineSelector`
- `ChoiceRow`
- `StatusRow`
- `RadioIndicator`

### Feedback

- `StorageStrip`
- `InlineNotice`

### Actions

- `PrimaryButton`
- `DangerButton`
- `QuietAction`

### Settings

- `SettingsSection`
- `SettingRow`
- `SettingValue`
- `Switch`
- `ConnectionStatus`

### TV state

- `TvFocusOutline`
- `SelectionRail`

Before introducing a new visual component, first check whether one of these primitives can express the interaction.

---

# 42. Suggested component API

Framework-neutral example:

```text
MediaHeader(
  artwork,
  kicker,
  title,
  metadata,
  onDismiss
)
```

```text
UnderlineSelector(
  options,
  selectedValue,
  focusedValue?,
  onChange
)
```

```text
ChoiceRow(
  title,
  description?,
  selected,
  disabled?,
  trailing?,
  onSelect
)
```

```text
SettingRow(
  title,
  description?,
  value?,
  toggle?,
  connectionStatus?,
  danger?,
  onActivate?
)
```

```text
StorageStrip(
  requiredBytes,
  availableBytes,
  itemCount,
  quality,
  blocked
)
```

Avoid APIs that expose visual decisions such as:

```text
cardColor
roundedPill
purpleIconBox
bigGlow
```

The design system should encode visual consistency rather than making every screen rebuild it manually.

---

# 43. Download flow specification

The redesigned download workflow consists of:

1. media context;
2. quality;
3. destination;
4. episode scope;
5. storage impact;
6. contextual notice if needed;
7. primary action;
8. destructive/quiet secondary actions.

## Quality

Use `UnderlineSelector`.

## Save target

Use `ChoiceRow`.

## Episode scope

Use `ChoiceRow`.

## Storage

Use `StorageStrip`.

## Existing download

Use `InlineNotice`.

## Insufficient space

- change storage strip to blocked state;
- show actionable inline error;
- disable primary CTA;
- keep options editable so the user can resolve the problem.

---

# 44. My List / Continue Watching specification

Both workflows should reuse the same status list.

Do not create separate status card components for each screen.

Shared statuses:

```text
Watching
Plan to Watch
Completed
On Hold
Dropped
```

Context-specific actions may appear below the list.

Example Continue Watching footer:

```text
Remove from Continue Watching       Open details
```

---

# 45. Settings information architecture

Recommended top-level grouping:

1. Player
2. Subtitles
3. Downloads
4. Accounts & Sync
5. Notifications
6. Content
7. Data / Storage
8. App
9. About

Avoid a long screen where each historical feature becomes its own collapsible group.

Groups should reflect user mental models, not code module ownership.

---

# 46. Copy rules

## Titles

Short and concrete.

```text
Default quality
Save to
Caption appearance
Autoplay next episode
```

## Descriptions

One line on settings screens whenever possible.

Long explanations belong inside the destination picker or detail screen.

## Values

Show current value directly on the row.

Example:

```text
Default quality                  Auto · 720p
```

This allows settings to be scanned without opening each row.

---

# 47. What not to reintroduce

The following patterns should be considered anti-patterns for this direction unless there is a strong interaction reason.

## Do not

- place every settings group inside a rounded card;
- place every setting icon inside a purple square;
- place every current value inside a pill;
- use chips for choices that need explanatory text;
- add permanent glow to inactive controls;
- use different accent colors for ordinary statuses;
- apply `border-radius: 999px` to arbitrary controls;
- use multiple layers of dark gray cards to create hierarchy;
- scale TV focus by 5%;
- add anime decoration that is unrelated to actual content;
- use purple text for normal action titles merely because it is the brand color;
- create a one-off dialog visual system for each feature.

---

# 48. Design review checklist

Before approving a new screen, check:

### Color

- Is purple communicating state/action rather than decoration?
- Are semantic colors only used semantically?
- Does the screen still read primarily black/neutral?

### Containers

- Can any card be replaced by spacing/dividers?
- Are there nested rounded surfaces?
- Is elevation actually necessary?

### Selection

- Is selected state obvious without glow?
- On TV, can selected and focused be distinguished?

### Typography

- Is media/title hierarchy stronger than component chrome?
- Are micro-labels used sparingly?
- Are settings descriptions one line where possible?

### Anime identity

- Is real media/artwork used where context exists?
- Does artwork remain subordinate to readability?
- Is any Japanese text real metadata rather than filler?

### Interaction

- Is there one clear primary action?
- Are destructive actions isolated?
- Are blocked states actionable?
- Does Back dismiss TV dialogs predictably?

### Consistency

- Does the screen reuse existing primitives?
- Is a new component actually necessary?

---

# 49. Implementation order

## Phase 1 — Tokens and primitives

Implement:

- color tokens;
- typography styles;
- dividers;
- radius tokens;
- selection rail;
- underline selector;
- flat row;
- focus outline;
- media header.

Do this first so product screens cannot drift into one-off styling.

## Phase 2 — Download sheet

Migrate:

- quality;
- download destination;
- episode scope;
- storage;
- existing download state;
- no-space state.

## Phase 3 — Status workflows

Migrate:

- My List;
- Continue Watching;
- AniList status picker.

Use one shared status row component.

## Phase 4 — Confirmation and update dialogs

Move removal and update flows into the restrained centered modal system.

## Phase 5 — Settings

Remove:

- group cards;
- icon tiles;
- value pills;
- inline chip walls;
- long explanatory paragraphs.

Preserve:

- current value on row;
- one-line descriptions;
- grouped information architecture.

## Phase 6 — TV

Adapt shared primitives to:

- D-pad traversal;
- 48dp+ focus targets;
- independent focus/selection states;
- cinematic backdrops;
- no scale-based focus animation.

---

## 49.1 What has landed

| Phase | State | Where |
|---|---|---|
| 1 — tokens and primitives | done | `ui/theme/Midnight.kt`, `ui/dialogs/Midnight*.kt` |
| 2 — download sheet | done | `WatchScreen.EpisodeDownloadDialog` |
| 3 — status workflows | done | `AnimeListStatusDialog`, `ContinueWatchingActions` |
| 4 — confirmations and update | done | `MidnightConfirmDialog` call sites, `UpdateDialog` |
| 5 — settings | done | `SettingsScreen`, `MidnightSettings.kt` |
| 6 — TV | done | device-aware inside each primitive |

Every dialog is built from `MidnightDialogHost` (sheet on phone, asymmetric card on TV) or
`MidnightModal` (centered confirmation). Both measure header and footer before the body, so the
primary action keeps its space whatever the body does — the failure that made the download dialog
unusable on a 540dp television.

The in-playback `PlayerSettingsSheet` / `PlayerCaptionsSheet` use the same primitives inside their
own hosting arrangement, which is deliberately untouched: on TV they are an in-window side panel,
not a dialog, because a dialog window does not reliably take D-pad focus over the player. Their
frame still matches — pinned header, scrolling body — and the panel carries the §27 accent edge on
the side it actually has.

---

# 50. Final visual target

Anilili should feel like this:

```text
90%  black / neutral structure
8%   violet brand and interaction
2%   semantic status colors
```

This is a directional ratio, not a literal pixel budget.

The intended impression is:

> **quiet interface, rich content, unmistakable purple interaction language.**

The user should remember the anime first and the interface second, while still recognizing the interface as distinctly Anilili.

---

# 51. Reference summary

The reference `mock.html` demonstrates the following core primitives:

- media-driven download sheet;
- underline resolution selector;
- flat radio choice rows;
- inline storage strip;
- inline notice/error treatment;
- status list with violet selection rail;
- restrained confirmation modal;
- flat settings page;
- purple section markers;
- direct text values instead of pills;
- TV asymmetric dialog;
- separate selected/focused TV states;
- no focus scaling;
- reduced component count.

Future screens should extend these principles before introducing new component families.
