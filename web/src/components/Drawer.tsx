import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronDown, ChevronRight, Inbox, Plus, Settings } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "./ui/dialog";
import { Button } from "./ui/button";
import { Avatar, FolderGlyph } from "./shared";
import { useMail } from "../store";
import type { Folder } from "../lib/mail";
export function Drawer({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
}) {
  const { state, setState } = useMail();
  const navigate = useNavigate();
  const selectedAccount = state.folders.find(
    (f) => f.id === state.selectedFolder,
  )?.accountId;
  const [expanded, setExpanded] = useState<string[]>([
    selectedAccount || state.accounts[0].id,
  ]);
  const [nested, setNested] = useState<string[]>(["work-projects"]);
  const unread = (ids: string[]) =>
    state.messages.filter((m) => ids.includes(m.folderId) && !m.isRead).length;
  const choose = (id: string) => {
    setState((s) => ({ ...s, selectedFolder: id }));
    onOpenChange(false);
    navigate("/mail");
  };
  function row(folder: Folder, depth = 0) {
    const children = state.folders.filter((f) => f.parentId === folder.id);
    const count = unread([folder.id, ...children.map((f) => f.id)]);
    return (
      <div key={folder.id}>
        <div className="folder-line" style={{ paddingLeft: depth * 20 }}>
          <button
            className={`folder-button ${state.selectedFolder === folder.id ? "active" : ""}`}
            onClick={() => choose(folder.id)}
          >
            <FolderGlyph role={folder.role} />
            <span>{folder.name}</span>
            {count > 0 && <span className="count-badge">{count}</span>}
          </button>
          {children.length > 0 && (
            <button
              className="expand-folder"
              aria-label={`${nested.includes(folder.id) ? "Collapse" : "Expand"} ${folder.name}`}
              aria-expanded={nested.includes(folder.id)}
              onClick={() =>
                setNested((v) =>
                  v.includes(folder.id)
                    ? v.filter((id) => id !== folder.id)
                    : [...v, folder.id],
                )
              }
            >
              {nested.includes(folder.id) ? (
                <ChevronDown size={18} />
              ) : (
                <ChevronRight size={18} />
              )}
            </button>
          )}
        </div>
        {nested.includes(folder.id) && children.map((f) => row(f, depth + 1))}
      </div>
    );
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent drawer>
        <DialogTitle className="drawer-title">Mailboxes</DialogTitle>
        <DialogDescription className="sr-only">
          Choose an account or folder to view its messages.
        </DialogDescription>
        <div className="drawer-scroll">
          {state.features.unified && (
            <section className="drawer-card">
              <button
                className={`folder-button unified-button ${state.selectedFolder === "unified" ? "active" : ""}`}
                onClick={() => choose("unified")}
              >
                <Inbox />
                <span>
                  All Inboxes<small>{state.accounts.length} accounts</small>
                </span>
                <span className="count-badge">
                  {unread(
                    state.folders
                      .filter((f) => f.role === "inbox")
                      .map((f) => f.id),
                  )}
                </span>
              </button>
            </section>
          )}
          {state.accounts.map((a) => {
            const isOpen = expanded.includes(a.id);
            const count = unread(
              state.folders
                .filter((f) => f.accountId === a.id)
                .map((f) => f.id),
            );
            return (
              <section className="drawer-card" key={a.id}>
                <button
                  className="account-button"
                  aria-expanded={isOpen}
                  onClick={() =>
                    setExpanded((v) =>
                      isOpen ? v.filter((id) => id !== a.id) : [...v, a.id],
                    )
                  }
                >
                  <Avatar name={a.name} color={a.color} />
                  <span className="account-label">
                    <strong>{a.name}</strong>
                    <small>{a.address}</small>
                  </span>
                  {!isOpen && count > 0 && (
                    <span className="outline-count">{count}</span>
                  )}
                  {isOpen ? (
                    <ChevronDown size={20} />
                  ) : (
                    <ChevronRight size={20} />
                  )}
                </button>
                {isOpen && (
                  <div className="account-folders">
                    {state.folders
                      .filter((f) => f.accountId === a.id && !f.parentId)
                      .sort((a, b) => {
                        const order = [
                          "inbox",
                          "drafts",
                          "sent",
                          "archive",
                          "spam",
                          "trash",
                          "custom",
                        ];
                        return order.indexOf(a.role) - order.indexOf(b.role);
                      })
                      .map((f) => row(f))}
                  </div>
                )}
              </section>
            );
          })}
          <Button
            variant="ghost"
            className="add-account"
            onClick={() => {
              onOpenChange(false);
              navigate("/setup");
            }}
          >
            <Plus size={18} />
            Add account
          </Button>
        </div>
        <footer className="drawer-footer">
          <Button
            variant="outline"
            onClick={() => {
              onOpenChange(false);
              navigate("/settings");
            }}
          >
            <Settings size={18} />
            Settings
          </Button>
          <span>Demo mail</span>
        </footer>
      </DialogContent>
    </Dialog>
  );
}
