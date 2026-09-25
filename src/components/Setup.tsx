import { useEffect, useState } from "react";
import { useNavigate, useSearchParams, Link } from "react-router-dom";
import {
  ArrowLeft,
  Check,
  CheckCircle2,
  Eye,
  EyeOff,
  LockKeyhole,
  Mail,
  X,
} from "lucide-react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Switch } from "./ui/switch";
import { IconButton } from "./shared";
import { useMail } from "../store";
import { initialState } from "../lib/mail";
export function Welcome() {
  const navigate = useNavigate();
  const { state, setState } = useMail();
  return (
    <main className="welcome">
      <span className="prototype-label">Interactive mail prototype</span>
      <div className="welcome-center">
        <img
          className="welcome-logo"
          src="./assets/thunderbird-logo.png"
          alt="Thunderbird"
        />
        <p>
          An open source, privacy focused
          <br className="desktop-break" /> and ad-free email experience.
        </p>
      </div>
      <div className="welcome-actions">
        <Button size="lg" onClick={() => navigate("/setup")}>
          Get started
        </Button>
        <Link
          to="/mail"
          className="demo-link"
          onClick={() => {
            if (!state.accounts.length) setState(initialState());
          }}
        >
          Explore the demo inbox <span aria-hidden="true">→</span>
        </Link>
        <span className="welcome-caption">
          {state.accounts.length} sample accounts · no sign-in required
        </span>
      </div>
      <footer>
        Developed by a dedicated team at MZLA Technologies and a global
        <br className="desktop-break" /> community of volunteers. Part of the
        Mozilla family.
      </footer>
    </main>
  );
}
export function Setup() {
  const { state, setState } = useMail();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const step = Number(params.get("step") || "0");
  function demoEmail() {
    // Keep repeat walkthroughs from colliding with previously added accounts.
    let suffix = 1;
    let address = "skye@example.net";
    while (state.accounts.some((a) => a.address.toLowerCase() === address)) {
      address = `skye${++suffix}@example.net`;
    }
    return address;
  }
  const [email, setEmail] = useState(demoEmail);
  const [password, setPassword] = useState("sample-password");
  const [showPassword, setShowPassword] = useState(false);
  const [host, setHost] = useState("imap.example.com");
  const [outHost, setOutHost] = useState("smtp.example.com");
  const [port, setPort] = useState("993");
  const [outPort, setOutPort] = useState("465");
  const [security, setSecurity] = useState("SSL/TLS");
  const [outSecurity, setOutSecurity] = useState("SSL/TLS");
  const [auth, setAuth] = useState(true);
  const [accountId, setAccountId] = useState("");
  const [error, setError] = useState("");
  const next = () => {
    setError("");
    setParams({ step: String(step + 1) });
  };
  useEffect(() => {
    if (step > 0 && !email) setParams({}, { replace: true });
  }, [step, email, setParams]);
  const titles = [
    "Account Information",
    "Choose Email Account Type",
    "Manual Account Setup",
    "Manual Setup Complete",
  ];
  function save(e: React.FormEvent) {
    e.preventDefault();
    if (
      !host.trim() ||
      !outHost.trim() ||
      ![port, outPort].every(
        (p) =>
          Number.isInteger(Number(p)) && Number(p) > 0 && Number(p) <= 65535,
      )
    ) {
      setError("Enter both server addresses and ports between 1 and 65535.");
      return;
    }
    if (
      state.accounts.some(
        (a) => a.address.toLowerCase() === email.toLowerCase(),
      )
    ) {
      setError(
        "This account is already in your demo. Go back to change the email address.",
      );
      return;
    }
    const id = "account-" + crypto.randomUUID();
    setAccountId(id);
    setState((s) => ({
      ...s,
      accounts: [
        ...s.accounts,
        {
          id,
          name: email.split("@")[0],
          address: email,
          protocol: "imap",
          color: "purple",
          incoming: host,
          outgoing: outHost,
          incomingPort: Number(port),
          outgoingPort: Number(outPort),
          security,
          outgoingSecurity: outSecurity,
          requireAuth: auth,
        },
      ],
      folders: [
        ...s.folders,
        ...["inbox", "drafts", "sent", "archive", "trash", "spam"].map(
          (role) => ({
            id: `${id}-${role}`,
            accountId: id,
            name: role[0].toUpperCase() + role.slice(1),
            parentId: null,
            role,
          }),
        ),
      ],
      selectedFolder: id + "-inbox",
    }));
    setPassword("");
    next();
  }
  return (
    <main className="setup-background">
      <div className="setup-sheet">
        <div className="sheet-handle" />
        <header className="setup-header">
          <IconButton
            label={step === 0 || step === 3 ? "Close setup" : "Previous step"}
            onClick={() =>
              step === 0 || step === 3
                ? navigate(step === 3 ? "/mail" : "/")
                : setParams({ step: String(step - 1) })
            }
          >
            {step === 0 || step === 3 ? <X /> : <ArrowLeft />}
          </IconButton>
          <h1>{titles[step] || titles[0]}</h1>
          <span />
        </header>
        <div className="step-indicators" aria-label={`Step ${step + 1} of 4`}>
          {titles.map((_, i) => (
            <span key={i} className={i <= step ? "filled" : ""} />
          ))}
        </div>
        {step === 0 && (
          <form
            className="setup-form"
            onSubmit={(e) => {
              e.preventDefault();
              next();
            }}
          >
            <div className="setup-fields">
              <label className="line-field">
                Email Address
                <Input
                  required
                  type="email"
                  autoComplete="off"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="your.email@example.com"
                />
              </label>
              <label className="line-field">
                Password
                <div className="password-field">
                  <Input
                    required
                    autoComplete="off"
                    type={showPassword ? "text" : "password"}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter your password"
                  />
                  <IconButton
                    type="button"
                    label={showPassword ? "Hide password" : "Show password"}
                    onClick={() => setShowPassword((v) => !v)}
                  >
                    {showPassword ? <EyeOff size={19} /> : <Eye size={19} />}
                  </IconButton>
                </div>
              </label>
              <p className="form-note">
                <LockKeyhole size={15} /> This is a demo. Use sample
                credentials, not your real password.
              </p>
              <button
                type="button"
                className="text-link"
                onClick={() => {
                  setEmail(demoEmail());
                  setPassword("sample-password");
                }}
              >
                Reset demo credentials
              </button>
            </div>
            <Button size="lg" className="full-button" type="submit">
              Next
            </Button>
          </form>
        )}
        {step === 1 && (
          <div className="setup-form">
            <div className="setup-fields">
              <h2 className="field-heading">Protocol</h2>
              <div
                className="protocol-option future-option"
                aria-disabled="true"
              >
                <div>
                  <strong>
                    JMAP <small>Planned for v2.0</small>
                  </strong>
                  <span>JSON Meta Application Protocol</span>
                </div>
                <span className="radio-circle" />
              </div>
              <div className="protocol-option selected">
                <div>
                  <strong>IMAP</strong>
                  <span>Internet Message Access Protocol</span>
                </div>
                <Check className="radio-check" />
              </div>
              <aside className="tips">
                <strong>Tips</strong>
                <ul>
                  <li>IMAP keeps your email synced across devices.</li>
                  <li>SMTP handles sending your messages.</li>
                  <li>JMAP is included in the design for a future release.</li>
                </ul>
              </aside>
            </div>
            <Button size="lg" className="full-button" onClick={next}>
              Next
            </Button>
          </div>
        )}
        {step === 2 && (
          <form className="setup-form" onSubmit={save}>
            <div className="setup-fields server-fields">
              <h2 className="form-section-title">Incoming Mail Server</h2>
              <label className="line-field">
                Server Address
                <Input
                  required
                  value={host}
                  onChange={(e) => setHost(e.target.value)}
                  placeholder="imap.example.com"
                />
              </label>
              <div className="server-row">
                <label>
                  Port
                  <Input
                    required
                    type="number"
                    min="1"
                    max="65535"
                    value={port}
                    onChange={(e) => setPort(e.target.value)}
                  />
                </label>
                <label>
                  Connection security
                  <select
                    value={security}
                    onChange={(e) => {
                      setSecurity(e.target.value);
                      setPort(e.target.value === "SSL/TLS" ? "993" : "143");
                    }}
                  >
                    <option>SSL/TLS</option>
                    <option>STARTTLS</option>
                    <option>None</option>
                  </select>
                </label>
              </div>
              <h2 className="form-section-title">Outgoing Mail Server</h2>
              <label className="line-field">
                Server Address
                <Input
                  required
                  value={outHost}
                  onChange={(e) => setOutHost(e.target.value)}
                  placeholder="smtp.example.com"
                />
              </label>
              <div className="server-row">
                <label>
                  Port
                  <Input
                    required
                    type="number"
                    min="1"
                    max="65535"
                    value={outPort}
                    onChange={(e) => setOutPort(e.target.value)}
                  />
                </label>
                <label>
                  Connection security
                  <select
                    value={outSecurity}
                    onChange={(e) => {
                      setOutSecurity(e.target.value);
                      setOutPort(e.target.value === "SSL/TLS" ? "465" : "587");
                    }}
                  >
                    <option>SSL/TLS</option>
                    <option>STARTTLS</option>
                    <option>None</option>
                  </select>
                </label>
              </div>
              <div className="setting-row">
                <label htmlFor="require-auth">Require authentication</label>
                <Switch
                  id="require-auth"
                  checked={auth}
                  onCheckedChange={setAuth}
                />
              </div>
              <aside className="tips">
                <strong>Tips</strong>
                <p>
                  Use the server settings from your email provider. This
                  prototype saves settings locally and does not connect to a
                  mail server.
                </p>
              </aside>
              {error && (
                <p role="alert" className="error-text">
                  {error}
                </p>
              )}
            </div>
            <Button type="submit" size="lg" className="full-button">
              Save
            </Button>
          </form>
        )}
        {step === 3 && (
          <div className="setup-form">
            <div className="confirmation">
              <CheckCircle2 size={52} strokeWidth={1.5} />
              <h2>You’re all set.</h2>
              <p>Your demo account is ready.</p>
              <div className="account-summary">
                <Mail size={22} />
                <div>
                  <strong>
                    {email ||
                      state.accounts.find((a) => a.id === accountId)?.address ||
                      "Account saved"}
                  </strong>
                  <span>IMAP / SMTP</span>
                </div>
              </div>
              <p className="form-note">
                Settings saved on this device.
                <br />
                No connection to an email provider was made.
              </p>
            </div>
            <Button
              size="lg"
              className="full-button"
              onClick={() => navigate("/mail")}
            >
              Finish
            </Button>
          </div>
        )}
      </div>
    </main>
  );
}
