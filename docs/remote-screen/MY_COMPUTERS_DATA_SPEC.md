# My computers: data layer spec

Easy chat (IM shell) gets a "我的电脑" list under the Me tab, a per-computer detail page, and a setup
flow that can run without a partner. The UI is built separately; this spec covers the data layer only.
The contract is already committed as stubs (`TODO("data layer")`); implement the bodies, keep the
public signatures and KDoc, and add tests.

## Files you own

- `app/src/main/java/me/ayuilos/miffan/data/ai/computer/PartnerComputers.kt`
- `app/src/main/java/me/ayuilos/miffan/ui/im/computer/ComputerSetupVM.kt` (the VM only, not the pages)
- `app/src/main/java/me/ayuilos/miffan/di/*.kt` if wiring changes
- New tests under `app/src/test/java/me/ayuilos/miffan/...`

Do not touch any `*Page.kt`, `*Step.kt`, `RouteActivity.kt`, `ComputersPage.kt` or string resources.

## PartnerComputers

Concepts: a computer is a `RemoteHostEntity`. Easy chat uses one remote workspace per host, the
"computer workspace". Partners bind to a workspace through `Assistant.workspaceId`.

1. **Computer workspace rule** (one private helper, used everywhere, including `observe`/`observeAll`
   and `ensureComputerWorkspace`): among the host's remote workspaces, pick the one with the most
   partners bound to it; ties and the no-partner case go to the earliest `createdAt`, then `id`.
   Note `WorkspaceDAO.listFlow()` is ordered by `updated_at DESC`, so never rely on list order.
2. `observeAll()`: combine settings, `listFlow()`, `listHostsFlow()`; one `KnownComputer` per host,
   sorted by name (case-insensitive). `address` is `user@host`, plus `:port` only when port != 22.
   `platform` = `RemoteScreenPlatform.parse(host.screenPlatform)`. `otherWorkspaceCount` = remote
   workspaces on the host minus the computer workspace. `partnerIds` = assistants (in
   `settings.assistants` order) bound to *any* workspace of the host. `distinctUntilChanged()`.
3. `observeComputer(hostId)`: same mapping for one host, null when it does not exist.
4. `ensureComputerWorkspace(hostId)`: existing computer workspace id, else create one exactly like
   `ComputerSetupVM.ensureWorkspace` does today (`remoteHome`, unique name from host name), and return
   its id. Move that logic here; the VM must call this instead.
5. `bind(hostId, assistantIds)`: ensure the workspace (step 4), then in one `settingsStore.update`, for
   each listed assistant: if already bound to any workspace of this host, leave it unchanged;
   otherwise `withWorkspaceBinding(workspace).copy(computerUse = ComputerUseMode.ASK)`. Unknown ids are
   ignored. An empty set still ensures nothing and returns.
6. `delete(hostId)`: if `otherWorkspaceCount > 0`, throw `ComputerHasWorkspacesException(count)` and
   change nothing. Otherwise delete the computer workspace if any via `WorkspaceRepository.delete`
   (it already unbinds partners), then `WorkspaceRepository.deleteHost(hostId)`.

`PartnerComputers.observe(assistantId)` keeps its current behaviour.

## ComputerSetupVM

The constructor now takes `ComputerSetupArgs(assistantId: Uuid?, hostId: String?, edit: Boolean)`
(Koin passes it via `parametersOf(args)`; DI line already updated).

1. **Init**: `hostId` with `edit = false` -> behave as `chooseHost(hostId)`. `hostId` with
   `edit = true` -> `state = ComputerSetupState(step = ADDRESS, hostId = hostId)` without connecting;
   add `val editing: Boolean` to `ComputerSetupState` (true in this mode) so the address step can
   prefill from the host and allow editing. No `hostId` -> unchanged (CHOOSE).
2. **`back()`** in edit mode: ADDRESS is the first step (the page pops); VERIFY/PREPARE go back to
   ADDRESS instead of CHOOSE. Outside edit mode unchanged.
3. **`submitAddress(..., auth: ComputerSetupAuth?)`**: new computer as today (auth required; null
   -> error). In edit mode call `workspaces.updateHost(hostId, name, address, port, username,
   authentication = (auth as? Password)?.let { RemoteAuthentication.Password(it.value) },
   sshKeyId = (auth as? AppKey)?.keyId)`; null keeps the saved sign-in. Then re-read the host: if
   `trustedHostKeySha256 == null` -> `readFingerprint`, else `connectAndProbe`. Note the address step
   UI currently treats `state.hostId != null` as "already saved, retry fingerprint"; that check must
   become `hostId != null && !editing` on the UI side (UI owner will do it) — just make sure editing
   state is distinguishable.
4. **`boundPartnerIds`**: real flow — partners bound to any workspace of the current `state.hostId`
   (derive from settings + workspaces; empty when no host yet).
5. **`bind(assistantIds: Set<Uuid>)`**: `run(BINDING) { partnerComputers.bind(hostId, ids); step = DONE }`.
   Empty set is allowed (computer kept, nobody bound) and still reaches DONE. Delete the old private
   `bindOld`. Inject `PartnerComputers` (update Koin).
6. `replacedBinding` is only meaningful with an `assistantId`; null otherwise (already stubbed).
7. Replace the VM's private `ensureWorkspace` with `PartnerComputers.ensureComputerWorkspace`.

## Acceptance

- `./gradlew :app:testDebugUnitTest` passes; `./gradlew :app:compileDebugKotlin` passes.
- Unit tests (fakes or in-memory Room, whichever existing tests use — look at
  `app/src/test/.../RemoteComputerControlTest.kt` and other repository tests for patterns) covering:
  computer-workspace rule (most partners, tie by createdAt), `observeAll` mapping (address port rule,
  partnerIds across several workspaces, otherWorkspaceCount), `bind` keeps already-bound partners'
  mode and sets ASK for new ones, `delete` refuses with other workspaces and otherwise removes
  workspace + host and unbinds partners.
- Commit on your branch `feature/my-computers-data` only. Do not commit to `feature/my-computers`.
  Do not push.
