// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

import { useLayoutEffect } from "react";
import {
  HashRouter,
  Navigate,
  Route,
  Routes,
  useLocation,
} from "react-router-dom";
import { MailProvider, useMail } from "./store";
import type { ReactNode } from "react";
import { Welcome, Setup } from "./components/Setup";
import { MailList } from "./components/MailList";
import { MessageView } from "./components/MessageView";
import { Compose } from "./components/Compose";
import { Settings } from "./components/Settings";
// Restore the list position when returning from a message, like the native flow.
const scrollPositions = new Map<string, number>();
function RouteEffects() {
  const { pathname } = useLocation();
  useLayoutEffect(() => {
    document.title =
      (pathname === "/"
        ? "Thunderbird"
        : pathname.startsWith("/message")
          ? "Message"
          : pathname.startsWith("/compose")
            ? "Compose"
            : pathname === "/settings"
              ? "Settings"
              : pathname === "/setup"
                ? "Account setup"
                : "Inbox") + " · Mail prototype";
    window.scrollTo?.(0, scrollPositions.get(pathname) || 0);
    return () => {
      scrollPositions.set(pathname, window.scrollY);
    };
  }, [pathname]);
  return null;
}
function RequireAccount({ children }: { children: ReactNode }) {
  const { state } = useMail();
  return state.accounts.length ? children : <Navigate to="/" replace />;
}
export default function App() {
  return (
    <HashRouter>
      <MailProvider>
        <RouteEffects />
        <Routes>
          <Route path="/" element={<Welcome />} />
          <Route path="/setup" element={<Setup />} />
          <Route
            path="/mail"
            element={
              <RequireAccount>
                <MailList />
              </RequireAccount>
            }
          />
          <Route
            path="/message/:id"
            element={
              <RequireAccount>
                <MessageView />
              </RequireAccount>
            }
          />
          <Route
            path="/compose"
            element={
              <RequireAccount>
                <Compose />
              </RequireAccount>
            }
          />
          <Route
            path="/settings"
            element={
              <RequireAccount>
                <Settings />
              </RequireAccount>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </MailProvider>
    </HashRouter>
  );
}
