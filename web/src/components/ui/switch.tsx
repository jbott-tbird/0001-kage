// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

import * as Primitive from "@radix-ui/react-switch";
import type { ComponentPropsWithoutRef } from "react";
export function Switch(props: ComponentPropsWithoutRef<typeof Primitive.Root>) {
  return (
    <Primitive.Root className="toggle" {...props}>
      <Primitive.Thumb className="toggle-thumb" />
    </Primitive.Root>
  );
}
