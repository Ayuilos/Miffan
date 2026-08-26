# Miffan Character System

## Product intent

Miffan turns the app's bowl icon into a persistent assistant identity. When an assistant has no custom image or emoji, the assistant is represented by an animated Miffan everywhere: selection, settings, empty chat, waiting, and message identity.

The system should make multiple assistants feel distinct without forcing users to upload avatars. Appearance communicates identity; motion communicates current state.

## Species invariants

Every Miffan keeps three recognition anchors:

- the bowl silhouette;
- a face drawn on the bowl;
- rice or another readable bowl content above the rim.

Color, material, contents, accessories, and motion temperament may evolve, but no variant may remove the bowl or face. At small avatar sizes, the bowl and eyes must remain the strongest shapes.

## Appearance V1

The first release provides six curated palettes:

- Classic: warm terracotta and golden rice;
- Matcha: tea green and pale matcha rice;
- Sakura: soft pink and cream;
- Moonlight: violet-blue and moonlit rice;
- Sea Salt: ocean blue and cool foam;
- Ink Jade: charcoal ink and jade.

Users select a palette per assistant. New assistants use Miffan Classic. Existing assistants whose avatar is the legacy `Dummy` value are displayed as Miffan Classic and become an explicit Miffan when the user changes the palette. Custom image and emoji avatars remain supported.

## Character V1

The first Miffan world collection contains four curated inhabitants:

- Rice: the original smooth ceramic bowl with a soft rice mound;
- Sprout: a fluted bowl whose rice carries a young two-leaf sprout;
- Dumpling: a banded bowl carrying three round dumplings and a small spoon;
- Stargazer: a speckled bowl carrying star-shaped rice with a rim charm.

A character kind is a coherent shape preset, not a bag of independently swappable parts. It changes content, material treatment, and one restrained accessory together so every option reads as an intentional inhabitant. Palette and motion profile remain independent axes, allowing the same inhabitant to belong to different assistants without multiplying renderer implementations.

## Theme-aware color

Each assistant can choose between its saved Miffan palette and the active app theme. Theme-aware color follows the final Material color scheme, including system dynamic color, preset and custom themes, and light/dark mode. It changes color roles only: character kind, material treatment, contents, accessories, and motion profile remain independent.

Turning theme sync off restores the assistant's previously selected Miffan palette. Existing and legacy assistants keep palette mode by default so an update never changes their appearance unexpectedly.

## Motion V1

Each explicit Miffan can use one of three motion profiles:

- Lively: faster rhythm, slightly broader gaze, and a light elastic response;
- Calm: slower breathing, smaller movement, and restrained reactions;
- Curious: eyes lead the response, followed by a small delayed lean.

Curious is the compatibility default for existing Miffan and legacy `Dummy` assistants. Profiles tune one shared semantic animation system; they do not own independent animation clips.

## Character Behavior V1

Each inhabitant adds one readable motion signature to the shared semantic animation system. The bowl joins the gesture so the behavior remains recognizable outside large previews:

- Rice uses a soft whole-body hop with one following grain;
- Sprout leans toward input while its leaves listen and sway;
- Dumpling answers with a side-to-side bowl step and staggered three-dumpling ripple;
- Stargazer slowly hovers while its stars pulse and twinkle.

The signature becomes clearer while thinking, typing, submitting, or celebrating, but remains quiet during ordinary idle. Error states settle rather than adding celebratory motion. Stargazer may be slightly more visible at night, while time-of-day gestures remain shared across the world.

Character behavior is not another user setting. It is derived from character kind inside the renderer, then scaled by the selected motion profile and reduced-motion preference. Feature pages continue to emit semantic scene state and never select signature animations directly.

## Semantic motion states

Components ask Miffan to express meaning rather than selecting animation clips. The shared V1 vocabulary is:

- Idle
- Thinking
- Happy
- Error
- Update available
- input focused
- typing
- submitted
- attention / poke

Time-of-day idle gestures enrich the character but must never override an active semantic state.

## Experience principles

- Motion is light, continuous, and interruptible.
- Expression changes reuse the existing face whenever possible; decorative elements explain an action, not decorate every state.
- The mascot reacts to taps in the surrounding chat scene, not only taps on its body.
- Keyboard and input movement are coordinated through scene state, without direct layout coupling between the mascot and input component.
- Reduced-motion behavior must preserve meaning with shorter, lower-amplitude transitions.

## Out of scope for Character V1

- freely interchangeable contents, accessories, and materials;
- downloadable character packs;
- an arbitrary color picker;
- cloud synchronization beyond the existing assistant settings persistence.
