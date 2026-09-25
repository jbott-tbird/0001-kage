import * as React from "react";
import { cn } from "../../lib/utils";
export const Input = React.forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement>
>(({ className, ...props }, ref) => (
  <input
    ref={ref}
    className={cn(
      "flex min-h-12 w-full rounded-lg border border-border bg-white px-3 py-2 text-base outline-none focus:border-primary focus:ring-2 focus:ring-primary/15 disabled:opacity-50",
      className,
    )}
    {...props}
  />
));
Input.displayName = "Input";
