# Miffan Character System

## Whale girl theme V1

The optional 蓝色大肥鱼 collection introduces a separate head-only whale girl identity.
The approved native revision uses a juvenile round face and oversized head, curved
fringe and ahoge, frilled headband, two lateral whale fins and a single side bow.
Eyes use one solid shape and one cutout highlight. There is
no body or full-body illustration. This is a new character family, not a bowl variant;
the bowl recognition rules below continue to apply to every Miffan inhabitant.

Users can select the whale girl per assistant in its basic settings or avatar picker.
Appearance settings preview the light and dark whale theme, apply its colors, select
the whale avatar for the current assistant, and independently choose a launcher icon.
Applying colors disables dynamic color explicitly. The character uses a compact flat blue palette: blue hair, deeper fins and bow,
a pale face, and white frills. Night colors keep these regions distinct. Existing assistants and custom avatars are not migrated.
The native renderer preserves the approved reference silhouette with simplified
eyes. Space outside the head is transparent; shape interiors use the palette paper
color, with no rectangular portrait tile. The whale girl has one
optimistic, food-loving, occasionally dizzy personality; there is no whale personality
or motion-profile selector. Choosing an avatar or theme never changes a model, system
prompt or conversation history.

The selected expressions are A (gentle default smile), C (contented petting), F (smug
success), I (surprised reminder), K (big-mouth rice eating), L (puffed-cheek chewing),
M (dizzy spiral-eye thinking), and O (exaggerated dozing with a sleep bubble). Eating
may include the small rice bowl and chopsticks, but never introduces a body or hands.
Ordinary waiting uses eating; text output uses chewing; only an actual unfinished
reasoning part uses dizzy thinking. The default face is positive and never tearful.
Reminder reactions use widened eyes, an open mouth and a large exclamation mark.
Eating has approach, bite and withdrawal beats; chewing alternates cheek volume with
a swallow pause. Sleeping coordinates slow breathing, a small nod and a nose-anchored
bubble. Petting, success and reminders are short reactions. An inactive scene may doze at night
or after prolonged inactivity, and wakes for input or interaction. Errors stop the
animation on the neutral positive still with a readable error cue. Historical avatars
remain still, and reduced motion preserves the matching expression.
Launcher choices include the original bowl and one whale girl icon. Legacy deep-sea selections migrate to the same whale icon, independently of light/dark mode.
Users can restore the original launcher icon independently of the theme or character.
The selected icon also identifies the app in share targets, text-processing actions and
registered link handlers. Each intent resolves to one selected entry. Screens owned by
other apps that read only the package-level application icon may still show the fixed
installation icon; that icon is not dynamically replaced by component aliases.

Onboarding offers one switch for the whale collection. Turning it on explicitly creates
or reuses the dedicated assistant and applies the palette, immediately updating the
onboarding head, background, connection card and controls. Turning it off restores only
the palette and retains the assistant. Existing assistants remain unchanged; the switch
does not start or cancel authorization or change global providers/models.
Startup artwork follows the whale theme or independently selected whale launcher icon,
including the loading state before the full settings are available.

Existing users discover the collection through a one-time introduction on an ordinary
chat-home launch. It previews smiling, rice eating and dizzy thinking, offers one-click
theme/character trial, and leaves changing the launcher icon unchecked by default.
First installations use onboarding instead. Sharing, translation and other external
entry flows do not trigger the introduction. "Later" permanently dismisses the card;
an unseen-theme indicator remains in the drawer/settings route for at most 30 days,
and clears when appearance settings are visited or the collection is applied.
Only confirming the trial creates and selects a persistent, independent whale assistant,
then opens a new chat. Existing assistants and conversations remain unchanged.
A stored assistant id prevents duplicate creation and preserves renamed/customized versions;
after deletion, a later confirmed trial may create another. The assistant has one editable
positive, rice-loving personality, with a complete default system prompt and normal assistant
configuration. Its reset action confirms replacement of all configuration while preserving
its id, chat history and saved memory. The preset uses the global model by default.
Appearance restore only restores the previous palette; it neither deletes nor resets the
whale assistant. Launcher choice remains independent.

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

## Continuous expression and attention

Face changes share one parameterized mouth contour and independently adjustable eye openness.
Transitions begin from the displayed expression, including when a new state interrupts a transition.
Thinking gaze chooses a direction, holds it, and periodically returns to the center instead of
continuously orbiting. Input focus and attention temporarily take precedence over ambient gaze.

Repeated taps retarget persistent springs. Eyes respond before the profile's delayed body reaction,
and every reaction returns to its resting pose after the latest tap. Character signature strength
and error settling also transition continuously; existing silhouettes, palettes, and saved avatar
settings remain unchanged.

## Chat continuity and restrained avatars

The empty-chat character and the waiting character share one renderer instance. On the first
active send it shrinks and moves into the waiting slot; scrolling thereafter tracks that slot
directly. A queued submission does not replay this handoff or interrupt the current expression.
Custom image and emoji avatars retain their existing presentation.

Historical message avatars are still at rest. Active small avatars retain readable expressions
with less body movement. Only a confirmed successful assistant reply can briefly celebrate;
stopping, failing, storing a user-only message, or awaiting tool approval cannot. Starting another
turn clears the previous celebration. Loading takes precedence over old error reminders during
a retry; otherwise visible errors take precedence over celebration, and input takes precedence
over update reminders.

The system's disabled-animation setting is observed live. It disables the spatial handoff and
ambient cycles, blinks, and wandering gaze while preserving semantic expressions. The Lab's
reduced-motion preview additionally allows checking the same quiet rendering without changing
the device setting.

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
