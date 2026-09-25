import * as React from "react";
import * as Primitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { cn } from "../../lib/utils";
export const Dialog = Primitive.Root;
export const DialogTrigger = Primitive.Trigger;
export const DialogTitle = Primitive.Title;
export const DialogDescription = Primitive.Description;
export function DialogContent({
  className,
  children,
  drawer = false,
  ...props
}: React.ComponentPropsWithoutRef<typeof Primitive.Content> & {
  drawer?: boolean;
}) {
  return (
    <Primitive.Portal>
      <Primitive.Overlay className="dialog-overlay" />
      <div className={drawer ? "drawer-viewport" : undefined}>
        <Primitive.Content
          className={cn(drawer ? "drawer-panel" : "dialog-panel", className)}
          {...props}
        >
          {children}
          <Primitive.Close className="dialog-close" aria-label="Close">
            <X size={21} />
          </Primitive.Close>
        </Primitive.Content>
      </div>
    </Primitive.Portal>
  );
}
