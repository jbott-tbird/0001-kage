import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { readStored, type MailState } from "./lib/mail";
type Store = {
  state: MailState;
  setState: React.Dispatch<React.SetStateAction<MailState>>;
  notify: (message: string) => void;
};
const Context = createContext<Store | null>(null);
export function MailProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState(readStored);
  const [toast, setToast] = useState("");
  useEffect(() => {
    try {
      localStorage.setItem("kage-mail-v1", JSON.stringify(state));
    } catch {
      setToast(
        "Device storage is full. Changes are available for this visit only.",
      );
    }
  }, [state]);
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(""), 4200);
    return () => clearTimeout(t);
  }, [toast]);
  return (
    <Context.Provider value={{ state, setState, notify: setToast }}>
      {children}
      {toast && (
        <div className="toast" role="status">
          {toast}
          <button
            onClick={() => setToast("")}
            aria-label="Dismiss notification"
          >
            ×
          </button>
        </div>
      )}
    </Context.Provider>
  );
}
export function useMail() {
  const value = useContext(Context);
  if (!value) throw new Error("MailProvider missing");
  return value;
}
