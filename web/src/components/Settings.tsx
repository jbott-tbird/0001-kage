// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronRight, Plus, RotateCcw, Trash2 } from "lucide-react";
import { useMail } from "../store";
import { Avatar, IconButton, PageHeader } from "./shared";
import { Button } from "./ui/button";
import { Switch } from "./ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "./ui/dialog";
import { initialState, type Account } from "../lib/mail";
export function Settings() {
  const { state, setState, notify } = useMail();
  const navigate = useNavigate();
  const [remove, setRemove] = useState<Account | null>(null);
  const [detail, setDetail] = useState<Account | null>(null);
  const [reset, setReset] = useState(false);
  const update = (key: "unified" | "threads", val: boolean) =>
    setState((s) => ({
      ...s,
      features: { ...s.features, [key]: val },
      selectedFolder:
        key === "unified" && !val && s.selectedFolder === "unified"
          ? s.folders.find((f) => f.role === "inbox")!.id
          : s.selectedFolder,
    }));
  return (
    <main className="settings-shell">
      <PageHeader title="Settings" onBack={() => navigate("/mail")} />
      <div className="settings-body">
        <h2>Accounts</h2>
        <div className="settings-group">
          {state.accounts.map((a) => (
            <div className="settings-account" key={a.id}>
              <button onClick={() => setDetail(a)}>
                <Avatar name={a.name} color={a.color} />
                <span>
                  <strong>{a.name}</strong>
                  <small>{a.address}</small>
                </span>
                <ChevronRight size={18} />
              </button>
              <IconButton
                label={`Remove ${a.address}`}
                onClick={() => setRemove(a)}
              >
                <Trash2 size={18} />
              </IconButton>
            </div>
          ))}
          <button className="settings-add" onClick={() => navigate("/setup")}>
            <Plus size={20} />
            Add account
          </button>
        </div>
        <h2>Messages and storage</h2>
        <div className="settings-group">
          <label className="setting-row" htmlFor="attachment-policy">
            <span>
              Download attachments
              <small>Choose when attachments become available offline</small>
            </span>
            <select
              id="attachment-policy"
              value={state.downloadPolicy}
              onChange={(e) => {
                const value = e.target.value as "onDemand" | "automatic";
                setState((s) => ({
                  ...s,
                  downloadPolicy: value,
                  messages:
                    value === "automatic" && !s.offline
                      ? s.messages.map((m) => ({
                          ...m,
                          attachments: m.attachments.map((a) => ({
                            ...a,
                            downloadState: "available",
                          })),
                        }))
                      : s.messages,
                }));
                notify(
                  value === "automatic"
                    ? "Automatic attachment downloads enabled for the demo"
                    : "Attachments will download when opened",
                );
              }}
            >
              <option value="onDemand">On demand</option>
              <option value="automatic">Automatically</option>
            </select>
          </label>
          <p className="settings-note">
            Complete message bodies are available offline. Attachments can be
            downloaded separately.
          </p>
        </div>
        <h2>Preview upcoming features</h2>
        <p className="settings-intro">
          Explore the next steps in the design. These options only affect the
          sample data.
        </p>
        <div className="settings-group">
          <div className="setting-row">
            <label htmlFor="unified">
              Unified inbox<small>See all accounts together · after v1.0</small>
            </label>
            <Switch
              id="unified"
              checked={state.features.unified}
              onCheckedChange={(v) => update("unified", v)}
            />
          </div>
          <div className="setting-row">
            <label htmlFor="threads">
              Related messages
              <small>
                Show earlier messages in a conversation · after v1.0
              </small>
            </label>
            <Switch
              id="threads"
              checked={state.features.threads}
              onCheckedChange={(v) => update("threads", v)}
            />
          </div>
          <div className="setting-row">
            <span>
              JMAP accounts<small>Planned for v2.0</small>
            </span>
            <span className="quiet-badge">Coming later</span>
          </div>
        </div>
        <h2>Demo controls</h2>
        <div className="settings-group">
          <div className="setting-row">
            <label htmlFor="offline">
              Offline preview
              <small>Read cached mail and try downloading an attachment</small>
            </label>
            <Switch
              id="offline"
              checked={state.offline}
              onCheckedChange={(v) =>
                setState((s) => ({
                  ...s,
                  offline: v,
                  messages:
                    !v && s.downloadPolicy === "automatic"
                      ? s.messages.map((m) => ({
                          ...m,
                          attachments: m.attachments.map((a) => ({
                            ...a,
                            downloadState: "available",
                          })),
                        }))
                      : s.messages,
                }))
              }
            />
          </div>
          <button className="settings-link" onClick={() => navigate("/")}>
            <span>Getting started screen</span>
            <ChevronRight size={18} />
          </button>
          <button className="settings-link" onClick={() => setReset(true)}>
            <span>Reset sample data</span>
            <RotateCcw size={18} />
          </button>
        </div>
        <p className="settings-footnote">
          This is a local, interactive prototype. Mail actions do not connect to
          a provider. Sample changes stay in this browser.
        </p>
      </div>
      <Dialog open={!!remove} onOpenChange={(v) => !v && setRemove(null)}>
        <DialogContent>
          <DialogTitle className="dialog-title">Remove account?</DialogTitle>
          <DialogDescription>
            This removes {remove?.address} and its sample messages from this
            browser.
          </DialogDescription>
          <div className="dialog-actions">
            <Button variant="outline" onClick={() => setRemove(null)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={() => {
                if (!remove) return;
                const remaining = state.accounts.filter(
                  (a) => a.id !== remove.id,
                );
                setState((s) => ({
                  ...s,
                  accounts: remaining,
                  folders: s.folders.filter((f) => f.accountId !== remove.id),
                  messages: s.messages.filter((m) => m.accountId !== remove.id),
                  selectedFolder: remaining.length
                    ? remaining[0].id + "-inbox"
                    : "",
                }));
                if (!remaining.length) navigate("/");
                notify("Demo account removed");
                setRemove(null);
              }}
            >
              Remove
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      <Dialog open={reset} onOpenChange={setReset}>
        <DialogContent>
          <DialogTitle className="dialog-title">Reset sample data?</DialogTitle>
          <DialogDescription>
            Your local changes, drafts and added accounts will be replaced with
            the original sample data.
          </DialogDescription>
          <div className="dialog-actions">
            <Button variant="outline" onClick={() => setReset(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setState(initialState());
                setReset(false);
                notify("Sample data restored");
                navigate("/mail");
              }}
            >
              Reset demo
            </Button>
          </div>
        </DialogContent>
      </Dialog>
      <Dialog open={!!detail} onOpenChange={(v) => !v && setDetail(null)}>
        <DialogContent>
          <DialogTitle className="dialog-title">Account settings</DialogTitle>
          <DialogDescription>{detail?.address}</DialogDescription>
          <dl className="recipient-details">
            <dt>Protocol</dt>
            <dd>IMAP / SMTP</dd>
            <dt>Incoming</dt>
            <dd>
              {detail?.incoming || "imap.example.com"}:
              {detail?.incomingPort || 993}
              <br />
              {detail?.security || "SSL/TLS"}
            </dd>
            <dt>Outgoing</dt>
            <dd>
              {detail?.outgoing || "smtp.example.com"}:
              {detail?.outgoingPort || 465}
              <br />
              {detail?.outgoingSecurity || "SSL/TLS"}
            </dd>
            <dt>Status</dt>
            <dd>Local demo account</dd>
          </dl>
        </DialogContent>
      </Dialog>
    </main>
  );
}
