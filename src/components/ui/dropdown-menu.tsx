import * as React from "react";
import * as Primitive from "@radix-ui/react-dropdown-menu";
import { cn } from "../../lib/utils";
export const DropdownMenu = Primitive.Root;
export const DropdownMenuTrigger = Primitive.Trigger;
export function DropdownMenuContent({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof Primitive.Content>) {
  return (
    <Primitive.Portal>
      <Primitive.Content
        sideOffset={6}
        align="end"
        className={cn("menu-panel", className)}
        {...props}
      />
    </Primitive.Portal>
  );
}
export function DropdownMenuItem({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof Primitive.Item>) {
  return <Primitive.Item className={cn("menu-item", className)} {...props} />;
}

export function DropdownMenuCheckboxItem({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof Primitive.CheckboxItem>) {
  return (
    <Primitive.CheckboxItem className={cn("menu-item", className)} {...props} />
  );
}
