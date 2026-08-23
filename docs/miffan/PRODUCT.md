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

## Motion V1

Each explicit Miffan can use one of three motion profiles:

- Lively: faster rhythm, slightly broader gaze, and a light elastic response;
- Calm: slower breathing, smaller movement, and restrained reactions;
- Curious: eyes lead the response, followed by a small delayed lean.

Curious is the compatibility default for existing Miffan and legacy `Dummy` assistants. Profiles tune one shared semantic animation system; they do not own independent animation clips.

## Semantic motion states

Components ask Miffan to express meaning rather than selecting animation clips. The shared V1 vocabulary is:

- Idle
- Thinking
- Happy
- Error
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

## Out of scope for Appearance V1

- interchangeable contents, accessories, and materials;
- downloadable character packs;
- an arbitrary color picker;
- cloud synchronization beyond the existing assistant settings persistence.
