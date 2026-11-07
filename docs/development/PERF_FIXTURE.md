# Performance fixture and macrobenchmarks

Repeatable numbers on a realistic data set, before and after each optimisation, on the emulator and on a
phone. This page covers three things: the **fixture** (a deterministic data set loaded with one adb command),
the **macrobenchmarks** (`app/baselineprofile`, run by `scripts/perf/run_bench.sh`) and the **baseline profile**
generator that uses the same journeys.

Emulator numbers (software rendering, shared host) only prove that the harness works. Take real numbers on a
phone with the `benchmarkRelease` build.

## The fixture

```bash
adb shell am broadcast -a me.ayuilos.miffan.PERF_FIXTURE \
    -n <package>/me.ayuilos.miffan.perf.PerfFixtureReceiver
```

| Build | `<package>` |
| --- | --- |
| debug (`assembleDebug`) | `me.ayuilos.miffan.app.debug` |
| macrobenchmark (`assembleBenchmarkRelease`, `assembleNonMinifiedRelease`) | `me.ayuilos.miffan.app.bench` |

The command prints `data="OK partners=6 segments=… messages=…"` when it is done (about 10–40 s) or
`data="FAILED …"`. Restart the app afterwards (`adb shell am force-stop <package>`): the receiver replaces data
under a possibly running app, and the unread state of the Chats list is read once at start (the benchmarks
force-stop the app after loading).

**It replaces everything.** The fixture first updates the settings, then clears every database table and the
full-text index, then writes its data, so running it twice gives identical counts (names and ids are
name-based UUIDs, text comes from a seeded generator). Do not run it on a device whose data you want to keep.
Pass `--el anchor <epochMillis>` to pin the "now" the timestamps are laid out against; the default is midnight
at the start of the current day.

It is compiled only into `debug`, `benchmarkRelease` and `nonMinifiedRelease` (`app/src/perfFixture`, wired up in
`app/build.gradle.kts`, receiver declared in each of those build types' manifest). The shipping `release` APK
has neither the classes nor the manifest entry; check with
`unzip -p app-release.apk classes*.dex | strings | grep -c PerfFixture` (must print 0) or
`apkanalyzer dex packages`.

### What it creates

Everything goes through the app's own repositories, DAOs and settings store, so rows have the shape the app
writes (`ConversationRepository.insertConversation` including the full-text index, `PartnerChatStore`,
`PartnerChatDAO`, `FavoriteRepository.addNodeFavorite`, `RevisionRepository.record`, `SettingsStore.update`).

**Settings**: easy (IM) interface, onboarding finished, no mode chooser, no what's-new card, update checks
paused, whale-theme intro seen, chat hint seen. One OpenAI-compatible provider "Fake LLM" with base URL
`http://127.0.0.1:18799/v1` and model `fake-llm` is the chat and fast model. Suggestions are off, memory is off.
Runtime permissions (notifications) cannot be granted by the app: `run_bench.sh` grants them with `pm grant`.

**Partners** (six, different looks): 小米 (rice bowl, classic; the app's default assistant id, so there is no
seventh partner), 阿青 (sprout, matcha), 糯米 (dumpling, sakura), Nova (stargazer, moonlight), 海盐 (whale girl),
墨玉 (fox emoji). 小米 and 糯米 are pinned; 阿青 and Nova sit in the group "工作", 海盐 in "生活".

| Thread | Messages | Segments | Span |
| --- | --- | --- | --- |
| 小米 (main) | 2000 | 60 | 90 days |
| 阿青 | 300 | 10 | 60 days |
| 糯米 | 200 | 7 | 45 days |
| Nova | 120 | 5 | 40 days |
| 海盐 | 60 | 3 | 30 days |
| 墨玉 | 20 | 1 | 20 days |
| Group chat "周末出游小分队" (阿青, 糯米, Nova, 海盐 + owner) | 500 | – | 30 days |
| Two-partner chat 阿青 ↔ 糯米 | 100 | – | 10 days |

Segments are IM thread segments: closed ones have `thread_summary` and `thread_closed_at`, the last is open,
`topic_start` varies (auto / idle / owner / welcome). Message mix per thread: about half short chat lines
(Chinese and English), a fifth long Markdown (headings, lists, tables, code blocks, LaTeX, quotes), some with
reasoning parts, about a seventh tool turns (search, calendar query and create, workspace file + shell, computer
steps with a screenshot), about 8% of user messages quote an earlier reply. The main thread also has six
generated images (small local PNGs in `files/images/perf_fixture/`) and 30 "the partner changed its own
preferences" turns, each with a matching agent revision (the notices in the thread).

Other data: 50 favorites (40 from the main thread), 30 revisions/notices, unread replies (阿青 3, Nova 12,
墨玉 10 (all of its replies), 海盐 1; the group chat 4), a partner contact grant between 阿青 and 糯米.

## The fake model

`scripts/perf/fake_llm.py` (Python 3 standard library) serves `/v1/models` and `/v1/chat/completions` on
`127.0.0.1:18799`. A streamed answer is the same ~1500 tokens of Markdown (headings, table, code, LaTeX) at 40
tokens/s; a non-streamed request gets a one-line reply. `--tokens`, `--rate`, `--reasoning` change that.

* **Emulator**: it has a global HTTP proxy that connects to the Mac's loopback, so `127.0.0.1` works as is.
* **Phone**: `adb reverse tcp:18799 tcp:18799` (`run_bench.sh` does it for non-emulator serials).

## Macrobenchmarks

```bash
scripts/perf/run_bench.sh emulator-5560                      # whole suite
scripts/perf/run_bench.sh <phone> --tests chatsScroll,streaming --iterations 10
scripts/perf/run_bench.sh <phone> --compilation none         # without the baseline profile
scripts/perf/run_bench.sh <phone> --frames timeline          # frame-timeline metric only
```

It starts the fake model if nothing listens on 18799, runs
`./gradlew :app:baselineprofile:connectedBenchmarkReleaseAndroidTest` (which builds and installs the
`benchmarkRelease` app, debug-signed, as `me.ayuilos.miffan.app.bench` and uninstalls it afterwards) and prints
a table from the JSON files under
`app/baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/`
(`scripts/perf/summarize.py <dir>` prints the table again). The tests load the fixture themselves before they
start (the streaming test reloads it first, because its replies add messages).

| Test (`ImBenchmarks`) | What it does | Metrics |
| --- | --- | --- |
| `startupCold` / `startupWarm` | start into the 消息 tab; the list calls `ReportDrawn` once it has data | TTID, TTFD, frames |
| `chatsScroll` | fling the 消息 list down and up, three rounds | frames |
| `threadOpenAndScroll` | tap 小米, open the 2000-message thread, show the history if the welcome screen is up, fling back six times | `ThreadOpenFirstMs` (trace slice from tap to messages loaded), frames |
| `tabSwitch` | 消息 → 伙伴 → 发现 → 我 → 消息, three rounds | frames |
| `streaming` | send a message in 小米's thread, let the fake model stream ~1500 tokens | frames over the whole reply |

`StartupBenchmarks` (`--tests startup`) compares cold start without and with the baseline profile.

Table columns: `ttid`/`ttfd` and `open` in ms (median over iterations); `p50 p90 p99` are the frame CPU time over
all frames in ms; `jank%` is the share of frames that missed their deadline (`frameOverrunMs > 0`); `frames` is
the frame count. Elements are found by resource id (`testTag` + `testTagsAsResourceId`):
`im_tab_{chats,partners,discover,me}`, `im_chat_list`, `im_chat_row_<id>`, `im_partner_list`,
`im_partner_row_<id>`, `thread_list`, `thread_show_history`, `thread_input`, `thread_send`, `thread_stop`.

Instrumentation arguments (via `-Pandroid.testInstrumentationRunnerArguments.<name>=…`): `compilation`
(`partial` default, `none`, `full`), `iterations`, `frames` (`both`, `timeline`, `gfx`), `class`. On emulators
`run_bench.sh` defaults to `frames=gfx`: software rendering often records no frame-timeline slices, which makes
`FrameTimingMetric` abort the test; the gfxinfo metric (5 ms buckets) still works. The benchmark process also
drops its own HTTP proxy properties (the emulator's global proxy would answer the on-device trace processor at
`localhost:9001` with 502); the device setting is never touched, and the app under test keeps the proxy.

## Baseline profile

`BaselineProfileGenerator` has two tests: `startup` (cold start into 消息, also feeds the startup profile) and
`journeys` (open the main thread and scroll its history, reply, 伙伴 tab and a profile page, 发现, 我, back).
It uses the `nonMinifiedRelease` build, which carries the fixture and loads it itself. Start
`scripts/perf/fake_llm.py` first so the reply is captured too.

```bash
python3 scripts/perf/fake_llm.py &
ANDROID_SERIAL=emulator-5560 ./gradlew :app:generateReleaseBaselineProfile
```

The plugin writes `app/src/release/generated/baselineProfiles/baseline-prof.txt` and `startup-prof.txt`;
check that they contain `Lme/ayuilos/miffan/ui/im/` classes (`grep -c ui/im/ baseline-prof.txt`). The generator
needs an API 33+ device. Regenerate after larger changes to what the IM shell runs at start-up or in its main
journeys; compare with `run_bench.sh --tests startup`.

## Things to know

* A fresh git worktree needs the untracked native inputs before any release-type build: `git submodule update --init
  material3/material-color-utilities`, and copies of `.deps/p6a` and `rdp/build/native` from the main checkout.

* The fixture wipes all data of the app it runs in. The benchmark variants carry their own application id
  (`me.ayuilos.miffan.app.bench`, set in `app/build.gradle.kts`), so the real app (`me.ayuilos.miffan.app`, release
  key) is never replaced or uninstalled. `PerfFixtureReceiver` answers `REFUSED` unless its package ends in `.debug`
  or `.bench`, and `run_bench.sh` refuses to run unless the benchmark package ends in `.bench`.
* `benchmarkRelease` is R8-minified like the release; a debug build is not comparable.
* The `streaming` test needs the fake model; without it the reply never starts and the test fails.
* Because the main thread is not touched for hours in the fixture's timeline (midnight anchor), the thread may
  open on the partner's welcome screen (`thread_show_history`); the benchmarks tap through it.
