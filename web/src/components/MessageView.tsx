// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

import { useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import {
  Archive,
  ArrowLeft,
  ArrowDown,
  ArrowUp,
  Check,
  ChevronDown,
  Download,
  FileText,
  Forward,
  MoreHorizontal,
  Paperclip,
  Reply,
  ReplyAll,
  Search,
  Trash2,
  WifiOff,
  X,
} from "lucide-react";
import { useMail } from "../store";
import {
  moveMessage,
  dateLabel,
  sizeLabel,
  type Attachment,
} from "../lib/mail";
import { Button } from "./ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "./ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "./ui/dropdown-menu";
import { IconButton } from "./shared";
export function MessageView() {
  const { id } = useParams();
  const { state, setState, notify } = useMail();
  const navigate = useNavigate();
  const location = useLocation();
  const message = state.messages.find((m) => m.id === id);
  const [details, setDetails] = useState(false);
  const [finding, setFinding] = useState(false);
  const [query, setQuery] = useState("");
  const [activeMatch, setActiveMatch] = useState(0);
  const [attachment, setAttachment] = useState<Attachment | null>(null);
  const [loading, setLoading] = useState("");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState<string[]>([]);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const chunks = useMemo(() => {
    const text = message?.bodyText || "";
    if (!query.trim()) return [{ text, match: false }];
    const term = query.toLocaleLowerCase();
    const lower = text.toLocaleLowerCase();
    let pos = 0;
    const out: { text: string; match: boolean }[] = [];
    let found;
    while ((found = lower.indexOf(term, pos)) !== -1) {
      out.push(
        { text: text.slice(pos, found), match: false },
        { text: text.slice(found, found + term.length), match: true },
      );
      pos = found + term.length;
    }
    out.push({ text: text.slice(pos), match: false });
    return out;
  }, [message?.bodyText, query]);
  const matches = chunks.filter((c) => c.match).length;
  useEffect(() => {
    bodyRef.current
      ?.querySelector('[data-current="true"]')
      ?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  }, [activeMatch, query]);
  if (!message)
    return (
      <main className="mail-shell">
        <div className="empty-state">
          <h1>Message not found</h1>
          <Button onClick={() => navigate("/mail")}>Back to inbox</Button>
        </div>
      </main>
    );
  const back = () => navigate(location.state?.returnTo || "/mail");
  const move = (role: string) => {
    setState((s) => moveMessage(s, message.id, role));
    notify(role === "archive" ? "Message archived" : "Message moved to Trash");
    back();
  };
  const reply = (all = false, forward = false) =>
    navigate(
      "/compose?" +
        (forward ? "forward=" : "reply=") +
        message.id +
        (all ? "&all=1" : ""),
    );
  function openAttachment(item: Attachment) {
    if (item.downloadState === "available") {
      setAttachment(item);
      return;
    }
    if (state.offline) {
      notify(
        "This attachment has not been downloaded. Turn off offline preview to download it.",
      );
      return;
    }
    setLoading(item.id);
    timer.current = setTimeout(() => {
      setState((s) => ({
        ...s,
        messages: s.messages.map((m) =>
          m.id === message!.id
            ? {
                ...m,
                attachments: m.attachments.map((a) =>
                  a.id === item.id ? { ...a, downloadState: "available" } : a,
                ),
              }
            : m,
        ),
      }));
      setLoading("");
      setAttachment({ ...item, downloadState: "available" });
    }, 550);
  }
  let matchIndex = -1;
  const related =
    state.features.threads && message.relatedGroupId
      ? state.messages.filter(
          (m) =>
            m.relatedGroupId === message.relatedGroupId && m.id !== message.id,
        )
      : [];
  return (
    <main className="message-shell">
      <header className="message-header">
        <div className="message-toolbar">
          <IconButton label="Back to messages" onClick={back}>
            <ArrowLeft />
          </IconButton>
          <div className="header-actions">
            <IconButton label="Archive message" onClick={() => move("archive")}>
              <Archive />
            </IconButton>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="Message options"
                >
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuItem onSelect={() => setFinding(true)}>
                  <Search size={16} />
                  Find in this message
                </DropdownMenuItem>
                <DropdownMenuItem
                  onSelect={() => {
                    setState((s) => ({
                      ...s,
                      messages: s.messages.map((m) =>
                        m.id === id ? { ...m, isRead: !m.isRead } : m,
                      ),
                    }));
                    notify(message.isRead ? "Marked unread" : "Marked read");
                  }}
                >
                  Mark {message.isRead ? "unread" : "read"}
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => move("archive")}>
                  Archive
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => move("trash")}>
                  Move to Trash
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        <div className="subject-heading">
          <h1 dir="auto">{message.subject || "(No subject)"}</h1>
          {message.attachments.length > 0 && <Paperclip size={21} />}
        </div>
      </header>
      {finding && (
        <div className="find-bar">
          <Search size={17} />
          <input
            aria-label="Find in this message"
            value={query}
            autoFocus
            placeholder="Find in message"
            onChange={(e) => {
              setQuery(e.target.value);
              setActiveMatch(0);
            }}
          />
          <span role="status">
            {matches ? `${activeMatch + 1} / ${matches}` : "0 matches"}
          </span>
          <IconButton
            label="Previous match"
            disabled={!matches}
            onClick={() => setActiveMatch((v) => (v - 1 + matches) % matches)}
          >
            <ArrowUp size={17} />
          </IconButton>
          <IconButton
            label="Next match"
            disabled={!matches}
            onClick={() => setActiveMatch((v) => (v + 1) % matches)}
          >
            <ArrowDown size={17} />
          </IconButton>
          <IconButton
            label="Close find"
            onClick={() => {
              setFinding(false);
              setQuery("");
            }}
          >
            <X size={17} />
          </IconButton>
        </div>
      )}
      {related.map((m) => (
        <section className="related-message" key={m.id}>
          <button
            onClick={() =>
              setExpanded((v) =>
                v.includes(m.id) ? v.filter((i) => i !== m.id) : [...v, m.id],
              )
            }
            aria-expanded={expanded.includes(m.id)}
          >
            <span>
              <strong dir="auto">{m.from.name}</strong>
              <small dir="auto">{m.preview}</small>
            </span>
            <time>{dateLabel(m.receivedAt)}</time>
            <ChevronDown size={18} />
          </button>
          {expanded.includes(m.id) && (
            <p className="plain-body" dir="auto">
              {m.bodyText}
            </p>
          )}
        </section>
      ))}
      <article className="message-article">
        <header className="sender-header">
          <div>
            <h2 dir="auto">{message.from.name}</h2>
            <button className="recipient-link" onClick={() => setDetails(true)}>
              To: {message.to[0]}
              {message.to.length + message.cc.length > 1
                ? ` + ${message.to.length + message.cc.length - 1}`
                : ""}
              <ChevronDown size={14} />
            </button>
          </div>
          <div className="sender-meta">
            <time>{dateLabel(message.receivedAt)}</time>
            <IconButton
              label="Sender and recipient details"
              onClick={() => setDetails(true)}
            >
              <MoreHorizontal size={20} />
            </IconButton>
          </div>
        </header>
        <div className="email-content" ref={bodyRef}>
          {message.bodyHtml && !query ? (
            <iframe
              title="Email body"
              sandbox=""
              srcDoc={`<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:"><style>body{margin:12px 0;color:#202734;font:17px/1.7 system-ui}h1{font-size:30px;line-height:1.3;color:#1376dc}h2{font-size:21px;margin-top:30px}blockquote{border-inline-start:3px solid #1376dc;margin:24px 0;padding:10px 20px;background:#eff7ff}a{color:#1376dc}</style></head><body dir="auto">${message.bodyHtml}</body></html>`}
            />
          ) : (
            <div className="plain-body" dir="auto">
              {chunks.map((part, i) => {
                if (!part.match) return <span key={i}>{part.text}</span>;
                matchIndex++;
                return (
                  <mark data-current={matchIndex === activeMatch} key={i}>
                    {part.text}
                  </mark>
                );
              })}
            </div>
          )}
        </div>
        {message.attachments.length > 0 && (
          <section className="attachments">
            <h3>
              {message.attachments.length} Attachment
              {message.attachments.length > 1 ? "s" : ""}
            </h3>
            {message.attachments.map((a) => (
              <button
                className="attachment-card"
                key={a.id}
                onClick={() => openAttachment(a)}
                disabled={!!loading}
              >
                <FileText size={32} strokeWidth={1.3} />
                <span>
                  <strong>{a.filename}</strong>
                  <small>
                    {sizeLabel(a.sizeBytes)} ·{" "}
                    {loading === a.id
                      ? "Downloading…"
                      : a.downloadState === "available"
                        ? "Available offline"
                        : "Tap to download"}
                  </small>
                </span>
                {a.downloadState === "available" ? (
                  <Check size={19} />
                ) : state.offline ? (
                  <WifiOff size={19} />
                ) : (
                  <Download size={19} />
                )}
              </button>
            ))}
          </section>
        )}
      </article>
      <footer className="message-bottom">
        <IconButton label="Reply" onClick={() => reply()}>
          <Reply />
        </IconButton>
        <IconButton label="Reply all" onClick={() => reply(true)}>
          <ReplyAll />
        </IconButton>
        <IconButton label="Delete message" onClick={() => move("trash")}>
          <Trash2 />
        </IconButton>
        <IconButton label="Forward" onClick={() => reply(false, true)}>
          <Forward />
        </IconButton>
        <IconButton
          label="Find in message"
          onClick={() => setFinding((v) => !v)}
        >
          <Search />
        </IconButton>
      </footer>
      <Dialog open={details} onOpenChange={setDetails}>
        <DialogContent>
          <DialogTitle className="dialog-title">Message details</DialogTitle>
          <DialogDescription>
            {new Date(message.receivedAt).toLocaleString("en-US", {
              timeZone: "UTC",
            })}{" "}
            UTC
          </DialogDescription>
          <dl className="recipient-details">
            <dt>From</dt>
            <dd>
              {message.from.name}
              <br />
              {message.from.address}
            </dd>
            <dt>To</dt>
            <dd>{message.to.join(", ")}</dd>
            {message.cc.length > 0 && (
              <>
                <dt>Cc</dt>
                <dd>{message.cc.join(", ")}</dd>
              </>
            )}
            <dt>Account</dt>
            <dd>
              {state.accounts.find((a) => a.id === message.accountId)?.address}
            </dd>
          </dl>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!attachment}
        onOpenChange={(v) => !v && setAttachment(null)}
      >
        <DialogContent>
          <DialogTitle className="dialog-title">
            {attachment?.filename}
          </DialogTitle>
          <DialogDescription>
            Sample attachment · {attachment && sizeLabel(attachment.sizeBytes)}
          </DialogDescription>
          <div className="attachment-preview">
            <FileText size={56} strokeWidth={1} />
            <p>Your attachment is ready.</p>
            <span>This demo provides a sample file for download.</span>
          </div>
          <Button asChild className="w-full">
            <a
              href={
                attachment?.mimeType === "application/pdf"
                  ? "./assets/sample-ticket.pdf"
                  : "./assets/thunderbird-logo.png"
              }
              download={
                attachment?.mimeType === "application/pdf"
                  ? "sample-ticket.pdf"
                  : "sample-image.png"
              }
            >
              <Download size={18} />
              Download sample
            </a>
          </Button>
        </DialogContent>
      </Dialog>
    </main>
  );
}
