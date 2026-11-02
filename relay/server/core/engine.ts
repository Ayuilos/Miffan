// The per-host relay engine: everything that happens for one hostId (control connection, sessions,
// forwarding, metering, enforcement). It is platform-neutral; the Worker runs one per Durable Object
// and the Bun server one per active host. The platform supplies sockets, an alarm, SQL and the
// global budget through the small interfaces below.
//
// Hibernation rule (Workers): the object may be evicted whenever it is idle for a few seconds, so
// nothing that matters lives only in memory. Per-socket state (role, sid, deadlines, unflushed
// metering) is kept in the socket attachment, and every timeout goes through the single alarm.

import type { BudgetState } from "./budget.ts";
import { CLOSE_CODE, LIMITS, type Channel, type Reason } from "./codes.ts";
import { paceFor, publicPolicy, type RelayConfig } from "./config.ts";
import { b64uDecode, b64uEncode, randomBytes, randomSid, verifyHostSignature, type Bytes } from "./crypto.ts";
import type { Route } from "./http.ts";
import { reject } from "./http.ts";
import {
  HostLedger,
  buildUsage,
  counted,
  pendingIsEmpty,
  requestUnits,
  quotaStatus,
  type Pending,
  type Usage,
} from "./ledger.ts";
import { checkNewSession, mediaSessionVerdict } from "./policy.ts";
import { RateWindow, allowanceBytes } from "./ratelimit.ts";
import type { Sql } from "./sql.ts";
import { utcDay } from "./time.ts";

// ---- platform interfaces -------------------------------------------------------------------

export type Frame = string | ArrayBuffer | Uint8Array;

export interface Sock {
  att(): Att;
  setAtt(a: Att): void;
  send(data: Frame): void;
  close(code: number, reason: string): void;
  /**
   * When the platform last answered a text `ping` on this socket by itself (the Worker's
   * auto-response), or null. Platforms where the engine answers `ping` omit it.
   */
  lastAutoPing?(): number | null;
}

export interface BudgetPort {
  state(): Promise<BudgetState>;
  /** Add an increment: bytes, active minutes and platform request units (see `requestUnits`). */
  report(bytes: number, minutes: number, requests: number): Promise<BudgetState>;
  /** Would a new host from `ip` be refused? Counts nothing. */
  checkIp(ip: string): Promise<{ ok: true } | { ok: false; retryAfter: number }>;
  /** Count a new host from `ip` (called once it has proved its key). */
  registerIp(ip: string): Promise<{ ok: true } | { ok: false; retryAfter: number }>;
}

export interface Platform {
  now(): number;
  /** Open sockets carrying `tag`. */
  sockets(tag: string): Sock[];
  allSockets(): Sock[];
  /** Replace the single alarm; `null` clears it. */
  setAlarm(at: number | null): void;
  waitUntil(p: Promise<unknown>): void;
  sql: Sql;
  budget: BudgetPort;
  log(line: Record<string, unknown>): void;
}

export interface RequestCtx {
  /** Lower-cased Host header of the request (signed by the connector). */
  host: string;
  ip: string;
  /** Accept the WebSocket upgrade. `after` runs once the socket can send. */
  accept(tags: string[], att: Att, after: (s: Sock) => void): Response;
}

// ---- attachments ---------------------------------------------------------------------------

export interface PendAtt {
  /** Bytes forwarded from this socket and not yet written to storage. */
  b: number;
  /** Active minutes credited through this socket and not yet written. */
  m: number;
  /** Last UTC minute credited through this socket. */
  lm: number;
  /** UTC day the unwritten counts belong to (absent on attachments from before this field existed). */
  d?: string;
  /** Messages received on this socket and not yet reported. */
  n?: number;
  /** Upgrades (this socket's own) not yet reported: 1 until the first write-back. */
  o?: number;
}

export interface HostAtt {
  role: "host";
  id: string;
  hid: string;
  authed: boolean;
  nonce: string;
  pub: string;
  host: string;
  at: number;
  /** Key of the last usage level pushed to the connector. */
  ub: string;
  /** Source address of a host that is not registered yet; counted (and dropped) once it authenticates. */
  reg?: string;
  /** Last `ping` the engine itself answered (Bun; the Worker answers pings without the engine). */
  lp?: number;
  x?: 1;
}

export interface SessAtt {
  role: "c" | "h";
  hid: string;
  sid: string;
  ch: Channel;
  at: number;
  paired: boolean;
  /** Time of the last binary frame sent from this socket (pairing time at first). */
  lb: number;
  /** Text frames (other than `ping`) received from this socket so far. */
  tx?: number;
  /** Last `ping` the engine itself answered (Bun; the Worker answers pings without the engine). */
  lp?: number;
  p: PendAtt;
  x?: 1;
}

export type Att = HostAtt | SessAtt;

// ---- helpers -------------------------------------------------------------------------------

function textBytes(s: string): number {
  return s.length * 3 <= LIMITS.maxTextFrame ? s.length : new TextEncoder().encode(s).length;
}

function frameBytes(m: Frame): number {
  return typeof m === "string" ? textBytes(m) : m.byteLength;
}

const zeroPending = (): Pending => ({ control: 0, media: 0, minutes: 0, msgs: 0, reqs: 0 });
const hasPending = (p: PendAtt) => Boolean(p.b || p.m || p.n || p.o);

// ---- engine --------------------------------------------------------------------------------

export class Engine {
  private hid = "";
  private ledgerRef?: HostLedger;
  private pend: Pending = zeroPending();
  private lmSockets = 0;
  private lm?: number;
  private pendDay: string;
  private budgetCache?: { state: BudgetState; at: number; day: string };
  /** Increments the global budget has not acknowledged yet; `reqs` is in request units. */
  private unreported = { bytes: 0, minutes: 0, reqs: 0 };
  private windows = new Map<string, RateWindow>();

  constructor(
    private p: Platform,
    private cfg: RelayConfig,
  ) {
    this.pendDay = utcDay(p.now());
    // After hibernation, rebuild what was only in memory from the sockets' attachments. Unwritten
    // counts keep the day they were made on, even when the object wakes after UTC midnight.
    for (const s of p.allSockets()) {
      const a = s.att();
      if (!this.hid) this.hid = a.hid;
      if (a.role === "host") continue;
      this.pend[a.ch] += a.p.b;
      this.pend.minutes += a.p.m;
      this.pend.msgs += a.p.n ?? 0;
      this.pend.reqs += a.p.o ?? 0;
      this.lmSockets = Math.max(this.lmSockets, a.p.lm);
      if (hasPending(a.p) && a.p.d) this.pendDay = a.p.d;
    }
  }

  // ---- small accessors ----

  private get ledger(): HostLedger {
    this.ledgerRef ??= new HostLedger(this.p.sql, this.hid);
    return this.ledgerRef;
  }

  private setHost(hostId: string): void {
    if (this.hid === hostId) return;
    this.hid = hostId;
    this.ledgerRef = undefined;
  }

  private log(ev: string, extra: Record<string, unknown> = {}): void {
    this.p.log({ ev, h: this.hid.slice(0, 8), ...extra });
  }

  private authedHost(): Sock | undefined {
    return this.p.sockets("host").find((s) => {
      const a = s.att();
      return a.role === "host" && a.authed && !a.x;
    });
  }

  /** Open sockets with this tag, not counting ones the relay has already begun to close. */
  private live(tag: string): Sock[] {
    return this.p.sockets(tag).filter((s) => !s.att().x);
  }

  private lastMinute(): number {
    this.lm ??= Math.max(this.ledger.meta().lastMinute, this.lmSockets);
    return this.lm;
  }

  private budgetEnabled(): boolean {
    const b = this.cfg.policy.budget;
    return b.globalDailyMb > 0 || b.globalDailyActiveHours > 0 || b.globalDailyRequests > 0;
  }

  /** The global budget state, refreshed from the budget service when the cached copy is old. */
  private async budgetFresh(): Promise<BudgetState> {
    if (!this.budgetEnabled()) return "ok";
    const now = this.p.now();
    const c = this.budgetCache;
    if (c && c.day === utcDay(now) && now - c.at < LIMITS.budgetCacheMs) return c.state;
    try {
      this.unreported.reqs += 1; // the call is itself a request to the budget object
      const state = await this.p.budget.state();
      this.budgetCache = { state, at: now, day: utcDay(now) };
      return state;
    } catch {
      this.log("budget_unreachable");
      return c?.state ?? "ok";
    }
  }

  private budgetCached(): BudgetState {
    if (!this.budgetEnabled()) return "ok";
    const c = this.budgetCache;
    return c && c.day === utcDay(this.p.now()) ? c.state : "ok";
  }

  private usage(now = this.p.now()): Usage {
    return buildUsage(now, this.ledger.totals(now), this.pend, this.budgetCached(), this.cfg.policy);
  }

  // ---- entry points ------------------------------------------------------------------------

  async start(route: Extract<Route, { kind: "host" | "connect" | "data" }>, rc: RequestCtx): Promise<Response> {
    this.setHost(route.hostId);
    if (route.kind === "host") return this.startHost(route, rc);
    if (route.kind === "connect") return this.startConnect(route, rc);
    return this.startData(route, rc);
  }

  private async startHost(route: Extract<Route, { kind: "host" }>, rc: RequestCtx): Promise<Response> {
    // A new host is checked against the per-IP limit here, but only counted (and given storage) once
    // it has proved its key, so unauthenticated requests cost nothing that lasts.
    const unregistered = !this.ledger.registered();
    if (unregistered) {
      try {
        this.unreported.reqs += 1;
        const r = await this.p.budget.checkIp(rc.ip);
        if (!r.ok) {
          this.log("registration_refused");
          return reject("too_many_registrations", r.retryAfter);
        }
      } catch {
        this.log("registration_check_unreachable"); // fail open: the limit is abuse protection, not security
      }
    }
    const nonce = b64uEncode(randomBytes(32));
    const att: HostAtt = {
      role: "host",
      id: b64uEncode(randomBytes(6)),
      hid: this.hid,
      authed: false,
      nonce,
      pub: b64uEncode(route.pub),
      host: rc.host,
      at: this.p.now(),
      ub: "",
      ...(unregistered ? { reg: rc.ip } : {}),
    };
    const res = rc.accept(["host"], att, (s) => s.send(JSON.stringify({ t: "challenge", nonce })));
    this.reschedule();
    return res;
  }

  private async startConnect(route: Extract<Route, { kind: "connect" }>, rc: RequestCtx): Promise<Response> {
    const now = this.p.now();
    let host = this.authedHost();
    if (host && this.silent(host, now)) {
      // The computer went away without closing (sleep, lost network): it is offline, not slow.
      this.log("host_silent");
      this.shut(host, CLOSE_CODE.host_offline, "host_offline");
      host = undefined;
    }
    // Sessions whose other end vanished must not hold the per-host session slots.
    this.reapSilentSessions(now);
    const budget = host ? await this.budgetFresh() : "ok";
    const rej = checkNewSession({
      ch: route.ch,
      hostOnline: host !== undefined,
      policy: this.cfg.policy,
      budget,
      counted: host ? counted(this.ledger.totals(now), this.pend) : counted(this.ledger.totals(now), zeroPending()),
      sessions: this.live("c").length,
      now,
    });
    if (rej || !host) {
      this.log("session_refused", { reason: rej?.reason });
      return reject(rej!.reason, rej!.retryAfter, rej!.status);
    }
    const sid = randomSid();
    const att: SessAtt = {
      role: "c",
      hid: this.hid,
      sid,
      ch: route.ch,
      at: now,
      paired: false,
      lb: now,
      p: { b: 0, m: 0, lm: 0, d: utcDay(now), o: 1 },
    };
    const res = rc.accept(["c", `c:${sid}`], att, () => {});
    this.countRequest();
    try {
      host.send(JSON.stringify({ t: "open", sid, ch: route.ch, pace: paceFor(this.cfg, route.ch) }));
    } catch {
      // The control connection is gone; the pairing timeout will report host_timeout.
    }
    this.log("session_open", { ch: route.ch });
    this.reschedule();
    return res;
  }

  private async startData(route: Extract<Route, { kind: "data" }>, rc: RequestCtx): Promise<Response> {
    const client = this.live(`c:${route.sid}`)[0];
    const ca = client?.att();
    if (!client || !ca || ca.role !== "c" || ca.paired || ca.x || this.p.sockets(`h:${route.sid}`).length > 0) {
      return reject("bad_request", undefined, 404);
    }
    const now = this.p.now();
    const att: SessAtt = {
      role: "h",
      hid: this.hid,
      sid: route.sid,
      ch: ca.ch,
      at: now,
      paired: true,
      lb: now,
      p: { b: 0, m: 0, lm: 0, d: utcDay(now), o: 1 },
    };
    const res = rc.accept(["h", `h:${route.sid}`], att, () => {});
    this.countRequest();
    client.setAtt({ ...ca, paired: true, lb: now });
    client.send(
      JSON.stringify({
        t: "ready",
        sid: route.sid,
        ch: ca.ch,
        pace: paceFor(this.cfg, ca.ch),
        policy: publicPolicy(this.cfg),
        usage: this.usage(now),
      }),
    );
    this.log("session_ready", { ch: ca.ch });
    this.reschedule();
    return res;
  }

  // ---- messages ----------------------------------------------------------------------------

  async onMessage(s: Sock, msg: Frame): Promise<void> {
    const a = s.att();
    if (a.x) return;
    // Every received message but `ping` is billed by the platform (the Worker answers `ping` without
    // waking the object). Messages of a host that has not authenticated are not counted: they belong
    // to no known host, and counting them would create its storage.
    if (msg !== "ping" && (a.role !== "host" || a.authed)) this.countMessage();
    if (a.role === "host") return this.hostMessage(s, a, msg);
    return this.sessionMessage(s, a, msg);
  }

  private countMessage(): void {
    const wasEmpty = pendingIsEmpty(this.pend);
    this.pend.msgs += 1;
    if (wasEmpty) this.reschedule();
  }

  private countRequest(): void {
    this.pend.reqs += 1;
  }

  private pong(s: Sock, a: Att): void {
    s.setAtt({ ...a, lp: this.p.now() });
    s.send("pong");
  }

  // ---- liveness ----------------------------------------------------------------------------
  //
  // Both ends ping every 30 s (PROTOCOL §5.4). A peer that vanished without closing its TCP connection
  // (a computer going to sleep, a phone switching networks) leaves a socket that looks open; one that
  // has not been heard from - no ping, no binary frame - for LIMITS.livenessMs is treated as gone.
  // These checks only run when the object is awake anyway (a new session, an alarm that is due).

  private lastHeard(s: Sock, a: Att): number {
    let t = Math.max(a.at, a.lp ?? 0, s.lastAutoPing?.() ?? 0);
    if (a.role !== "host") t = Math.max(t, a.lb);
    return t;
  }

  private silent(s: Sock, now: number): boolean {
    return now - this.lastHeard(s, s.att()) > LIMITS.livenessMs;
  }

  /** Close paired sessions with a side that has gone silent. */
  private reapSilentSessions(now: number): void {
    for (const c of this.live("c")) {
      const a = c.att();
      if (a.role !== "c" || !a.paired) continue;
      const h = this.peerOf(a);
      if (this.silent(c, now) || (h && this.silent(h, now))) {
        this.log("session_silent");
        this.closeSession(a.sid, CLOSE_CODE.peer_closed, "peer_closed");
      }
    }
  }

  private oversize(msg: Frame): boolean {
    return typeof msg === "string" ? textBytes(msg) > LIMITS.maxTextFrame : msg.byteLength > LIMITS.maxBinaryFrame;
  }

  private async hostMessage(s: Sock, a: HostAtt, msg: Frame): Promise<void> {
    if (msg === "ping") return this.pong(s, a);
    if (this.oversize(msg)) return this.shut(s, CLOSE_CODE.frame_too_large, "frame_too_large");
    if (a.authed) {
      // Nothing but `ping` is expected from an authenticated control connection (PROTOCOL §4).
      this.log("unexpected_message");
      return this.shut(s, CLOSE_CODE.unexpected_text, "bad_request");
    }
    const now = this.p.now();
    if (typeof msg !== "string" || now - a.at > LIMITS.challengeMs) return this.shut(s, CLOSE_CODE.unauthorized, "unauthorized");

    let sig: Bytes | null = null;
    try {
      const j = JSON.parse(msg) as { t?: unknown; sig?: unknown };
      if (j.t === "auth" && typeof j.sig === "string") sig = b64uDecode(j.sig);
    } catch {
      // falls through to the rejection below
    }
    const pub = b64uDecode(a.pub);
    if (!sig || !pub || !(await verifyHostSignature(pub, a.host, a.nonce, sig))) {
      this.log("auth_failed");
      return this.shut(s, CLOSE_CODE.unauthorized, "unauthorized");
    }

    const cur = s.att();
    if (cur.role !== "host" || cur.authed || cur.x) return; // raced with another message or a close
    // Only now that the signature is good does the new connection displace the old one.
    for (const old of this.p.sockets("host")) {
      const oa = old.att();
      if (oa.role === "host" && oa.authed && oa.id !== cur.id) {
        this.shut(old, CLOSE_CODE.replaced, "replaced");
        this.log("host_replaced");
      }
    }
    if (cur.reg !== undefined && !this.ledger.registered()) {
      try {
        this.unreported.reqs += 1;
        await this.p.budget.registerIp(cur.reg);
      } catch {
        this.log("registration_check_unreachable");
      }
      this.ledger.markRegistered();
      this.log("registered");
    }
    await this.budgetFresh();
    const usage = this.usage();
    const level = this.usageKey(usage);
    const { reg: _reg, ...rest } = cur;
    s.setAtt({ ...rest, authed: true, nonce: "", ub: level });
    // The control connection's upgrade and its auth message, counted only once the host is known.
    this.countRequest();
    this.pend.msgs += 1;
    s.send(
      JSON.stringify({
        t: "hello",
        hostId: this.hid,
        policy: publicPolicy(this.cfg),
        usage,
        capabilities: { dgram: "none" },
      }),
    );
    this.log("host_online");
    this.reschedule();
  }

  private peerOf(a: SessAtt): Sock | undefined {
    return this.live(a.role === "c" ? `h:${a.sid}` : `c:${a.sid}`)[0];
  }

  private async sessionMessage(s: Sock, a: SessAtt, msg: Frame): Promise<void> {
    if (msg === "ping") return this.pong(s, a);
    if (a.role === "c" && !a.paired) {
      // PROTOCOL §5.1: the app waits for `ready` before sending anything.
      this.shut(s, CLOSE_CODE.early_frame, "bad_request");
      return;
    }
    if (this.oversize(msg)) {
      this.shut(s, CLOSE_CODE.frame_too_large, "frame_too_large");
      this.peerClosed(a);
      return;
    }
    const peer = this.peerOf(a);
    if (!peer) {
      this.shut(s, CLOSE_CODE.peer_closed, "peer_closed");
      return;
    }
    const text = typeof msg === "string";
    let next = a;
    if (text) {
      // PROTOCOL §5.3: a session carries text only for the end-to-end handshake (the connector's
      // challenge and verdict, the app's auth). Anything more is refused rather than relayed unmetered.
      const tx = (a.tx ?? 0) + 1;
      if (tx > LIMITS.sessionTexts) {
        this.log("unexpected_text", { side: a.role });
        this.shut(s, CLOSE_CODE.unexpected_text, "bad_request");
        this.peerClosed(a);
        return;
      }
      next = { ...a, tx };
    }
    const bytes = frameBytes(msg);
    const now = this.p.now();
    const key = `${a.sid}|${a.role}`;
    let win = this.windows.get(key);
    if (!win) this.windows.set(key, (win = new RateWindow()));
    // Binary bytes are held to the channel's pace; every frame, text or binary, counts against the
    // frame limit, because the platform bills each received message (PROTOCOL §6.2).
    const binBytes = text ? 0 : bytes;
    if (win.wouldExceed(now, binBytes, allowanceBytes(paceFor(this.cfg, a.ch)), LIMITS.maxFramesPerWindow)) {
      this.log("rate_limited", { ch: a.ch });
      this.closeSession(a.sid, CLOSE_CODE.rate_limited, "rate_limited");
      return;
    }
    win.add(now, binBytes);
    try {
      peer.send(msg);
    } catch {
      this.closeSession(a.sid, CLOSE_CODE.peer_closed, "peer_closed");
      return;
    }
    this.account(s, next, bytes, now, !text);
  }

  /**
   * Meter one forwarded frame: its bytes always, and for binary frames also the activity time and
   * the active UTC minute (PROTOCOL §5.3, §6.3: only binary frames count as activity).
   */
  private account(s: Sock, a: SessAtt, bytes: number, now: number, binary: boolean): void {
    const day = utcDay(now);
    if (day !== this.pendDay) {
      void this.flush(); // counts so far belong to the previous day
      this.pendDay = day;
    }
    const wasEmpty = pendingIsEmpty(this.pend);
    this.pend[a.ch] += bytes;
    const next: SessAtt = { ...a, lb: binary ? now : a.lb, p: { ...a.p, b: a.p.b + bytes, n: (a.p.n ?? 0) + 1, d: day } };
    const minute = Math.floor(now / 60_000);
    if (binary && minute > this.lastMinute()) {
      this.lm = minute;
      this.pend.minutes += 1;
      next.p.m += 1;
      next.p.lm = minute;
    }
    s.setAtt(next);
    if (wasEmpty) this.reschedule();
  }

  // ---- closing -----------------------------------------------------------------------------

  /** Close one socket and mark it so that no later deadline calculation counts it. */
  private shut(s: Sock, code: number, reason: string): void {
    try {
      const a = s.att();
      s.setAtt({ ...a, x: 1 });
    } catch {
      // already gone
    }
    try {
      s.close(code, reason);
    } catch {
      // already closing
    }
  }

  private closeSession(sid: string, code: number, reason: Reason | string): void {
    for (const tag of [`c:${sid}`, `h:${sid}`]) for (const s of this.p.sockets(tag)) this.shut(s, code, reason);
    this.windows.delete(`${sid}|c`);
    this.windows.delete(`${sid}|h`);
    this.p.waitUntil(this.flush());
    this.reschedule();
  }

  private peerClosed(a: SessAtt): void {
    const peer = this.peerOf(a);
    if (peer) this.shut(peer, CLOSE_CODE.peer_closed, "peer_closed");
  }

  /** The remote end closed (or the connection failed). */
  onClose(s: Sock): void {
    let a: Att;
    try {
      a = s.att();
    } catch {
      return;
    }
    if (a.role === "host") {
      if (a.authed) this.log("host_offline");
      this.reschedule();
      return;
    }
    if (!a.x) this.peerClosed(a); // one side closed (any code): close the other with 1000 peer_closed
    this.windows.delete(`${a.sid}|c`);
    this.windows.delete(`${a.sid}|h`);
    this.p.waitUntil(this.flush());
    this.reschedule();
  }

  // ---- metering write-back and enforcement -------------------------------------------------

  /** Write pending counts to storage now; returns what was written. */
  private flushLocal(): Pending | null {
    if (pendingIsEmpty(this.pend)) return null;
    const delta = this.pend;
    this.pend = zeroPending();
    this.ledger.commit(this.p.now(), this.pendDay, delta, this.lastMinute());
    for (const s of this.p.allSockets()) {
      const a = s.att();
      if (a.role !== "host" && hasPending(a.p)) {
        try {
          s.setAtt({ ...a, p: { ...a.p, b: 0, m: 0, n: 0, o: 0 } });
        } catch {
          // closed meanwhile
        }
      }
    }
    return delta;
  }

  /** Flush to storage, report the increment to the global budget and enforce limits on the result. */
  async flush(): Promise<void> {
    const delta = this.flushLocal();
    if (!delta) return;
    this.unreported.bytes += delta.control + delta.media;
    this.unreported.minutes += delta.minutes;
    this.unreported.reqs += requestUnits(delta.msgs, delta.reqs) + 1; // + this report itself
    const send = this.unreported;
    this.unreported = { bytes: 0, minutes: 0, reqs: 0 };
    try {
      const state = await this.p.budget.report(send.bytes, send.minutes, send.reqs);
      const now = this.p.now();
      this.budgetCache = { state, at: now, day: utcDay(now) };
    } catch {
      this.unreported.bytes += send.bytes;
      this.unreported.minutes += send.minutes;
      this.unreported.reqs += send.reqs;
      this.log("budget_report_failed");
    }
    this.enforce();
  }

  private usageKey(u: Usage): string {
    const q = quotaStatus(this.cfg.policy, counted(this.ledger.totals(this.p.now()), this.pend), null, this.p.now());
    const level = q.fraction >= 1 ? 3 : q.fraction >= 0.8 ? 2 : q.fraction >= 0.5 ? 1 : 0;
    return `${level}:${u.budget}`;
  }

  /** Close media sessions that quota or budget no longer allow, and tell the connector about level changes. */
  private enforce(): void {
    const now = this.p.now();
    const c = counted(this.ledger.totals(now), this.pend);
    const verdict = mediaSessionVerdict(this.cfg.policy, this.budgetCached(), c, now);
    if (verdict) {
      for (const s of this.p.sockets("c")) {
        const a = s.att();
        if (a.role === "c" && a.ch === "media" && !a.x) {
          this.log("session_cut", { reason: verdict.code });
          if (verdict.code === "relay_busy") this.closeSession(a.sid, CLOSE_CODE.relay_busy, "relay_busy");
          else this.closeSession(a.sid, CLOSE_CODE.quota_exhausted, "quota_exhausted");
        }
      }
    }
    const host = this.authedHost();
    if (!host) return;
    const ha = host.att();
    if (ha.role !== "host") return;
    const usage = this.usage(now);
    const key = this.usageKey(usage);
    if (key !== ha.ub) {
      host.setAtt({ ...ha, ub: key });
      try {
        host.send(JSON.stringify({ t: "usage", usage }));
      } catch {
        // control connection is closing
      }
    }
  }

  // ---- alarm -------------------------------------------------------------------------------

  async onAlarm(): Promise<void> {
    this.unreported.reqs += 1; // every alarm run is a billed request
    // Timers may fire a few ms early; treat a deadline that close as due.
    const now = this.p.now() + 5;
    this.reapSilentSessions(now);
    for (const s of this.p.allSockets()) {
      const a = s.att();
      if (a.x) continue;
      if (a.role === "host") {
        if (!a.authed && now - a.at >= LIMITS.challengeMs) this.shut(s, CLOSE_CODE.unauthorized, "unauthorized");
        else if (a.authed && this.silent(s, now)) {
          this.log("host_silent");
          this.shut(s, CLOSE_CODE.host_offline, "host_offline");
        }
      } else if (a.role === "c") {
        if (!a.paired) {
          if (now - a.at >= LIMITS.pairMs) {
            this.log("host_timeout");
            this.shut(s, CLOSE_CODE.host_timeout, "host_timeout");
          }
        } else {
          const idle = this.idleDeadline(a);
          if (idle !== null && now >= idle) {
            this.log("session_idle");
            this.closeSession(a.sid, CLOSE_CODE.idle, "idle");
          }
        }
      }
    }
    if (!pendingIsEmpty(this.pend) && now >= this.flushDeadline()) await this.flush();
    // The floor keeps a deadline that cannot be cleared (a socket that failed to close) from re-arming in a tight loop.
    this.reschedule(1000);
  }

  private idleDeadline(a: SessAtt): number | null {
    const idleMs = this.cfg.policy.limits.idleTimeoutS * 1000;
    if (idleMs <= 0) return null;
    const peer = this.peerOf(a)?.att();
    const peerLb = peer && peer.role !== "host" ? peer.lb : 0;
    return Math.max(a.lb, peerLb) + idleMs;
  }

  private flushDeadline(): number {
    return this.ledger.meta().lastFlush + LIMITS.flushMs;
  }

  /** Set the single alarm to the earliest outstanding deadline of any kind. */
  private reschedule(minDelayMs = 10): void {
    const now = this.p.now();
    let next = Infinity;
    for (const s of this.p.allSockets()) {
      const a = s.att();
      if (a.x) continue;
      if (a.role === "host") {
        if (!a.authed) next = Math.min(next, a.at + LIMITS.challengeMs);
      } else if (a.role === "c") {
        if (!a.paired) next = Math.min(next, a.at + LIMITS.pairMs);
        else {
          const idle = this.idleDeadline(a);
          if (idle !== null) next = Math.min(next, idle);
        }
      }
    }
    if (!pendingIsEmpty(this.pend)) next = Math.min(next, Math.max(this.flushDeadline(), now));
    this.p.setAlarm(next === Infinity ? null : Math.max(next, now + minDelayMs));
  }

  /** True when this engine holds nothing in memory that matters (Bun uses it to drop idle hosts). */
  isIdle(): boolean {
    return this.p.allSockets().length === 0 && pendingIsEmpty(this.pend);
  }
}
