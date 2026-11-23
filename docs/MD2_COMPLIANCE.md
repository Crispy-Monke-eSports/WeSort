# MD2 compliance audit — WeSort web UI

Material Design **2** (https://m2.material.io/), not Material Design 3 / Material You.
Library version `@mui/material` v9.5.0 is the toolkit version, not the design
version: every row below maps an MD2 rule to the MUI v9 API that implements it
(verified through the installed v9 docs and `@mui/material/styles` typings),
the actual code, and the proof. Nothing is accepted for "looking Material".

Conventions: `shape.borderRadius = 4` globally (MD2: cards, buttons, dialogs,
text fields and menus all use 4dp). Dark is the default scheme, light is a
complete second scheme of the same system (`wesortTheme.ts`, `AppShell`
toggle, `public/init-mode.js`).

## 1. Color roles and text contrast

| MD2 rule                                           | Source                       | MUI v9 API                                       | Implementation                                               | Exception                                                                       | Proof                                          |
| -------------------------------------------------- | ---------------------------- | ------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------------------------- | ---------------------------------------------- |
| Dark surface `#121212`-family, light surface white | m2 dark theme                | `createTheme({ colorSchemes: { dark, light } })` | `dark.background #121614/#1b211e`, `light #f2f4f1/#ffffff`   | Slightly green-tinted greys (product identity), documented here                 | `theme/wesortTheme.ts`, screenshots dark+light |
| Text emphasis 87/60/38%                            | m2 text legibility           | `palette.text.primary/secondary/disabled`        | dark `68%/38%`, light `87%/60%/38%`                          | —                                                                               | computed ratios below                          |
| Contrast ≥ 4.5 body text                           | WCAG AA (MD2 references)     | `palette.*.contrastText`                         | all pairs ≥ 5.1 except warning (fixed) and disabled (exempt) | light `warning.main #e65100` uses **black** text (5.54) instead of white (3.79) | python check, screenshots                      |
| Disabled text exempt                               | WCAG inactive-component rule | default 38% opacity                              | 3.15 dark / 2.42 light, standard MUI/MD2 opacity             | accepted: disabled is never the only signal                                     | —                                              |
| Error/warning/info/success roles                   | m2 color system              | `palette.error/warning/info/success`             | real roles; no invented prices (product rule)                | —                                                                               | `status.tsx` mapping                           |

## 2. Typography roles and semantics

| MD2 rule                                          | Source                                     | MUI v9 API                                                         | Implementation                                                                                              | Exception                                                                                                                   | Proof                                                                 |
| ------------------------------------------------- | ------------------------------------------ | ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| H1 96/Light … H6 20/Medium, subtitle/body/caption | m2 type scale, `/inline-tools/typography/` | `createTheme` typography defaults (MUI v9 implements the MD scale) | **No** global h1–h4 overrides (previous 1.5/1.25/1.1/1rem compression removed)                              | `button` keeps `textTransform: none` + 600: long ops labels stay readable; uppercase MD2 buttons would clip in dense tables | `wesortTheme.ts`, tsc, screenshots                                    |
| Right semantic level, free visual variant         | review requirement                         | `component` + `variant` props                                      | page titles `component="h1" variant="h5"`; sections `h2/h6`; card names `h3/subtitle1`; stat figures `p/h4` | —                                                                                                                           | grep: no `variant="h1..h4"` on headings except intentional H4 figures |
| Monospace IDs break, never clip                   | product rule                               | `sx wordBreak: break-all`                                          | IDs/stash names in tables+cards                                                                             | —                                                                                                                           | mobile overflow assertion (P1-06)                                     |

## 3. Shape per component

| MD2 rule                                           | Source               | MUI v9 API                                            | Implementation                                  | Exception                                | Proof                         |
| -------------------------------------------------- | -------------------- | ----------------------------------------------------- | ----------------------------------------------- | ---------------------------------------- | ----------------------------- |
| Card 4dp, elevation 1–2 resting                    | m2 cards             | `MuiCard defaultProps elevation 2` + `borderRadius 4` | `wesortTheme.ts`                                | elevation 2 (raised card) allowed by MD2 | screenshots                   |
| Buttons 4dp                                        | m2 buttons           | `MuiButton root borderRadius 4`                       | theme                                           | —                                        | screenshots                   |
| Contained buttons have 2dp elevation (8dp pressed) | m2 buttons/behavior  | default `disableElevation: false`                     | **removed** the global `disableElevation: true` | none                                     | `wesortTheme.ts`, screenshots |
| Chips are fully rounded (stadium), Body-2 label    | m2 chips             | MUI default Chip (no overrides)                       | **removed** `borderRadius: 4, fontWeight: 600`  | none                                     | `wesortTheme.ts`, screenshots |
| Dialog 4dp                                         | m2 dialogs           | `MuiDialog paper borderRadius 4`                      | theme                                           | —                                        | screenshots                   |
| Drawer: no rounding                                | m2 navigation drawer | `MuiDrawer paper borderRadius 0`                      | theme                                           | —                                        | screenshots                   |
| Tooltip 4dp                                        | m2 tooltips          | `MuiTooltip tooltip borderRadius 4`                   | theme                                           | —                                        | theme file                    |
| Table density 8px vertical rhythm                  | m2 data tables       | `MuiTableCell padding 8px 12px`                       | theme                                           | —                                        | screenshots                   |

## 4. Elevation and dark surfaces

Contained buttons and cards use real elevation (no global flattening).
Dark surfaces are solid tints (`#121614`/`#1b211e`) rather than pure-black
overlays — a documented product deviation that keeps green-tinted identity in
both schemes; contrast ratios hold (≥ 5.1).

## 5. Spacing (8dp grid)

Layout uses MUI `spacing()` (8dp base): page stacks `spacing={2}` (16dp),
card grids `spacing={2}`, table cell padding 8/12. No raw px gutters outside
the `sx` system except icon-size constants.

## 6. Navigation (AppBar/Drawer)

Permanent drawer ≥ md, temporary drawer with `keepMounted` below md;
`selected` nav item; `aria-label` on menu/open buttons; account `Menu` with
sign-out; theme toggle `IconButton` with accessible label; Minecraft
disclaimer only in the drawer footer (product rule, exactly one place).
Proof: AppShell source + mobile screenshots.

## 7. Buttons and visual priority

`contained` = primary commit (Save server, Create, Save, Apply-in-dialog),
`outlined` = secondary (Send to bot, Send sessions…), `text` = tertiary row
actions (Edit/Delete…). Destructive confirm buttons use `color="error"`
**plus** explicit danger copy (`Yes, delete…`) — color is never the only
signal. `disabled` + `busy` states prevent double-submit in every dialog.
Proof: `dialogs.tsx`, per-page call sites.

## 8. Forms, dialogs, validation, tabs, steppers

- `TextField` with `label`, `helperText`, `error`, `type="password"` for
  secrets (`PasswordDialog`); numeric bot settings with min/max + messages.
- `Dialog` + `DialogTitle/Content/Actions`, `aria-labelledby/describedby`,
  `autoFocus` on the safe action, Escape/backdrop disabled while busy, focus
  restored by MUI Modal on close.
- `Tabs variant="scrollable" scrollButtons="auto"` with `aria-label`;
  checkout `Stepper` with per-group state machine (not_submitted → submitting
  → confirmed/uncertain/rejected), exact retry, resume banner.
- No `window.confirm`/`window.prompt` anywhere in app code.
- Proof: `dialogs.tsx` (+`useConfirm`), e2e `perms-scope`, screenshots of
  dialogs with validation errors.

## 9. Data display, hierarchy, empty/loading/error

Tables (`OpsTable`, orders, inventory table w/ `TablePagination`) on sm+;
key-value cards on xs (exactly one variant via `useMediaQuery(down sm)`).
Status comes from a tested backend mapping (`status.tsx`, P2-17): success /
error / info / warning, never color alone (label text always present).
Loading `LinearProgress`/`Skeleton`, error `Alert`, empty states — never a
silent blank list. Proof: mobile screenshots, Playwright tests.

## 10. Interaction: touch, keyboard, screen reader, motion

- Touch: row actions are text buttons in wrapping stacks on xs (no 4-across
  icon crush); cards use full-width buttons.
- Keyboard: tabs scrollable with buttons, switches/radios/selects are native
  MUI controls, dialog focus trap + Escape, table pagination keyboard-able.
- Screen reader: labelled dialogs/selects/switches, `aria-label` on icon
  buttons, table `aria-label`s, status text (not color-only).
- Motion: no custom animation; MUI default transitions only.
- Deviation: dense `size="small"` buttons in desktop tables (below 48px
  touch). Accepted for the desktop ops tool; every small-screen layout uses
  wrapping text buttons/cards instead.

## 11. Responsive

`main` has `minWidth: 0` + `maxWidth: 100%` (flex-child overflow fix);
two-column state tables stack label-over-value on xs; grids use
`size={{ xs: 12, sm: 6, lg: 4 }}`; `word-break` on IDs. Verified widths:
320/360/390/768/1440/1920 + 200% zoom spot-check, with the assertion
`document.documentElement.scrollWidth <= window.innerWidth` on pages without
intentional horizontal scroll. Proof: Playwright overflow spec + screenshots.

## 12. Light and dark

Both `colorSchemes` complete; toggle in the account row (`useColorScheme`

- `setMode`, MUI-persisted `mui-mode`); `public/init-mode.js` sets
  `data-mui-color-scheme` pre-paint from the same key (no inline script, CSP
  `script-src 'self'` holds); `defaultMode="dark"`. Both themes captured for
  overview/inventory/orders/bots/operations/settings incl. dialogs and error
  states. Proof: `main.tsx`, `AppShell.tsx`, screenshot matrix.
