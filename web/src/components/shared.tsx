import type { ReactNode } from "react";
import {
  ArrowLeft,
  Archive,
  FileText,
  Flame,
  Folder as FolderIcon,
  Inbox,
  Send,
  Trash2,
} from "lucide-react";
import { Button } from "./ui/button";
export function IconButton({
  label,
  children,
  onClick,
  ...props
}: React.ComponentProps<typeof Button> & {
  label: string;
  children: ReactNode;
}) {
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={label}
      title={label}
      onClick={onClick}
      {...props}
    >
      {children}
    </Button>
  );
}
export function PageHeader({
  title,
  onBack,
  children,
}: {
  title: string;
  onBack: () => void;
  children?: ReactNode;
}) {
  return (
    <header className="page-header">
      <IconButton label="Back" onClick={onBack}>
        <ArrowLeft />
      </IconButton>
      <h1>{title}</h1>
      <div className="header-actions">{children}</div>
    </header>
  );
}
export function FolderGlyph({ role }: { role: string }) {
  const Component =
    (
      {
        inbox: Inbox,
        drafts: FileText,
        sent: Send,
        archive: Archive,
        trash: Trash2,
        spam: Flame,
      } as Record<string, typeof Inbox>
    )[role] || FolderIcon;
  return <Component size={22} strokeWidth={1.5} />;
}
export function Avatar({
  name,
  color = "blue",
}: {
  name: string;
  color?: string;
}) {
  return (
    <span className={`avatar avatar-${color}`} aria-hidden="true">
      {name
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((v) => v[0])
        .join("")}
    </span>
  );
}
