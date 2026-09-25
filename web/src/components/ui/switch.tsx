import * as Primitive from "@radix-ui/react-switch";
import type { ComponentPropsWithoutRef } from "react";
export function Switch(props: ComponentPropsWithoutRef<typeof Primitive.Root>) {
  return (
    <Primitive.Root className="toggle" {...props}>
      <Primitive.Thumb className="toggle-thumb" />
    </Primitive.Root>
  );
}
