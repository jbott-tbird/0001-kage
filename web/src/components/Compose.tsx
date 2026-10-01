// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

import { useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ChevronDown, Paperclip, Save, Send, Trash2, X } from "lucide-react";
import { Button } from "./ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "./ui/dialog";
import { IconButton, PageHeader } from "./shared";
import { useMail } from "../store";
import { sizeLabel, type Attachment, type Message } from "../lib/mail";
export function Compose() {
  const { state, setState, notify } = useMail();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const source = state.messages.find(
    (m) =>
      m.id ===
      (params.get("reply") || params.get("forward") || params.get("draft")),
  );
  const reply = params.has("reply");
  const forward = params.has("forward");
  const draft = params.has("draft");
  const own =
    state.accounts.find(
      (a) =>
        a.id ===
        (source?.accountId ||
          state.folders.find((f) => f.id === state.selectedFolder)?.accountId),
    ) || state.accounts[0];
  const initialTo = draft
    ? source?.to.join(", ") || ""
    : reply
      ? source?.from.address || ""
      : "";
  const [accountId, setAccountId] = useState(own.id);
  const [to, setTo] = useState(initialTo);
  const [cc, setCc] = useState(
    draft
      ? source?.cc.join(", ") || ""
      : params.has("all")
        ? [...(source?.to || []), ...(source?.cc || [])]
            .filter((a) => a !== own.address)
            .join(", ")
        : "",
  );
  const [bcc, setBcc] = useState(draft ? source?.bcc?.join(", ") || "" : "");
  const [expanded, setExpanded] = useState(!!cc);
  const [subject, setSubject] = useState(
    source
      ? draft
        ? source.subject
        : forward
          ? "Fwd: " + source.subject
          : /^re:/i.test(source.subject)
            ? source.subject
            : "Re: " + source.subject
      : "",
  );
  const [body, setBody] = useState(
    draft
      ? source?.bodyText || ""
      : source
        ? `\n\n${forward ? "---------- Forwarded message ----------" : `On ${new Date(source.receivedAt).toLocaleDateString()}, ${source.from.name} wrote:`}\n${source.bodyText
            .split("\n")
            .map((line) => "> " + line)
            .join("\n")}`
        : "",
  );
  const [attachments, setAttachments] = useState<Attachment[]>(
    draft || forward ? source?.attachments || [] : [],
  );
  const [error, setError] = useState("");
  const [discard, setDiscard] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const addresses = (value: string) =>
    value
      .split(/[,;]+/)
      .map((s) => s.trim())
      .filter(Boolean);
  const account = state.accounts.find((a) => a.id === accountId)!;
  function persist(send: boolean) {
    if (
      send &&
      (!addresses(to).length ||
        [...addresses(to), ...addresses(cc), ...addresses(bcc)].some(
          (a) => !/^\S+@\S+\.\S+$/.test(a),
        ))
    ) {
      setError("Enter valid recipient addresses, separated by commas.");
      return;
    }
    if (send && state.offline) {
      setError(
        "You are in offline preview. Save a draft or go online in Settings before sending.",
      );
      return;
    }
    const folder = state.folders.find(
      (f) => f.accountId === accountId && f.role === (send ? "sent" : "drafts"),
    );
    if (!folder) return;
    const id = draft && source ? source.id : crypto.randomUUID();
    const message: Message = {
      id,
      accountId,
      folderId: folder.id,
      from: { name: account.name, address: account.address },
      to: addresses(to),
      cc: addresses(cc),
      bcc: addresses(bcc),
      subject,
      preview: body.slice(0, 150),
      bodyText: body,
      bodyHtml: null,
      receivedAt: new Date().toISOString(),
      isRead: true,
      isNewSinceLastVisit: false,
      attachments,
      relatedGroupId: reply
        ? source?.relatedGroupId || source?.id || null
        : null,
      isDraft: !send,
    };
    setState((s) => ({
      ...s,
      messages: [...s.messages.filter((m) => m.id !== id), message],
    }));
    notify(
      send ? "Message added to demo Sent. No email was sent." : "Draft saved",
    );
    navigate("/mail");
  }
  return (
    <main className="compose-shell">
      <PageHeader
        title={
          draft
            ? "Edit draft"
            : reply
              ? "Reply"
              : forward
                ? "Forward"
                : "New message"
        }
        onBack={() => setDiscard(true)}
      >
        <IconButton label="Save draft" onClick={() => persist(false)}>
          <Save size={21} />
        </IconButton>
        <Button onClick={() => persist(true)}>
          <Send size={17} />
          <span>Send</span>
        </Button>
      </PageHeader>
      <div className="compose-form">
        <div className="compose-field">
          <label htmlFor="sender">From</label>
          <select
            id="sender"
            value={accountId}
            onChange={(e) => setAccountId(e.target.value)}
          >
            {state.accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} &lt;{a.address}&gt;
              </option>
            ))}
          </select>
        </div>
        <div className="compose-field">
          <label htmlFor="to">To</label>
          <input
            id="to"
            type="text"
            autoFocus
            value={to}
            onChange={(e) => setTo(e.target.value)}
            placeholder="name@example.com"
          />
          <IconButton
            label="Show Cc and Bcc"
            aria-expanded={expanded}
            onClick={() => setExpanded((v) => !v)}
          >
            <ChevronDown size={18} />
          </IconButton>
        </div>
        {expanded && (
          <>
            <div className="compose-field">
              <label htmlFor="cc">Cc</label>
              <input
                id="cc"
                value={cc}
                onChange={(e) => setCc(e.target.value)}
              />
            </div>
            <div className="compose-field">
              <label htmlFor="bcc">Bcc</label>
              <input
                id="bcc"
                value={bcc}
                onChange={(e) => setBcc(e.target.value)}
              />
            </div>
          </>
        )}
        <div className="compose-field subject-field">
          <label htmlFor="subject">Subject</label>
          <input
            id="subject"
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder="Add a subject"
          />
        </div>
        {error && (
          <p className="error-text" role="alert">
            {error}
          </p>
        )}
        <textarea
          className="compose-body"
          aria-label="Message body"
          placeholder="Write your message…"
          value={body}
          onChange={(e) => setBody(e.target.value)}
        />
        {attachments.length > 0 && (
          <div className="compose-attachments">
            {attachments.map((a) => (
              <div key={a.id}>
                <Paperclip size={16} />
                <span>
                  {a.filename}
                  <small>{sizeLabel(a.sizeBytes)}</small>
                </span>
                <IconButton
                  label={`Remove ${a.filename}`}
                  onClick={() =>
                    setAttachments((v) => v.filter((item) => item.id !== a.id))
                  }
                >
                  <X size={16} />
                </IconButton>
              </div>
            ))}
          </div>
        )}
      </div>
      <footer className="compose-bottom">
        <input
          type="file"
          ref={fileInput}
          className="sr-only"
          multiple
          onChange={(e) => {
            setAttachments((v) => [
              ...v,
              ...Array.from(e.target.files || []).map((f) => ({
                id: crypto.randomUUID(),
                filename: f.name,
                mimeType: f.type || "application/octet-stream",
                sizeBytes: f.size,
                downloadState: "available" as const,
              })),
            ]);
            notify(
              "Attachment names added to the demo. Files are not uploaded.",
            );
            e.target.value = "";
          }}
        />
        <IconButton
          label="Attach a file"
          onClick={() => fileInput.current?.click()}
        >
          <Paperclip size={22} />
        </IconButton>
        <span>Demo message · saved only on this device</span>
        <IconButton label="Discard message" onClick={() => setDiscard(true)}>
          <Trash2 size={21} />
        </IconButton>
      </footer>
      <Dialog open={discard} onOpenChange={setDiscard}>
        <DialogContent>
          <DialogTitle className="dialog-title">Keep this message?</DialogTitle>
          <DialogDescription>
            Save it as a draft to pick up where you left off.
          </DialogDescription>
          <div className="dialog-actions">
            <Button
              variant="outline"
              onClick={() => {
                if (draft && source)
                  setState((s) => ({
                    ...s,
                    messages: s.messages.filter((m) => m.id !== source.id),
                  }));
                navigate("/mail");
              }}
            >
              Discard
            </Button>
            <Button onClick={() => persist(false)}>Save draft</Button>
          </div>
        </DialogContent>
      </Dialog>
    </main>
  );
}
