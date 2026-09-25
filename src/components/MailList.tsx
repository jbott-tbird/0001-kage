import { useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  CheckCheck,
  CheckSquare,
  Menu,
  MoreHorizontal,
  Paperclip,
  Search,
  SlidersHorizontal,
  SquarePen,
  WifiOff,
  X,
} from "lucide-react";
import { Button } from "./ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "./ui/dropdown-menu";
import { IconButton } from "./shared";
import { Drawer } from "./Drawer";
import { dateLabel, filterMessages } from "../lib/mail";
import { useMail } from "../store";
export function MailList() {
  const { state, setState, notify } = useMail();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const query = params.get("q") || "";
  const scope = params.get("scope") || "account";
  const [drawer, setDrawer] = useState(false);
  const [searching, setSearching] = useState(!!query);
  const [unread, setUnread] = useState(false);
  const [attachments, setAttachments] = useState(false);
  const [oldest, setOldest] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const folder = state.folders.find((f) => f.id === state.selectedFolder);
  const account = state.accounts.find((a) => a.id === folder?.accountId);
  const effectiveFolder =
    folder?.id ||
    (state.features.unified && state.selectedFolder === "unified"
      ? "unified"
      : state.folders[0]?.id || "");
  const messages = useMemo(
    () =>
      filterMessages(state, {
        folderId: effectiveFolder,
        query,
        scope,
        unread,
        attachments,
      }),
    [state, effectiveFolder, query, scope, unread, attachments],
  );
  const displayed = oldest ? [...messages].reverse() : messages;
  function search(q: string, s = scope) {
    setParams(q ? { q, scope: s } : {}, { replace: true });
  }
  const markRead = () => {
    const ids = selecting ? selected : messages.map((m) => m.id);
    setState((s) => ({
      ...s,
      messages: s.messages.map((m) =>
        ids.includes(m.id) ? { ...m, isRead: true } : m,
      ),
    }));
    notify(`${ids.length} message${ids.length === 1 ? "" : "s"} marked read`);
    setSelecting(false);
    setSelected([]);
  };
  return (
    <main className="mail-shell">
      <header className="mail-header">
        <IconButton label="Open account drawer" onClick={() => setDrawer(true)}>
          <Menu size={25} />
        </IconButton>
        <div className="mail-heading">
          <h1>
            {query
              ? "Search"
              : effectiveFolder === "unified"
                ? "All Inboxes"
                : folder?.name || "Inbox"}
          </h1>
          <span>
            {effectiveFolder === "unified"
              ? `${state.accounts.length} accounts`
              : account?.address}
          </span>
        </div>
        <div className="header-actions">
          <IconButton
            label="Search messages"
            onClick={() => setSearching((v) => !v)}
          >
            <Search size={21} />
          </IconButton>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" aria-label="Filter and sort">
                <SlidersHorizontal size={23} />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              <DropdownMenuItem onSelect={() => setUnread((v) => !v)}>
                {unread ? "✓ " : ""}Unread only
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => setAttachments((v) => !v)}>
                {attachments ? "✓ " : ""}Has attachments
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => setOldest((v) => !v)}>
                {oldest ? "Newest first" : "Oldest first"}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" aria-label="Inbox options">
                <MoreHorizontal />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              <DropdownMenuItem
                onSelect={() => {
                  setSelecting((v) => !v);
                  setSelected([]);
                }}
              >
                <CheckSquare size={17} />
                {selecting ? "Cancel selection" : "Select messages"}
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={markRead}>
                <CheckCheck size={17} />
                Mark all read
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => navigate("/settings")}>
                Settings
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      {state.offline && (
        <div className="offline-banner">
          <WifiOff size={16} />
          Offline preview · downloaded messages are available
        </div>
      )}
      {searching && (
        <div className="search-bar">
          <Search size={18} />
          <input
            aria-label="Search mail"
            placeholder="Search messages"
            value={query}
            onChange={(e) => search(e.target.value)}
            autoFocus
          />
          <select
            aria-label="Search scope"
            value={scope}
            onChange={(e) => search(query, e.target.value)}
          >
            <option value="account">This account</option>
            <option value="all">All accounts</option>
          </select>
          <IconButton
            label="Close search"
            onClick={() => {
              search("");
              setSearching(false);
            }}
          >
            <X size={18} />
          </IconButton>
        </div>
      )}
      {(unread || attachments || query) && (
        <div className="filter-summary">
          <span>
            {messages.length} result{messages.length === 1 ? "" : "s"}
          </span>
          {unread && (
            <button onClick={() => setUnread(false)}>
              Unread <X size={13} />
            </button>
          )}
          {attachments && (
            <button onClick={() => setAttachments(false)}>
              Attachments <X size={13} />
            </button>
          )}
        </div>
      )}
      {selecting && (
        <div className="selection-bar">
          <label>
            <input
              type="checkbox"
              checked={
                selected.length === messages.length && messages.length > 0
              }
              onChange={(e) =>
                setSelected(e.target.checked ? messages.map((m) => m.id) : [])
              }
            />{" "}
            {selected.length} selected
          </label>
          <Button
            variant="ghost"
            onClick={markRead}
            disabled={!selected.length}
          >
            Mark read
          </Button>
          <Button
            variant="ghost"
            onClick={() => {
              setSelecting(false);
              setSelected([]);
            }}
          >
            Done
          </Button>
        </div>
      )}
      <div className="message-list">
        {displayed.map((message) => (
          <div key={message.id} className="message-row-wrap">
            {selecting && (
              <input
                className="row-checkbox"
                aria-label={`Select ${message.subject || "No subject"}`}
                type="checkbox"
                checked={selected.includes(message.id)}
                onChange={(e) =>
                  setSelected((v) =>
                    e.target.checked
                      ? [...v, message.id]
                      : v.filter((id) => id !== message.id),
                  )
                }
              />
            )}
            <button
              className={`message-row ${!message.isRead ? "unread" : ""}`}
              onClick={() => {
                if (selecting) {
                  setSelected((v) =>
                    v.includes(message.id)
                      ? v.filter((id) => id !== message.id)
                      : [...v, message.id],
                  );
                  return;
                }
                setState((s) => ({
                  ...s,
                  messages: s.messages.map((m) =>
                    m.id === message.id ? { ...m, isRead: true } : m,
                  ),
                }));
                if (message.isDraft) navigate("/compose?draft=" + message.id);
                else
                  navigate("/message/" + message.id, {
                    state: {
                      returnTo:
                        "/mail" + (query ? "?" + params.toString() : ""),
                    },
                  });
              }}
            >
              <span className="message-status">
                {message.isNewSinceLastVisit ? (
                  <span
                    className="dot new-dot"
                    title="New since your last visit"
                  />
                ) : !message.isRead ? (
                  <span className="dot unread-dot" title="Unread" />
                ) : null}
                <span className="sr-only">
                  {message.isNewSinceLastVisit ? "New. " : ""}
                  {message.isRead ? "Read" : "Unread"}
                </span>
              </span>
              <span className="message-lines">
                <span className="message-top">
                  <span className="sender-name">
                    {message.isDraft ? "Draft" : message.from.name}
                  </span>
                  <time dateTime={message.receivedAt}>
                    {dateLabel(message.receivedAt)}
                  </time>
                </span>
                <span className="message-subject">
                  <span dir="auto">{message.subject || "(No subject)"}</span>
                  {message.attachments.length > 0 && (
                    <Paperclip size={17} aria-label="Has attachment" />
                  )}
                  {state.features.threads && message.relatedGroupId && (
                    <span className="thread-count">
                      {
                        state.messages.filter(
                          (m) => m.relatedGroupId === message.relatedGroupId,
                        ).length
                      }
                    </span>
                  )}
                </span>
                <span className="message-preview">{message.preview}</span>
                {query && scope === "all" && (
                  <span className="result-account">
                    {
                      state.accounts.find((a) => a.id === message.accountId)
                        ?.address
                    }{" "}
                    ·{" "}
                    {state.folders.find((f) => f.id === message.folderId)?.name}
                  </span>
                )}
              </span>
            </button>
          </div>
        ))}
        {!displayed.length && (
          <div className="empty-state">
            <MailEmpty />
            <h2>{query ? "No matching messages" : "You’re all caught up"}</h2>
            <p>
              {query
                ? "Try a different phrase or search all accounts."
                : unread || attachments
                  ? "No messages match these filters."
                  : "Messages in this folder will appear here."}
            </p>
            {(unread || attachments) && (
              <Button
                variant="outline"
                onClick={() => {
                  setUnread(false);
                  setAttachments(false);
                }}
              >
                Clear filters
              </Button>
            )}
            {!query &&
              !state.messages.some((m) => m.folderId === effectiveFolder) && (
                <Button variant="outline" onClick={() => setDrawer(true)}>
                  Choose another mailbox
                </Button>
              )}
          </div>
        )}
      </div>
      <footer className="list-footer">
        <span>
          <i className="dot new-dot" />
          New since last visit
        </span>
        <span>
          <i className="dot unread-dot" />
          Unread
        </span>
        <span>{displayed.length} messages</span>
      </footer>
      <Button
        className="compose-fab"
        aria-label="Compose a message"
        onClick={() => navigate("/compose")}
      >
        <SquarePen size={24} />
        <span>Compose</span>
      </Button>
      <Drawer open={drawer} onOpenChange={setDrawer} />
    </main>
  );
}
function MailEmpty() {
  return (
    <div className="empty-icon">
      <CheckCheck size={30} strokeWidth={1.3} />
    </div>
  );
}
